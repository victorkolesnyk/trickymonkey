package io.github.victorkolesnyk.hamir.logic

import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BankLoaderTest {
    private lateinit var dir: File
    private lateinit var store: BankStore

    /** Speech-like test signal: one second of syllable bursts with pauses between them. */
    private fun utterance(seed: Int): ShortArray {
        val rate = 32000
        val out = ShortArray(rate * 2)
        val pitch = 110.0 + seed * 3
        for (i in out.indices) {
            val t = i.toDouble() / rate
            val syllable = if ((t * 4).toInt() % 2 == 0) 1.0 else 0.02
            val edge = minOf(1.0, minOf(t, 2.0 - t) * 10)
            val tone = sin(2 * PI * pitch * t) + 0.5 * sin(2 * PI * pitch * 2.1 * t) + 0.3 * sin(2 * PI * 1900 * t)
            out[i] = (tone * 6000 * syllable * edge).toInt().toShort()
        }
        return out
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("hamir-loader").toFile()
        store = BankStore(File(dir, "bank"), MemoryKeyProvider(), FakeCodec)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun tooFewReadableTakesAreReportedWithoutTouchingTheGenerator() {
        for (n in 1..5) store.saveTake(Profile.A, n, utterance(n))
        assertIs<BankLoadResult.Unreadable>(BankLoader.load(store, listOf(Profile.A)))
        assertIs<BankLoadResult.Unreadable>(BankLoader.load(store, listOf(Profile.B)))
        assertIs<BankLoadResult.Unreadable>(BankLoader.load(store, emptyList()))
    }

    @Test
    fun cancellationStopsLoading() {
        for (n in 1..25) store.saveTake(Profile.A, n, utterance(n))
        var polls = 0
        assertIs<BankLoadResult.Cancelled>(BankLoader.load(store, listOf(Profile.A), isCancelled = { ++polls > 3 }))
    }

    @Test
    fun twoProfilesGiveAWorkingGeneratorAndContinuousProgress() {
        for (n in 1..22) store.saveTake(Profile.A, n, utterance(n))
        for (n in 1..20) store.saveTake(Profile.B, n, utterance(n + 40))
        store.takeFile(Profile.A, 22).writeBytes(ByteArray(100) { 1 })
        val progress = ArrayList<Pair<Int, Int>>()
        val result = BankLoader.load(store, listOf(Profile.A, Profile.B), { done, total -> progress.add(done to total) })
        val ready = assertIs<BankLoadResult.Ready>(result)
        assertEquals(1, ready.unreadableTakes)
        assertEquals((1..42).map { it to 42 }, progress)

        val out = ShortArray(32000)
        ready.generator.read(out, 0, out.size)
        assertTrue(out.any { it.toInt() != 0 }, "generator produced silence")
    }
}
