/*
 * Copyright 2026 Dmytro Ponomarenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dimowner.audiorecorder.v2.audio.overdub

import com.dimowner.audiorecorder.v2.audio.createWavHeader
import com.dimowner.audiorecorder.v2.di.qualifiers.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Produces the final overdub by mixing a captured mic layer onto a base record, entirely offline.
 *
 * Pipeline (all on [IoDispatcher]):
 *  1. Decode the base record (any container) to raw PCM via [PcmDecoder].
 *  2. Strip the 44-byte WAV header from the captured layer (written by [OverdubRecorder]).
 *  3. Sum the two PCM streams with [PcmMixer], applying [playbackStartOffsetMills] as a
 *     frame-aligned leading offset so the layer lines up with where base playback started.
 *  4. Wrap the result as a WAV using [createWavHeader].
 *
 * This is the part the design calls "pure, deterministic, file-in/file-out" — the mixing math lives
 * in [PcmMixer] and is unit-tested with no hardware; this class is just I/O orchestration.
 *
 * MVP note: the base and mixed PCM are held in memory during the mix. Overdub takes are short
 * (bounded by the base's length), so this is acceptable for the MVP; a streaming mix can replace it
 * if very long bases must be supported. [MAX_IN_MEMORY_BYTES] guards against pathological sizes.
 */
@Singleton
class OverdubMixer @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Outcome of a successful mix. */
    data class Result(
        val outputFile: File,
        val durationMills: Long,
        val sampleRate: Int,
        val channelCount: Int,
    )

    /**
     * Mixes [layerWavFile] onto [baseFile] and writes the result to [outputWavFile].
     *
     * @param baseFile the base record (WAV/M4A/3GP/…).
     * @param layerWavFile the captured overdub layer, a WAV produced by [OverdubRecorder] at the
     *   base's sample rate and channel count.
     * @param outputWavFile destination for the mixed WAV (created/overwritten).
     * @param playbackStartOffsetMills where base playback was when capture began; the layer is
     *   delayed by this much so it aligns to the base timeline (0 for capture-from-start).
     * @param baseGain / [layerGain] linear gains; MVP passes unity.
     * @param workDir directory for the transient decoded-base PCM file; cleaned up before return.
     * @throws IOException on decode/IO failure or if inputs exceed [MAX_IN_MEMORY_BYTES].
     */
    @Suppress("LongParameterList")
    @Throws(IOException::class)
    suspend fun mix(
        baseFile: File,
        layerWavFile: File,
        outputWavFile: File,
        playbackStartOffsetMills: Long,
        workDir: File,
        baseGain: Float = 1f,
        layerGain: Float = 1f,
    ): Result = withContext(ioDispatcher) {
        val basePcmTemp = File(workDir, "overdub_base_${System.nanoTime()}.pcm")
        try {
            val decoded = PcmDecoder.decodeToPcm(baseFile, basePcmTemp)
            val sampleRate = decoded.sampleRate
            val channels = decoded.channelCount.coerceAtLeast(1)
            val frameSize = channels * PcmMixer.BYTES_PER_SAMPLE
            val byteRate = sampleRate.toLong() * frameSize

            guardSize(basePcmTemp.length(), "decoded base")
            guardSize(layerWavFile.length(), "captured layer")

            val basePcm = basePcmTemp.readBytes()
            // The layer is a WAV from OverdubRecorder; its raw PCM starts after the 44-byte header.
            val layerPcm = readWavPcm(layerWavFile)

            // Align the offset to a full frame so stereo channels never get swapped by a half-frame shift.
            val rawOffsetBytes = (playbackStartOffsetMills * byteRate / 1000L).toInt().coerceAtLeast(0)
            val offsetBytes = (rawOffsetBytes / frameSize) * frameSize

            val mixed = PcmMixer.mix(
                base = basePcm,
                layer = layerPcm,
                baseGain = baseGain,
                layerGain = layerGain,
                layerOffsetBytes = offsetBytes,
            )

            writeWav(outputWavFile, mixed, sampleRate, channels, byteRate)

            val durationMills = if (byteRate > 0) mixed.size * 1000L / byteRate else 0L
            Timber.d("Overdub mix complete: ${outputWavFile.absolutePath} ${durationMills}ms")
            Result(outputWavFile, durationMills, sampleRate, channels)
        } finally {
            if (basePcmTemp.exists() && !basePcmTemp.delete()) {
                Timber.w("Failed to delete temp base PCM: ${basePcmTemp.absolutePath}")
            }
        }
    }

    /** Reads the raw PCM payload of a WAV produced by [OverdubRecorder] (fixed 44-byte header). */
    @Throws(IOException::class)
    private fun readWavPcm(wav: File): ByteArray {
        val all = wav.readBytes()
        return if (all.size <= WAV_HEADER_BYTES) ByteArray(0)
        else all.copyOfRange(WAV_HEADER_BYTES, all.size)
    }

    @Throws(IOException::class)
    private fun writeWav(out: File, pcm: ByteArray, sampleRate: Int, channels: Int, byteRate: Long) {
        FileOutputStream(out).use { fos ->
            val totalAudioLen = pcm.size.toLong()
            fos.write(
                createWavHeader(
                    totalAudioLen = totalAudioLen,
                    totalDataLen = totalAudioLen + 36,
                    sampleRate = sampleRate,
                    channels = channels,
                    byteRate = byteRate,
                )
            )
            fos.write(pcm)
            fos.flush()
        }
    }

    @Throws(IOException::class)
    private fun guardSize(bytes: Long, what: String) {
        if (bytes > MAX_IN_MEMORY_BYTES) {
            throw IOException("$what is too large to mix in memory ($bytes bytes); streaming mix not yet implemented")
        }
    }

    companion object {
        private const val WAV_HEADER_BYTES = 44

        /** ~10 min of 48 kHz stereo 16-bit PCM. Beyond this, fail fast rather than OOM. */
        private const val MAX_IN_MEMORY_BYTES = 48_000L * 2 * 2 * 60 * 10
    }
}
