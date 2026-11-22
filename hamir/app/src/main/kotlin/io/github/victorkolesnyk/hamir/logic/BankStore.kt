package io.github.victorkolesnyk.hamir.logic

import io.github.victorkolesnyk.hamir.core.BankCodec
import io.github.victorkolesnyk.hamir.core.SAMPLE_RATE
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.crypto.SecretKey

/**
 * Access to the AES-256 data key that encrypts the takes. In the app it is an [EnvelopeKeyProvider];
 * tests use an in-memory key. May be slow; never call on the UI thread.
 */
interface BankKeyProvider {
    /**
     * The key created earlier, or null when there is none.
     * @throws BankKeyLostException when a key was stored but can no longer be opened
     */
    fun existingKey(): SecretKey?

    /** Creates and stores a new key, replacing any previous one. */
    fun createKey(): SecretKey

    /** Destroys the key. Every take encrypted with it becomes unreadable. */
    fun deleteKey()
}

/** Encryption of one take. The default delegates to the frozen core format. */
interface TakeCodec {
    fun encrypt(pcm: ShortArray, key: SecretKey): ByteArray
    fun decrypt(data: ByteArray, key: SecretKey): ShortArray

    object Core : TakeCodec {
        override fun encrypt(pcm: ShortArray, key: SecretKey): ByteArray = BankCodec.encrypt(pcm, key)
        override fun decrypt(data: ByteArray, key: SecretKey): ShortArray = BankCodec.decrypt(data, key)
    }
}

/**
 * Result of reading a whole profile: unreadable files are counted, not thrown.
 * [keyLost] means nothing could be read because the key itself is gone.
 */
class LoadedTakes(val takes: List<ShortArray>, val failed: Int, val keyLost: Boolean = false)

/**
 * Directory layout and bookkeeping of the encrypted voice bank:
 * `<root>/<A|B>/<NNN>.hmr`, NNN = phrase number 001..999. The wrapped data key lives next to the
 * profile directories in `<root>/key.bin` and is managed by the [BankKeyProvider].
 *
 * All methods do disk and crypto work and must run off the UI thread.
 * Writes are atomic (temp file + rename), so concurrent readers see either the old or the new take.
 */
