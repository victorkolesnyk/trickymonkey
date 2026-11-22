package io.github.victorkolesnyk.hamir.logic

/** Navigation over the enrollment phrases. Phrase numbers are 1-based. */
object PhraseCursor {
    /** First phrase without a take; when everything is recorded, phrase 1. */
    fun firstUnrecorded(recorded: Set<Int>, total: Int): Int {
        for (n in 1..total) if (n !in recorded) return n
        return 1
    }

    fun next(current: Int, total: Int): Int = if (current >= total) 1 else current + 1

    fun previous(current: Int, total: Int): Int = if (current <= 1) total else current - 1

    /** Next phrase after [current] without a take (wrapping); null when all are recorded. */
    fun nextUnrecorded(current: Int, recorded: Set<Int>, total: Int): Int? {
        var n = current
        repeat(total) {
            n = next(n, total)
            if (n !in recorded) return n
        }
        return null
    }

    fun clamp(number: Int, total: Int): Int = number.coerceIn(1, total)
}
