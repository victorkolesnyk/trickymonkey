package io.github.victorkolesnyk.hamir.logic

/** Voice profile. The enum name is also the directory name under filesDir/bank. */
enum class Profile { A, B }

/** Engineering assumptions from SPEC 6.1, not figures from the paper. */
object EnrollmentRules {
    const val PHRASE_COUNT = 100
    const val MIN_TAKES = 20
    const val RECOMMENDED_TAKES = 60
    const val MAX_TAKE_SECONDS = 15

    fun isReady(takeCount: Int): Boolean = takeCount >= MIN_TAKES
}

/** What the main screen shows about one profile. Seconds are recorded audio, derived from file sizes. */
data class ProfileStats(val takeCount: Int, val recordedSeconds: Double) {
    val ready: Boolean get() = EnrollmentRules.isReady(takeCount)
}
