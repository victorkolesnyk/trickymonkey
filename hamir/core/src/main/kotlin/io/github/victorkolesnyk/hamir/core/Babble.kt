package io.github.victorkolesnyk.hamir.core

import java.util.Random

/**
 * Preprocessed recordings of one speaker: silence trimmed, level normalised,
 * cut into speech fragments at energy minima.
 */
class VoiceBank private constructor() {
    /** Number of fragments available to the generator. */
    val segmentCount: Int get() = TODO()

    /** Seconds of speech left after trimming silence. */
    val speechSeconds: Double get() = TODO()

    companion object {
        /** @param utterances raw takes at [sampleRate]; they are resampled to [SAMPLE_RATE] when needed. */
        fun fromUtterances(utterances: List<ShortArray>, sampleRate: Int = SAMPLE_RATE): VoiceBank = TODO()
    }
}

data class BabbleConfig(
    /** Total simultaneous voices. The paper's optimum is 8. */
    val voices: Int = 8,
    val minSegmentMs: Int = 400,
    val maxSegmentMs: Int = 2000,
    /** Per-fragment playback speed is drawn from 1 +- speedJitter. */
    val speedJitter: Double = 0.06,
    val crossfadeMs: Int = 20,
    /** Long-term RMS of the mix before [Gain]. */
    val targetRmsDbfs: Double = -20.0,
)

/**
 * Endless personalised babble: [BabbleConfig.voices] overlapping streams of shuffled
 * speech fragments. With several banks the voices are split evenly between them
 * (two speakers -> 4 + 4). Not thread-safe.
 */
class BabbleGenerator(
    banks: List<VoiceBank>,
    config: BabbleConfig = BabbleConfig(),
    random: Random = java.security.SecureRandom(),
) {
    /** Fills out[offset until offset+length] completely with the next samples at [SAMPLE_RATE]. */
    fun read(out: ShortArray, offset: Int = 0, length: Int = out.size - offset): Unit = TODO()
}
