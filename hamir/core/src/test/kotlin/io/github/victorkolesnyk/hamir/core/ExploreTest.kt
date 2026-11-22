package io.github.victorkolesnyk.hamir.core

import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

class ExploreTest {
    /** The detector as it was before the fix. */
    private fun oldActive(samples: ShortArray): Double {
        val powers = SpeechActivity.framePowers(samples, SAMPLE_RATE)
        val sorted = powers.copyOf(); sorted.sort()
        var top = sorted.size - 1
        var ceiling = Double.POSITIVE_INFINITY
        var threshold: Double
        var level: Double
        while (true) {
            var sum = 0.0
            var i = top
            while (i >= 0) {
                val accepted = top - i
                if (accepted > 0 && sorted[i] * 38.9 < sum / accepted) break
                sum += sorted[i]; i--
            }
            val accepted = top - i
            if (i >= 0 && accepted < 10) { ceiling = sorted[i + 1]; top = i; continue }
            threshold = if (i < 0) 0.0 else sorted[i + 1]
            level = sum / accepted
            break
        }
        val floor = sorted[(top * 0.10).toInt()]
        threshold = max(threshold, min(floor * 4.0, level * 0.1))
        var sum = 0.0; var count = 0
        for (p in powers) if (p > 0.0 && p >= threshold && p < ceiling) { sum += p; count++ }
        return if (count == 0) SILENCE_DBFS else Level.powerToDbfs(sum / count)
    }

    private fun stats(name: String, errors: List<Double>) {
        val s = errors.sorted()
        fun q(x: Double) = s[((s.size - 1) * x).toInt()]
        println("X %-34s n=%d min=%+.2f p05=%+.2f median=%+.2f p95=%+.2f max=%+.2f beyond1.5=%d".format(
            name, s.size, s.first(), q(0.05), q(0.5), q(0.95), s.last(), s.count { Math.abs(it) > 1.5 }))
    }

    @Test
    fun explore() {
        for (model in listOf(TestSignals.SpeechModel.SYLLABLES)) {
            val n = if (model == TestSignals.SpeechModel.SYLLABLES) 200 else 400
            val cases = (0L until n).map { TestSignals.speechInNoise(it, model) }
            val powers = cases.map { SpeechActivity.framePowers(it.samples, SAMPLE_RATE) }
            println("X $model share ${cases.minOf { it.speechShare }}..${cases.maxOf { it.speechShare }} snr ${cases.minOf { it.speechDbfs - it.ambientDbfs }}..${cases.maxOf { it.speechDbfs - it.ambientDbfs }}")
            val old = cases.map { oldActive(it.samples) - it.speechDbfs }
            stats("$model OLD", old)
            println("X sounding-only truth is above segment truth by ${cases.minOf { it.soundingDbfs - it.speechDbfs }}..${cases.maxOf { it.soundingDbfs - it.speechDbfs }}")
            stats("$model OLD ambient<16.5", old.filterIndexed { i, _ -> cases[i].speechDbfs - cases[i].ambientDbfs < 16.5 })
            for (hang in intArrayOf(0, 60, 100, 140, 200)) {
                val e = cases.indices.map { Level.powerToDbfs(SpeechActivity.measure(powers[it], hangoverMs = hang).power) - cases[it].speechDbfs }
                stats("$model new hang=$hang", e)
                stats("$model new hang=$hang ambient<16.5", e.filterIndexed { i, _ -> cases[i].speechDbfs - cases[i].ambientDbfs < 16.5 })
                if (model == TestSignals.SpeechModel.SYLLABLES) {
                    val e2 = cases.indices.map { Level.powerToDbfs(SpeechActivity.measure(powers[it], hangoverMs = hang).power) - cases[it].soundingDbfs }
                    stats("$model new hang=$hang vs sounding-only", e2)
                }
            }
        }
    }
}
