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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure-JVM tests for [PcmMixer] — no Android, no hardware. This is the smallest test set
 * that proves the overdub mix is correct: summation, clipping, time-alignment, and gain.
 */
class PcmMixerTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Builds a little-endian 16-bit PCM byte array from sample values. */
    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * PcmMixer.BYTES_PER_SAMPLE)
        samples.forEachIndexed { idx, v -> PcmMixer.writeSample(out, idx * PcmMixer.BYTES_PER_SAMPLE, v) }
        return out
    }

    /** Reads a little-endian 16-bit PCM byte array back into sample values. */
    private fun samples(data: ByteArray): IntArray =
        IntArray(data.size / PcmMixer.BYTES_PER_SAMPLE) { i ->
            PcmMixer.readSample(data, i * PcmMixer.BYTES_PER_SAMPLE)
        }

    // ── read/write round-trip (the byte-order foundation everything rests on) ──

    @Test
    fun `readSample and writeSample round-trip across the full 16-bit range`() {
        val boundary = intArrayOf(-32768, -32767, -256, -1, 0, 1, 255, 256, 32766, 32767)
        val buf = ByteArray(boundary.size * 2)
        boundary.forEachIndexed { i, v -> PcmMixer.writeSample(buf, i * 2, v) }
        boundary.forEachIndexed { i, v ->
            assertEquals(v, PcmMixer.readSample(buf, i * 2))
        }
    }

    // ── summation ──────────────────────────────────────────────────────────

    @Test
    fun `overlapping samples are summed`() {
        val out = samples(PcmMixer.mix(pcm(100, 200, -100), pcm(50, -50, 100)))
        assertEquals(listOf(150, 150, 0), out.toList())
    }

    // ── clipping ─────────────────────────────────────────────────────────────

    @Test
    fun `positive overflow is clamped to 32767, not wrapped`() {
        val out = samples(PcmMixer.mix(pcm(30000), pcm(10000)))
        assertEquals(32767, out[0])
    }

    @Test
    fun `negative overflow is clamped to -32768, not wrapped`() {
        val out = samples(PcmMixer.mix(pcm(-30000), pcm(-10000)))
        assertEquals(-32768, out[0])
    }

    // ── time alignment via offset ──────────────────────────────────────────

    @Test
    fun `layer offset delays the layer onto the base timeline`() {
        // base: 4 samples; layer: 2 samples starting at sample index 2 (4 bytes).
        val base = pcm(10, 20, 30, 40)
        val layer = pcm(1, 2)
        val out = samples(PcmMixer.mix(base, layer, layerOffsetBytes = 2 * PcmMixer.BYTES_PER_SAMPLE))
        // first two samples = base only; last two = base + layer.
        assertEquals(listOf(10, 20, 31, 42), out.toList())
    }

    @Test
    fun `negative offset is treated as zero`() {
        val out = samples(PcmMixer.mix(pcm(10, 20), pcm(1, 2), layerOffsetBytes = -100))
        assertEquals(listOf(11, 22), out.toList())
    }

    // ── output length covers both inputs ─────────────────────────────────────

    @Test
    fun `output length covers a layer that runs past the base`() {
        val base = pcm(10, 20)
        val layer = pcm(1, 2, 3, 4)
        val out = samples(PcmMixer.mix(base, layer))
        // base contributes to first 2 samples; the tail is layer-only.
        assertEquals(listOf(11, 22, 3, 4), out.toList())
    }

    @Test
    fun `output length covers a layer pushed past the base by its offset`() {
        val base = pcm(10, 20)
        val layer = pcm(7)
        val out = samples(PcmMixer.mix(base, layer, layerOffsetBytes = 3 * PcmMixer.BYTES_PER_SAMPLE))
        // sample 2 is silence between base end and layer start; sample 3 is the layer.
        assertEquals(listOf(10, 20, 0, 7), out.toList())
    }

    // ── gain ─────────────────────────────────────────────────────────────────

    @Test
    fun `per-layer gain attenuates each source independently`() {
        val out = samples(PcmMixer.mix(pcm(1000), pcm(1000), baseGain = 0.5f, layerGain = 0f))
        assertEquals(500, out[0])
    }

    @Test
    fun `empty layer passes the base through unchanged at unit gain`() {
        val base = pcm(123, -456, 789)
        val out = PcmMixer.mix(base, ByteArray(0))
        assertEquals(base.toList(), out.toList())
    }

    // ── robustness ───────────────────────────────────────────────────────────

    @Test
    fun `a dangling trailing byte is ignored rather than read as half a sample`() {
        val base = byteArrayOf(0x10, 0x00, 0x7F) // 1.5 samples worth of bytes
        val out = PcmMixer.mix(base, ByteArray(0))
        assertEquals(1 * PcmMixer.BYTES_PER_SAMPLE, out.size)
        assertEquals(0x10, PcmMixer.readSample(out, 0))
    }

    // ── end-to-end: two distinct tones both survive the mix ────────────────────

    @Test
    fun `two different tones both survive the mix without clipping at half gain`() {
        val sr = 8000
        val n = sr // 1 second
        val low = ByteArray(n * 2)
        val high = ByteArray(n * 2)
        for (i in 0 until n) {
            val a = (sin(2.0 * PI * 220.0 * i / sr) * 20000).toInt()
            val b = (sin(2.0 * PI * 660.0 * i / sr) * 20000).toInt()
            PcmMixer.writeSample(low, i * 2, a)
            PcmMixer.writeSample(high, i * 2, b)
        }
        val mixed = PcmMixer.mix(low, high, baseGain = 0.5f, layerGain = 0.5f)

        // No sample may exceed 16-bit range (clamp upheld).
        val s = samples(mixed)
        assertTrue("no clipping expected at half gain", s.all { it in -32768..32767 })

        // Correlate the mix against each source tone; both correlations must be clearly
        // positive, proving neither layer was dropped (a silent or single-tone result
        // would make one correlation ~0).
        fun correlate(freq: Double): Double {
            var acc = 0.0
            for (i in 0 until n) acc += s[i] * sin(2.0 * PI * freq * i / sr)
            return acc / n
        }
        assertTrue("220Hz component must be present, was ${correlate(220.0)}", correlate(220.0) > 1000)
        assertTrue("660Hz component must be present, was ${correlate(660.0)}", correlate(660.0) > 1000)
    }

    // ── stereo / interleaving ──────────────────────────────────────────────────

    @Test
    fun `interleaved stereo channels are summed positionally`() {
        // Two stereo streams: samples are L,R,L,R. Positional mixing keeps L with L and R with R.
        val base = pcm(100, 200, 300, 400)
        val layer = pcm(10, 20, 30, 40)
        val out = samples(PcmMixer.mix(base, layer))
        assertEquals(listOf(110, 220, 330, 440), out.toList())
    }

    // ── offset alignment edge ─────────────────────────────────────────────────

    @Test
    fun `odd byte offset is rounded down to a whole sample boundary`() {
        // 3 bytes rounds to 2 (one 16-bit sample), so the layer lands on sample index 1.
        val base = pcm(10, 20, 30)
        val layer = pcm(5)
        val out = samples(PcmMixer.mix(base, layer, layerOffsetBytes = 3))
        assertEquals(listOf(10, 25, 30), out.toList())
    }

    // ── clipping boundary (sum lands exactly on the limit) ─────────────────────

    @Test
    fun `a sum landing exactly on the max is preserved, not clamped further`() {
        assertEquals(32767, samples(PcmMixer.mix(pcm(30000), pcm(2767)))[0])
        assertEquals(-32768, samples(PcmMixer.mix(pcm(-30000), pcm(-2768)))[0])
    }

    // ── gain on both layers / empties ──────────────────────────────────────────

    @Test
    fun `both layers attenuated then summed`() {
        // 1000*0.5 + 2000*0.5 = 1500
        assertEquals(1500, samples(PcmMixer.mix(pcm(1000), pcm(2000), baseGain = 0.5f, layerGain = 0.5f))[0])
    }

    @Test
    fun `mixing two empty streams yields an empty result`() {
        assertEquals(0, PcmMixer.mix(ByteArray(0), ByteArray(0)).size)
    }

    @Test
    fun `negative gain is rejected`() {
        var threw = false
        try {
            PcmMixer.mix(pcm(1), pcm(1), baseGain = -1f)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("expected IllegalArgumentException for negative gain", threw)
    }
}
