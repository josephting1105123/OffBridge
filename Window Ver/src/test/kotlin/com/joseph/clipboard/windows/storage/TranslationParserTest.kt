package com.joseph.clipboard.windows.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

/**
 * A6/XP: the JSON translation parser must load every locale with no dropped keys — the old regex
 * silently discarded any value containing an escaped quote. Runs against the real packaged resource.
 */
class TranslationParserTest {

    // Mirror of TranslationManager.languageMap (kept local so the test doesn't trigger the
    // SecurityVault-coupled TranslationManager object init).
    private val languageMap = mapOf(
        "English" to "English (en)",
        "简体中文" to "Simplified Chinese (zh-CN)",
        "繁體中文" to "Traditional Chinese (zh-TW)",
        "Español" to "Spanish (es)",
        "Français" to "French (fr)",
        "日本語" to "Japanese (ja)",
        "Deutsch" to "German (de)",
        "Português" to "Portuguese (pt)",
        "Bahasa Indonesia" to "Indonesian (id)",
        "Русский" to "Russian (ru)"
    )

    private fun loadContent(): String {
        val stream = this::class.java.classLoader.getResourceAsStream("translation.json.txt")
            ?: error("translation.json.txt not on the test classpath")
        return stream.use { InputStreamReader(it, Charsets.UTF_8).readText() }
    }

    @Test
    fun everyLanguageLoadsWithSubstantialKeySet() {
        val parsed = TranslationParser.parse(loadContent(), languageMap)
        for (ui in languageMap.keys) {
            val map = parsed[ui]
            assertTrue("Missing/empty translations for $ui", map != null && map.isNotEmpty())
        }
        // English is the source locale; it should carry the full key set.
        assertTrue("English key set looks truncated", (parsed["English"]?.size ?: 0) > 100)
    }

    @Test
    fun valuesContainingEscapedQuotesAreNotDropped() {
        val english = TranslationParser.parse(loadContent(), languageMap)["English"]!!
        // user_agreement_text embeds \"as is\" — the old regex dropped any value with a quote, so this
        // key had to be special-cased. gson now unescapes it to a real quote.
        val agreement = english["user_agreement_text"]
        assertTrue("user_agreement_text missing (quote-containing value dropped)", agreement != null)
        assertTrue("escaped quote not unescaped", agreement!!.contains("\"as is\""))
        // A plain key still loads unchanged.
        assertTrue(english["app_name"]?.isNotBlank() == true)
    }

    @Test
    fun blankContentYieldsEmptyResult() {
        assertTrue(TranslationParser.parse("", languageMap).isEmpty())
        assertTrue(TranslationParser.parse("   ", languageMap).isEmpty())
    }
}
