package io.github.victorkolesnyk.hamir.core

import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.security.SecureRandom
import java.util.Locale
import java.util.Random
import kotlin.math.min
import kotlin.system.exitProcess

/**
 * Command-line front end for trying the generator on a desktop:
 *
 *     java -jar hamir-cli.jar generate --in <dir of wav> --out <wav> --seconds N
 *         [--voices 8] [--seed S] [--min-seg ms] [--max-seg ms] [--speed-jitter x]
 */
object Cli {
    const val EXIT_OK = 0
    const val EXIT_FAILED = 1
    const val EXIT_USAGE = 2

    private val USAGE = """
        Usage: java -jar hamir-cli.jar generate --in <dir> --out <file.wav> --seconds N [options]

          --in <dir>          directory with PCM16 WAV takes of one speaker (any sample rate,
                              mono or multi-channel). Repeat --in to add more speakers.
          --out <file.wav>    output file: mono PCM16 at $SAMPLE_RATE Hz
          --seconds N         length of the output
          --voices N          simultaneous voices (default 8)
          --seed S            make the output reproducible (default: SecureRandom)
          --min-seg ms        shortest fragment, at least ${BabbleGenerator.MIN_SEGMENT_MS} (default 400)
          --max-seg ms        longest fragment (default 2000)
          --speed-jitter x    playback speed spread, 0.06 = +-6% (default 0.06)

        Every speaker needs at least voices x max-seg x (1 + speed-jitter) of speech
        (17 s with the defaults); speakers with less are left out.
    """.trimIndent()

    private class UsageException(message: String) : Exception(message)

    /** Runs the CLI and returns the process exit code. Never calls exit itself. */
    fun run(args: Array<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        if (args.isEmpty() || args[0] == "--help" || args[0] == "-h" || args[0] == "help") {
            out.println(USAGE)
            return if (args.isEmpty()) EXIT_USAGE else EXIT_OK
        }
        return try {
            when (args[0]) {
                "generate" -> generate(args, out)
                else -> throw UsageException("unknown command '${args[0]}'")
            }
        } catch (e: UsageException) {
            err.println("Error: ${e.message}")
            err.println()
            err.println(USAGE)
            EXIT_USAGE
        } catch (e: IOException) {
            err.println("Error: ${e.message}")
            EXIT_FAILED
        } catch (e: IllegalArgumentException) {
            err.println("Error: ${e.message}")
            EXIT_FAILED
        }
    }

    private fun generate(args: Array<String>, out: PrintStream): Int {
        val inputs = ArrayList<File>()
        var output: File? = null
        var seconds: Double? = null
        var seed: Long? = null
        var config = BabbleConfig()
        var i = 1
        while (i < args.size) {
            val option = args[i]
            if (option == "--help" || option == "-h") {
                out.println(USAGE)
                return EXIT_OK
            }
            if (i + 1 >= args.size) throw UsageException("option '$option' needs a value")
            val value = args[i + 1]
            when (option) {
                "--in" -> inputs.add(File(value))
                "--out" -> output = File(value)
                "--seconds" -> seconds = value.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
                    ?: throw UsageException("--seconds must be a positive number, got '$value'")
                "--voices" -> config = config.copy(voices = positiveInt(option, value))
                "--seed" -> seed = value.toLongOrNull() ?: throw UsageException("--seed must be an integer, got '$value'")
                "--min-seg" -> config = config.copy(
                    minSegmentMs = positiveInt(option, value).takeIf { it >= BabbleGenerator.MIN_SEGMENT_MS }
                        ?: throw UsageException("--min-seg must be at least ${BabbleGenerator.MIN_SEGMENT_MS}, got '$value'"),
                )
                "--max-seg" -> config = config.copy(maxSegmentMs = positiveInt(option, value))
                "--speed-jitter" -> config = config.copy(
                    speedJitter = value.toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 0.5 }
                        ?: throw UsageException("--speed-jitter must be within 0..0.5, got '$value'"),
                )
                else -> throw UsageException("unknown option '$option'")
            }
            i += 2
        }
        if (inputs.isEmpty()) throw UsageException("--in is required")
        val outFile = output ?: throw UsageException("--out is required")
        val duration = seconds ?: throw UsageException("--seconds is required")
        if (config.maxSegmentMs < config.minSegmentMs) throw UsageException("--max-seg must not be below --min-seg")
        val totalSamples = duration * SAMPLE_RATE
        if (totalSamples > 1_000_000_000) throw UsageException("--seconds is too large for a WAV file")

        val banks = ArrayList<VoiceBank>()
        for (dir in inputs) {
            if (!dir.isDirectory) throw IOException("${dir.path} is not a directory")
            val files = dir.listFiles { f -> f.isFile && f.name.lowercase(Locale.ROOT).endsWith(".wav") }
                ?.sortedBy { it.name } ?: emptyList()
            if (files.isEmpty()) throw IOException("${dir.path} contains no .wav files")
            val takes = ArrayList<ShortArray>()
            for (file in files) {
                val wav = Wav.read(file)
                takes.add(Resampler.resample(wav.samples, wav.sampleRate, SAMPLE_RATE))
            }
            val bank = VoiceBank.fromUtterances(takes)
            out.println(
                String.format(
                    Locale.ROOT, "%s: %d files, %.1f s of speech, %d fragments",
                    dir.path, files.size, bank.speechSeconds, bank.segmentCount,
                ),
            )
            banks.add(bank)
        }
        if (banks.all { it.segmentCount == 0 }) throw IOException("no usable speech found in the input")
        val required = VoiceBank.requiredSpeechSeconds(config)
        if (banks.none { it.isUsable(config) }) {
            throw IOException(
                String.format(
                    Locale.ROOT,
                    "not enough speech: these settings need %.1f s per speaker (add recordings, or lower --voices or --max-seg)",
                    required,
                ),
            )
        }
        for (b in banks.indices) {
            if (!banks[b].isUsable(config)) {
                out.println(String.format(Locale.ROOT, "%s: left out, less than the required %.1f s of speech", inputs[b].path, required))
            }
        }

        val random: Random = if (seed != null) Random(seed) else SecureRandom()
        val generator = BabbleGenerator(banks, config, random)
        val samples = ShortArray(totalSamples.toInt())
        val chunk = SAMPLE_RATE / 50
        val started = System.nanoTime()
        var position = 0
        while (position < samples.size) {
            val count = min(chunk, samples.size - position)
            generator.read(samples, position, count)
            position += count
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        outFile.absoluteFile.parentFile?.mkdirs()
        Wav.write(outFile, samples)
        val written = samples.size.toDouble() / SAMPLE_RATE
        out.println(
            String.format(
                Locale.ROOT, "%s: %.2f s, %d voices, RMS %.2f dBFS, peak %.2f dBFS, generated at %.0fx real time",
                outFile.path, written, config.voices, Level.rmsDbfs(samples), Level.peakDbfs(samples),
                if (elapsed > 0.0) written / elapsed else Double.POSITIVE_INFINITY,
            ),
        )
        return EXIT_OK
    }

    private fun positiveInt(option: String, value: String): Int =
        value.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("$option must be a positive integer, got '$value'")
}

fun main(args: Array<String>) {
    val code = Cli.run(args)
    if (code != Cli.EXIT_OK) exitProcess(code)
}
