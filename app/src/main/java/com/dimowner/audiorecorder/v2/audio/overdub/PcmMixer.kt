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

/**
 * Pure, hardware-free mixer for signed 16-bit little-endian PCM audio.
 *
 * This is the keystone of the overdub feature: it is the only piece that decides whether
 * two layers actually combine correctly, and it is deliberately free of any Android,
 * coroutine, or file dependency so it can be fully unit-tested on the JVM with zero
 * hardware (see `PcmMixerTest`). The recording, playback, decode, and persistence layers
 * are all "plumbing" around this function.
 *
 * Conventions (matching what `WavRecorderV2` / [createWavHeader] produce):
 *  - Samples are signed 16-bit, little-endian.
 *  - Interleaved channels are mixed positionally, so both inputs must share the same
 *    channel count and sample rate. The overdub capture path enforces this by always
 *    capturing at the base record's sample rate and channel count.
 */
object PcmMixer {

    const val BYTES_PER_SAMPLE = 2

    private const val SAMPLE_MIN = Short.MIN_VALUE.toInt() // -32768
    private const val SAMPLE_MAX = Short.MAX_VALUE.toInt() // 32767

    /**
     * Mixes [layer] on top of [base], sample-by-sample, with per-layer gain and clipping.
     *
     * The output length covers both inputs: `max(baseLen, layerOffset + layerLen)`, so a
     * layer that starts late (positive offset) and/or runs past the end of the base track
     * is preserved in full. Where only one input has audio, that input passes through
     * (scaled by its gain); where they overlap, the samples are summed and clamped.
     *
     * @param base raw PCM of the backing/base track.
     * @param layer raw PCM of the newly recorded overdub layer.
     * @param baseGain linear gain applied to [base] (1f = unchanged). Must be >= 0.
     * @param layerGain linear gain applied to [layer] (1f = unchanged). Must be >= 0.
     * @param layerOffsetBytes byte offset at which [layer] begins relative to [base], used
     *        to align the take to where playback actually started. Negative values are
     *        clamped to 0; the value is rounded down to an even number so it always lands
     *        on a 16-bit sample boundary.
     * @return a new ByteArray of mixed PCM, every sample clamped to the signed 16-bit range.
     */
    @Suppress("LongParameterList")
    fun mix(
        base: ByteArray,
        layer: ByteArray,
        baseGain: Float = 1f,
        layerGain: Float = 1f,
        layerOffsetBytes: Int = 0,
    ): ByteArray {
        require(baseGain >= 0f && layerGain >= 0f) { "Gains must be non-negative" }

        // Only operate on whole samples; ignore any dangling trailing byte from a
        // truncated/odd-length stream so we never read half a sample.
        val baseLen = base.size - (base.size % BYTES_PER_SAMPLE)
        val layerLen = layer.size - (layer.size % BYTES_PER_SAMPLE)
        val offset = (layerOffsetBytes.coerceAtLeast(0) / BYTES_PER_SAMPLE) * BYTES_PER_SAMPLE

        val outLen = maxOf(baseLen, offset + layerLen)
        val out = ByteArray(outLen)

        var i = 0
        while (i < outLen) {
            val b = if (i < baseLen) readSample(base, i) else 0
            val layerIndex = i - offset
            val l = if (layerIndex in 0 until layerLen) readSample(layer, layerIndex) else 0

            val mixed = (b * baseGain + l * layerGain)
                .toInt()
                .coerceIn(SAMPLE_MIN, SAMPLE_MAX)
            writeSample(out, i, mixed)
            i += BYTES_PER_SAMPLE
        }
        return out
    }

    /** Reads a signed 16-bit little-endian sample starting at byte index [i]. */
    fun readSample(data: ByteArray, i: Int): Int {
        val lo = data[i].toInt() and 0xFF
        val hi = data[i + 1].toInt() // Byte is signed, so this sign-extends the high byte.
        return (hi shl 8) or lo
    }

    /** Writes [value] as a signed 16-bit little-endian sample starting at byte index [i]. */
    fun writeSample(data: ByteArray, i: Int, value: Int) {
        data[i] = (value and 0xFF).toByte()
        data[i + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
