package io.github.victorkolesnyk.hamir.logic

import io.github.victorkolesnyk.hamir.core.BabbleConfig
import io.github.victorkolesnyk.hamir.core.BabbleGenerator
import io.github.victorkolesnyk.hamir.core.VoiceBank

/** Outcome of preparing the noise generator from the encrypted bank. */
sealed class BankLoadResult {
    class Ready(val generator: BabbleGenerator, val unreadableTakes: Int) : BankLoadResult()

    /** Too few takes could be decrypted, or they contain no usable speech. */
    object Unreadable : BankLoadResult()
    object Cancelled : BankLoadResult()
}

/** Decrypts the takes of the given profiles and builds the babble generator. Slow: worker thread only. */
object BankLoader {
    fun load(
        store: BankStore,
        profiles: List<Profile>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): BankLoadResult {
        val totals = profiles.map { store.takeNumbers(it).size }
        val total = totals.sum()
        val banks = ArrayList<VoiceBank>(profiles.size)
        var base = 0
        var unreadable = 0
        for ((index, profile) in profiles.withIndex()) {
            val offset = base
            val loaded = store.loadAll(profile, { done, _ -> onProgress(offset + done, total) }, isCancelled)
            if (isCancelled()) return BankLoadResult.Cancelled
            if (loaded.takes.size < EnrollmentRules.MIN_TAKES) return BankLoadResult.Unreadable
            unreadable += loaded.failed
            val bank = VoiceBank.fromUtterances(loaded.takes)
            if (bank.segmentCount <= 0) return BankLoadResult.Unreadable
            banks.add(bank)
            base += totals[index]
        }
        if (banks.isEmpty()) return BankLoadResult.Unreadable
        return try {
            BankLoadResult.Ready(BabbleGenerator(banks, BabbleConfig()), unreadable)
        } catch (e: IllegalArgumentException) {
            BankLoadResult.Unreadable
        }
    }
}
