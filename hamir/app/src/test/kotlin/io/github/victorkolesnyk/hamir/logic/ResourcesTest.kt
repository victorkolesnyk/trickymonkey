package io.github.victorkolesnyk.hamir.logic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks on source files that no device is needed for: the phrase list, the Ukrainian texts,
 * the manifest and the rule that the logic package stays free of Android classes.
 * Gradle runs tests with the module directory (app/) as working directory.
 */
class ResourcesTest {
    private val main = File("src/main")

    private fun read(path: String): String {
        val file = File(main, path)
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        return file.readText(Charsets.UTF_8)
    }

    private fun phrases(): List<String> =
        Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(read("res/values/phrases.xml"))
            .map { it.groupValues[1].trim() }.toList()

    private fun words(phrase: String): List<String> =
        phrase.split(Regex("\\s+")).filter { token -> token.any { it.isLetter() } }

    @Test
    fun thereAreExactlyOneHundredDistinctPhrases() {
        val list = phrases()
        assertEquals(EnrollmentRules.PHRASE_COUNT, list.size)
        assertEquals(list.size, list.map { it.lowercase() }.toSet().size, "duplicate phrases")
    }

    @Test
    fun everyPhraseHasSixToFourteenWords() {
        for (phrase in phrases()) {
            val count = words(phrase).size
            assertTrue(count in 6..14, "$count words: $phrase")
        }
    }

    @Test
    fun phrasesUseOnlyUkrainianLettersAndPlainPunctuation() {
        val allowed = "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя" + "’" + " ,.:?!—-"
        for (phrase in phrases()) {
            for (c in phrase.lowercase()) {
                assertTrue(c in allowed, "unexpected character '$c' (U+${"%04X".format(c.code)}) in: $phrase")
            }
            assertTrue(phrase.first().isUpperCase(), "must start with a capital: $phrase")
            assertTrue(phrase.last() in ".?!", "must end with punctuation: $phrase")
        }
    }

    @Test
    fun phrasesCoverTheWholeUkrainianAlphabet() {
        val text = phrases().joinToString(" ").lowercase()
        for (letter in "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя") {
            assertTrue(text.count { it == letter } >= 3, "letter '$letter' is too rare for a phonetically varied set")
        }
        assertTrue(text.contains("дж") && text.contains("дз"), "affricates дж and дз must occur")
    }

    @Test
    fun interfaceTextsContainNoRussianOnlyLettersAndNoRawApostrophes() {
        for (name in listOf("res/values/strings.xml", "res/values/phrases.xml")) {
            // Comments are English and may contain apostrophes.
            val text = read(name).replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            for (letter in "ыэъёЫЭЪЁ") {
                assertFalse(text.contains(letter), "$name contains '$letter', which Ukrainian does not use")
            }
            val body = text.substringAfter("<resources>")
            assertFalse(body.contains('\''), "$name: use ’ (U+2019); a raw apostrophe breaks aapt2 string parsing")
        }
    }

    @Test
    fun everyStringWithSeveralArgumentsUsesPositionalFormat() {
        val strings = Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(read("res/values/strings.xml"))
        var checked = 0
        for (match in strings) {
            val value = match.groupValues[2]
            assertFalse(
                Regex("%[sd]").containsMatchIn(value) || Regex("%[+.0-9]*f").containsMatchIn(value),
                "${match.groupValues[1]}: use positional arguments like %1\$s",
            )
            checked++
        }
        assertTrue(checked > 100, "strings.xml was not parsed")
    }

    @Test
    fun honestyStatementsArePresentOnMainAndGuideScreens() {
        val text = read("res/values/strings.xml")
        fun value(name: String) =
            Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]
        for (name in listOf("main_disclaimer", "guide_limits")) {
            val value = value(name).lowercase()
            assertTrue("експериментальн" in value, "$name: must say the defence is experimental")
            assertTrue("не гарантовано" in value, "$name: must say effectiveness is not guaranteed")
            assertTrue("фрагмент" in value && "нейромереж" in value, "$name: must explain the difference from the paper")
            assertTrue("впритул" in value, "$name: must say only microphones with a speaker on them are covered")
        }
        val limits = value("guide_limits").lowercase()
        assertTrue("кілька мікрофонів" in limits)
        assertTrue("української мови" in limits)
        assertTrue("2020" in limits)
        assertTrue("мереж" in value("guide_privacy").lowercase())
        val mainLayout = read("res/layout/activity_main.xml")
        assertTrue("@string/main_disclaimer" in mainLayout && "@string/manual_warning" in mainLayout)
        assertTrue("@string/guide_limits" in read("res/layout/activity_guide.xml"))
    }

    @Test
    fun manifestRequestsExactlyTheSpecifiedPermissionsAndNoInternet() {
        val manifest = read("AndroidManifest.xml").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val requested = Regex("<uses-permission\\s+android:name=\"android\\.permission\\.([A-Z_]+)\"")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "RECORD_AUDIO", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                "POST_NOTIFICATIONS", "WAKE_LOCK", "VIBRATE",
            ),
            requested,
        )
        assertFalse(manifest.contains("INTERNET"))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaPlayback\""))
    }

    @Test
    fun logicPackageDoesNotImportAndroid() {
        val dir = File(main, "kotlin/io/github/victorkolesnyk/hamir/logic")
        val files = dir.listFiles { file -> file.name.endsWith(".kt") }!!
        assertTrue(files.size >= 5)
        for (file in files) {
            for (line in file.readLines()) {
                assertFalse(line.trim().startsWith("import android"), "${file.name}: $line")
                assertFalse(line.contains("android."), "${file.name} mentions android.*: $line")
            }
        }
    }
}
