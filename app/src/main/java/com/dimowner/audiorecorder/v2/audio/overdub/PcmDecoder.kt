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

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Decodes any supported audio file (WAV, M4A/AAC, 3GP/AMR, …) to a flat 16-bit little-endian PCM
 * file, so an overdub's base track can be mixed sample-by-sample by [PcmMixer] regardless of its
 * container/codec.
 *
 * This is a focused, standalone counterpart to the shared legacy
 * [com.dimowner.audiorecorder.audio.AudioDecoder] (which decodes PCM internally but only keeps
 * amplitude gains). It is deliberately separate so the overdub feature does not perturb the
 * shared V1/V2 waveform pipeline.
 *
 * Synchronous and blocking — call it from a background dispatcher (see [OverdubMixer]).
 */
object PcmDecoder {

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** Result of a decode: the raw PCM file plus the format needed to mix and re-wrap it as WAV. */
    data class PcmResult(
        val pcmFile: File,
        val sampleRate: Int,
        val channelCount: Int,
    )

    /**
     * Decodes [input] into [pcmOutput] as raw 16-bit little-endian PCM.
     *
     * @return the [PcmResult] with the actual decoded sample rate / channel count (read from the
     *   codec's output format, which is authoritative for the decoded PCM).
     * @throws IOException if no audio track is found or decoding fails irrecoverably.
     */
    @Throws(IOException::class)
    fun decodeToPcm(input: File, pcmOutput: File): PcmResult {
        if (!input.exists() || !input.isFile) {
            throw IOException("Input file does not exist: ${input.absolutePath}")
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var fos: FileOutputStream? = null
        try {
            extractor.setDataSource(input.path)
            val trackIndex = selectAudioTrack(extractor)
                ?: throw IOException("No audio track found in ${input.absolutePath}")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Audio track has no MIME type")

            // Sensible defaults from the container; overwritten by the codec's output format below.
            var sampleRate = inputFormat.safeInt(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channelCount = inputFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT, 1)

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
            fos = FileOutputStream(pcmOutput)

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.safeInt(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = outFormat.safeInt(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* no output yet */ }
                    else -> {
                        if (outIndex >= 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                outBuf.get(chunk)
                                fos.write(chunk)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }
            fos.flush()
            return PcmResult(pcmOutput, sampleRate, channelCount)
        } catch (e: IllegalStateException) {
            throw IOException("PCM decode failed for ${input.absolutePath}", e)
        } finally {
            try {
                codec?.stop()
            } catch (e: IllegalStateException) {
                Timber.e(e, "codec.stop() failed")
            }
            codec?.release()
            extractor.release()
            try {
                fos?.close()
            } catch (e: IOException) {
                Timber.e(e, "Failed to close PCM output stream")
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun MediaFormat.safeInt(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
