package io.github.victorkolesnyk.hamir.logic

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmTest {
    @Test
    fun bufferGrowsAndKeepsOrder() {
        val buffer = PcmBuffer(maxSamples = 1000, initialCapacity = 4)
        val chunk = ShortArray(7) { (it + 1).toShort() }
        assertEquals(7, buffer.append(chunk, 0, 7))
        assertEquals(5, buffer.append(chunk, 2, 5))
        assertEquals(12, buffer.size)
        assertContentEquals(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 3, 4, 5, 6, 7), buffer.toArray())
        assertFalse(buffer.isFull)
    }

    @Test
    fun bufferStopsAtCapacity() {
        val buffer = PcmBuffer(maxSamples = 10, initialCapacity = 64)
        assertEquals(8, buffer.append(ShortArray(8) { 1 }, 0, 8))
        assertEquals(2, buffer.append(ShortArray(8) { 2 }, 0, 8))
        assertTrue(buffer.isFull)
        assertEquals(0, buffer.append(ShortArray(8) { 3 }, 0, 8))
        assertEquals(10, buffer.toArray().size)
        assertEquals(2, buffer.toArray()[9])
    }

    @Test
    fun bufferHoldsFifteenSecondsExactly() {
        val max = 15 * 32000
        val buffer = PcmBuffer(max)
        val chunk = ShortArray(3200)
        var total = 0
        repeat(200) { total += buffer.append(chunk, 0, chunk.size) }
        assertEquals(max, total)
        assertEquals(max, buffer.size)
    }

    @Test
    fun rampInterpolatesLinearly() {
        val data = ShortArray(100) { 10000 }
        PcmMath.ramp(data, 0, 100, 0f, 1f)
        assertEquals(0, data[0].toInt())
        assertTrue(abs(data[50] - 5000) <= 2)
        assertTrue(data[99] in 9800..10000)
        for (i in 1 until 100) assertTrue(data[i] >= data[i - 1])
    }

    @Test
    fun rampTouchesOnlyTheGivenRange() {
        val data = ShortArray(10) { 1000 }
        PcmMath.ramp(data, 3, 4, 0f, 0f)
        assertContentEquals(shortArrayOf(1000, 1000, 1000, 0, 0, 0, 0, 1000, 1000, 1000), data)
    }

    @Test
    fun slewLimitsTheStep() {
        assertEquals(3.0, PcmMath.slew(0.0, 10.0, 3.0))
        assertEquals(-3.0, PcmMath.slew(0.0, -10.0, 3.0))
        assertEquals(2.0, PcmMath.slew(0.0, 2.0, 3.0))
        assertEquals(5.0, PcmMath.slew(5.0, 5.0, 3.0))
    }

    @Test
    fun meterMapsDbfsToPercent() {
        assertEquals(0, PcmMath.meterPercent(-120.0))
        assertEquals(0, PcmMath.meterPercent(-60.0))
        assertEquals(50, PcmMath.meterPercent(-30.0))
        assertEquals(100, PcmMath.meterPercent(0.0))
        assertEquals(100, PcmMath.meterPercent(3.0))
        assertEquals(0, PcmMath.meterPercent(Double.NaN))
    }

    @Test
    fun rawSourceMeterStillMovesForQuietSpeech() {
        // Speech peaks around -50 dBFS on the UNPROCESSED source.
        assertEquals(0, PcmMath.meterPercent(-50.0 - 10.0, PcmMath.METER_FLOOR_TUNED_DBFS))
        assertEquals(37, PcmMath.meterPercent(-50.0, PcmMath.METER_FLOOR_RAW_DBFS))
        assertEquals(0, PcmMath.meterPercent(-80.0, PcmMath.METER_FLOOR_RAW_DBFS))
        assertEquals(100, PcmMath.meterPercent(0.0, PcmMath.METER_FLOOR_RAW_DBFS))
    }

    private fun constant(n: Int) = ShortArray(n) { 10000 }

    @Test
    fun envelopeFadesInThenPassesThrough() {
        val env = FadeEnvelope(100)
        val first = constant(60)
        env.process(first, 0, 60)
        assertEquals(0, first[0].toInt())
        assertTrue(first[59] in 5700..6000)
        val second = constant(60)
        env.process(second, 0, 60)
        assertTrue(second[0] in 5900..6100, "no jump between chunks: ${second[0]}")
        assertEquals(10000, second[45].toInt())
        val third = constant(60)
        env.process(third, 0, 60)
        assertContentEquals(constant(60), third)
        assertFalse(env.finished)
    }

    @Test
    fun envelopeFadesOutToSilenceAndStaysSilent() {
        val env = FadeEnvelope(100)
        env.process(constant(200), 0, 200)
        env.beginFadeOut()
        val out = constant(150)
        env.process(out, 0, 150)
        assertEquals(10000, out[0].toInt())
        assertTrue(abs(out[50] - 5000) <= 100)
        assertEquals(0, out[100].toInt())
        assertEquals(0, out[149].toInt())
        assertTrue(env.finished)
        val later = constant(20)
        env.process(later, 0, 20)
        assertContentEquals(ShortArray(20), later)
    }

    @Test
    fun fadeOutDuringFadeInStartsFromTheCurrentGain() {
        val env = FadeEnvelope(100)
        val rising = constant(30)
        env.process(rising, 0, 30)
        val reached = rising[29].toInt()
        env.beginFadeOut()
        val falling = constant(100)
        env.process(falling, 0, 100)
        assertTrue(abs(falling[0] - reached) <= 200, "jump from $reached to ${falling[0]}")
        assertEquals(0, falling[35].toInt())
        assertTrue(env.finished)
    }

    @Test
    fun envelopeHasNoLargeStepsAcrossSmallChunks() {
        val env = FadeEnvelope(4800)
        var previous = 0
        var maxStep = 0
        repeat(20) { chunk ->
            if (chunk == 12) env.beginFadeOut()
            val buf = constant(640)
            env.process(buf, 0, 640)
            for (v in buf) {
                maxStep = maxOf(maxStep, abs(v - previous))
                previous = v.toInt()
            }
        }
        assertTrue(maxStep <= 5, "step $maxStep")
        assertTrue(env.finished)
    }

    @Test
    fun zeroLengthFadeIsANoOpUntilStopped() {
        val env = FadeEnvelope(0)
        val buf = constant(10)
        env.process(buf, 0, 10)
        assertContentEquals(constant(10), buf)
        env.beginFadeOut()
        env.process(buf, 0, 10)
        assertContentEquals(ShortArray(10), buf)
    }
}