class BankStore(
    private val root: File,
    private val keys: BankKeyProvider,
    private val codec: TakeCodec = TakeCodec.Core,
) {
    fun profileDir(profile: Profile): File = File(root, profile.name)

    fun takeFile(profile: Profile, number: Int): File {
        require(number in 1..MAX_NUMBER) { "phrase number out of range: $number" }
        return File(profileDir(profile), String.format(Locale.ROOT, "%03d%s", number, EXTENSION))
    }

    /** Phrase numbers that have a stored take, ascending. */
    fun takeNumbers(profile: Profile): List<Int> =
        takeFiles(profile).map { it.first }

    fun hasTake(profile: Profile, number: Int): Boolean = takeFile(profile, number).isFile

    fun stats(profile: Profile): ProfileStats {
        val files = takeFiles(profile)
        var samples = 0L
        for ((_, file) in files) samples += samplesInFile(file.length())
        return ProfileStats(files.size, samples.toDouble() / SAMPLE_RATE)
    }

    /** Encrypts and stores one take at [SAMPLE_RATE], replacing a previous take of the same phrase. */
    @Throws(IOException::class, java.security.GeneralSecurityException::class)
    fun saveTake(profile: Profile, number: Int, pcm: ShortArray) {
        val target = takeFile(profile, number)
        val dir = profileDir(profile)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("cannot create $dir")
        }
        val data = codec.encrypt(pcm, keyForWriting())
        val tmp = File(dir, target.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use { out ->
                out.write(data)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // Some file systems refuse to rename over an existing file.
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("cannot move $tmp to $target")
            }
        } finally {
            tmp.delete()
        }
    }

    /** @throws IOException when missing, GeneralSecurityException / IllegalArgumentException when unreadable. */
    @Throws(IOException::class, java.security.GeneralSecurityException::class)
    fun loadTake(profile: Profile, number: Int): ShortArray {
        val file = takeFile(profile, number)
        val data = file.readBytes()
        val key = keys.existingKey() ?: throw BankKeyLostException("no key for $file")
        return codec.decrypt(data, key)
    }

    /**
     * Decrypts every take of the profile. Files that cannot be read or authenticated are skipped and counted.
     * @param onProgress called after each file with (done, total)
     * @param isCancelled polled between files; when true the partial result is returned
     */
    fun loadAll(
        profile: Profile,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): LoadedTakes {
        val files = takeFiles(profile)
        if (files.isEmpty()) return LoadedTakes(emptyList(), 0)
        val takes = ArrayList<ShortArray>(files.size)
        var failed = 0
        val key = try {
            keys.existingKey()
        } catch (e: Exception) {
            null
        } ?: return LoadedTakes(emptyList(), files.size, keyLost = true)
        var done = 0
        for ((_, file) in files) {
            if (isCancelled()) break
            try {
                takes.add(codec.decrypt(file.readBytes(), key))
            } catch (e: Exception) {
                failed++
            }
            done++
            onProgress(done, files.size)
        }
        return LoadedTakes(takes, failed)
    }

    fun deleteTake(profile: Profile, number: Int): Boolean = takeFile(profile, number).delete()

    /** Removes every file of the profile, including leftovers that are not takes. */
    fun deleteProfile(profile: Profile): Boolean = profileDir(profile).deleteRecursively()

    /** Removes all profiles, the wrapped key file and the key itself, so remnants on flash cannot be decrypted. */
    fun deleteAll(): Boolean {
        var ok = true
        for (profile in Profile.values()) ok = deleteProfile(profile) && ok
        ok = root.deleteRecursively() && ok
        try {
            keys.deleteKey()
        } catch (e: Exception) {
            ok = false
        }
        return ok
    }

    /** True when any profile holds at least one take. */
    fun hasAnyTake(): Boolean = Profile.values().any { takeFiles(it).isNotEmpty() }

    /**
     * A new key may only be created for an empty bank. If takes exist but their key is missing or
     * cannot be opened, writing with a fresh key would silently mix readable and unreadable takes:
     * refuse instead. With no takes on disk nothing depends on the old key, so a key that cannot be
     * opened is simply replaced.
     */
    private fun keyForWriting(): SecretKey {
        val existing = try {
            keys.existingKey()
        } catch (e: BankKeyLostException) {
            if (hasAnyTake()) throw e
            null
        }
        if (existing != null) return existing
        if (hasAnyTake()) throw BankKeyLostException("takes exist but their key is missing")
        return keys.createKey()
    }

    private fun takeFiles(profile: Profile): List<Pair<Int, File>> {
        val list = profileDir(profile).listFiles() ?: return emptyList()
        val result = ArrayList<Pair<Int, File>>(list.size)
        for (file in list) {
            val number = parseNumber(file.name) ?: continue
            if (file.isFile) result.add(number to file)
        }
        result.sortBy { it.first }
        return result
    }

    companion object {
        const val EXTENSION = ".hmr"
        const val MAX_NUMBER = 999
        private const val TMP_SUFFIX = ".tmp"

        /** "HMR1" + 12-byte IV + 16-byte GCM tag, see core BankCodec. */
        const val FILE_OVERHEAD_BYTES = 4 + 12 + 16

        /** Phrase number for a file name like "007.hmr", or null for anything else. */
        fun parseNumber(fileName: String): Int? {
            if (fileName.length != 3 + EXTENSION.length || !fileName.endsWith(EXTENSION)) return null
            var n = 0
            for (i in 0 until 3) {
                val c = fileName[i]
                if (c < '0' || c > '9') return null
                n = n * 10 + (c - '0')
            }
            return if (n in 1..MAX_NUMBER) n else null
        }

        fun samplesInFile(fileLength: Long): Long =
            if (fileLength <= FILE_OVERHEAD_BYTES) 0 else (fileLength - FILE_OVERHEAD_BYTES) / 2
    }
}
