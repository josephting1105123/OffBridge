package com.joseph.clipboard.windows.storage

import java.io.InputStreamReader
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State

object TranslationManager {
    // Map format: translations[languageName][key] = value
    private val translations = mutableMapOf<String, MutableMap<String, String>>()

    private val _currentLanguage = mutableStateOf(SecurityVault.getLanguage())
    val currentLanguageState: State<String> get() = _currentLanguage

    fun setLanguage(lang: String) {
        _currentLanguage.value = lang
        SecurityVault.setLanguage(lang)
    }

    // Maps the display language names to the headers in the translation.json.txt file
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

    init {
        loadTranslations()
    }

    private fun loadTranslations() {
        try {
            val stream = this::class.java.classLoader.getResourceAsStream("translation.json.txt")
            if (stream == null) {
                System.err.println("TranslationManager: FATAL - translation.json.txt resource not found on classpath. Every getString() will return the raw key.")
                return
            }
            val content = stream.use { InputStreamReader(it, Charsets.UTF_8).readText() }

            if (content.isBlank()) return

            // Real JSON parsing per language block (see TranslationParser) — the old regex silently
            // dropped any value containing an escaped quote, which is why the two long legal texts
            // needed bespoke extraction. gson handles them like any other key now.
            translations.putAll(TranslationParser.parse(content, languageMap))

            // Map System Default to English
            translations["System Default"] = translations["English"] ?: mutableMapOf()

            // If nothing parsed, the resource was present but unreadable (e.g. packaging/encoding
            // mistake). Fail loudly instead of silently returning raw keys from every getString().
            if (translations["English"].isNullOrEmpty()) {
                System.err.println("TranslationManager: FATAL - translation.json.txt parsed to an empty map (no 'English' keys). Check the resource is packaged intact; every getString() will return the raw key.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getString(key: String): String {
        val langMap = translations[_currentLanguage.value] ?: translations["English"] ?: return key
        return langMap[key] ?: translations["English"]?.get(key) ?: key
    }

    fun getPrivacyPolicy(): String {
        val text = getString("privacy_policy_text")
        return text.replace("<br>", "\n").replace("<b>", "").replace("</b>", "")
    }
}
