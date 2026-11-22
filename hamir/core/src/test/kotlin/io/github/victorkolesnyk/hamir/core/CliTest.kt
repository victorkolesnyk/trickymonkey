package io.github.victorkolesnyk.hamir.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliTest {
    private lateinit var dir: File
    private lateinit var speakerA: File
    private lateinit var speakerB: File

    private class Result(val code: Int, val out: String, val err: String)

    private companion object {
        val rates = intArrayOf(44100, 16000, 48000, 32000, 22050, 8000)
        val takesA: List<ShortArray> by lazy { rates.mapIndexed { i, rate -> TestSignals.utterance(900L + i, rate = rate, pitchHz = 110.0).samples } }
        val takesB: List<ShortArray> by lazy { rates.mapIndexed { i, rate -> TestSignals.utterance(950L + i, rate = rate, pitchHz = 200.0).samples } }
    }

    private fun run(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli.run(arrayOf(*args), PrintStream(out, true), PrintStream(err, true))
        return Result(code, out.toString(), err.toString())
    }

    @BeforeTest
    fun createTakes() {
        dir = Files.createTempDirectory("hamir-cli").toFile()
        speakerA = File(dir, "a").apply { mkdirs() }
        speakerB = File(dir, "b").apply { mkdirs() }
        // Takes at assorted sample rates, as they would come from other recorders.
        for (i in rates.indices) {
            Wav.write(File(speakerA, "take%02d.wav".format(i)), takesA[i], rates[i])
            Wav.write(File(speakerB, "take%02d.WAV".format(i)), takesB[i], rates[i])
        }
        File(speakerA, "notes.txt").writeText("not audio")
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun generateWritesBabbleOfTheRequestedLength() {
        val output = File(dir, "out/babble.wav")
        val result = run("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "20", "--seed", "1")
        assertEquals(Cli.EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains("6 files"), result.out)
        val wav = Wav.read(output)
        assertEquals(SAMPLE_RATE, wav.sampleRate)
        assertEquals(20 * SAMPLE_RATE, wav.samples.size)
        val rms = Level.rmsDbfs(wav.samples)
        assertTrue(abs(rms - (-20.0)) <= 1.0, "level $rms")
        assertEquals(0.0, Level.clippedFraction(wav.samples))
        val (lowest, _) = TestSignals.windowLevelRange(wav.samples)
        assertTrue(lowest > rms - 6.0)
    }

    @Test
    fun seedMakesTheOutputReproducible() {
        val first = File(dir, "first.wav")
        val second = File(dir, "second.wav")
        val third = File(dir, "third.wav")
        val unseeded = File(dir, "unseeded.wav")
        assertEquals(0, run("generate", "--in", speakerA.path, "--out", first.path, "--seconds", "3", "--seed", "7").code)
        assertEquals(0, run("generate", "--in", speakerA.path, "--out", second.path, "--seconds", "3", "--seed", "7").code)
        assertEquals(0, run("generate", "--in", speakerA.path, "--out", third.path, "--seconds", "3", "--seed", "8").code)
        assertEquals(0, run("generate", "--in", speakerA.path, "--out", unseeded.path, "--seconds", "3").code)
        assertContentEquals(first.readBytes(), second.readBytes())
        assertFalse(first.readBytes().contentEquals(third.readBytes()))
        assertFalse(first.readBytes().contentEquals(unseeded.readBytes()))
    }

    @Test
    fun optionsReachTheGenerator() {
        val output = File(dir, "options.wav")
        val result = run(
            "generate", "--seconds", "2.5", "--voices", "4", "--min-seg", "200", "--max-seg", "500",
            "--speed-jitter", "0.1", "--seed", "3", "--out", output.path, "--in", speakerA.path,
        )
        assertEquals(Cli.EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains("4 voices"), result.out)
        val wav = Wav.read(output)
        assertEquals(80000, wav.samples.size)
        // Same as calling the library with these settings on the same (resampled) takes.
        val takes = speakerA.listFiles { f -> f.name.endsWith(".wav") }!!.sortedBy { it.name }.map {
            val take = Wav.read(it)
            Resampler.resample(take.samples, take.sampleRate, SAMPLE_RATE)
        }
        val config = BabbleConfig(voices = 4, minSegmentMs = 200, maxSegmentMs = 500, speedJitter = 0.1)
        val expected = TestSignals.generate(BabbleGenerator(listOf(VoiceBank.fromUtterances(takes)), config, java.util.Random(3)), 2.5)
        assertContentEquals(expected, wav.samples)
    }

    @Test
    fun severalInputDirectoriesBecomeSeveralSpeakers() {
        val output = File(dir, "two.wav")
        val result = run("generate", "--in", speakerA.path, "--in", speakerB.path, "--out", output.path, "--seconds", "10", "--seed", "5")
        assertEquals(Cli.EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains(speakerA.path) && result.out.contains(speakerB.path), result.out)
        val rms = Level.rmsDbfs(Wav.read(output).samples)
        assertTrue(abs(rms - (-20.0)) <= 1.0, "level $rms")
    }

    @Test
    fun usageErrorsReturnExitCodeTwo() {
        val output = File(dir, "never.wav")
        val cases = listOf(
            arrayOf(),
            arrayOf("frobnicate"),
            arrayOf("generate"),
            arrayOf("generate", "--in", speakerA.path, "--seconds", "5"),
            arrayOf("generate", "--out", output.path, "--seconds", "5"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "abc"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "-1"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "5", "--voices", "0"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "5", "--seed", "x"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "5", "--speed-jitter", "2"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "5", "--min-seg", "900", "--max-seg", "300"),
            arrayOf("generate", "--in", speakerA.path, "--out", output.path, "--seconds", "5", "--bogus", "1"),
        )
        for (args in cases) {
            val result = run(*args)
            assertEquals(Cli.EXIT_USAGE, result.code, args.joinToString(" "))
            assertTrue((result.out + result.err).contains("Usage:"), args.joinToString(" "))
        }
        assertFalse(output.exists())
    }

    @Test
    fun helpPrintsUsage() {
        val result = run("--help")
        assertEquals(Cli.EXIT_OK, result.code)
        assertTrue(result.out.contains("--speed-jitter"))
        assertEquals(Cli.EXIT_OK, run("generate", "--help").code)
    }

    @Test
    fun unusableInputReturnsExitCodeOne() {
        val output = File(dir, "never.wav")
        val missing = run("generate", "--in", File(dir, "nowhere").path, "--out", output.path, "--seconds", "5")
        assertEquals(Cli.EXIT_FAILED, missing.code)
        assertTrue(missing.err.contains("nowhere"), missing.err)

        val empty = File(dir, "empty").apply { mkdirs() }
        assertEquals(Cli.EXIT_FAILED, run("generate", "--in", empty.path, "--out", output.path, "--seconds", "5").code)

        val silent = File(dir, "silent").apply { mkdirs() }
        Wav.write(File(silent, "silence.wav"), ShortArray(3 * SAMPLE_RATE))
        val noSpeech = run("generate", "--in", silent.path, "--out", output.path, "--seconds", "5")
        assertEquals(Cli.EXIT_FAILED, noSpeech.code)
        assertTrue(noSpeech.err.contains("no usable speech"), noSpeech.err)

        val broken = File(dir, "broken").apply { mkdirs() }
        File(broken, "broken.wav").writeText("RIFF but not really")
        val bad = run("generate", "--in", broken.path, "--out", output.path, "--seconds", "5")
        assertEquals(Cli.EXIT_FAILED, bad.code)
        assertTrue(bad.err.contains("broken.wav"), bad.err)
        assertFalse(output.exists())
    }
}
