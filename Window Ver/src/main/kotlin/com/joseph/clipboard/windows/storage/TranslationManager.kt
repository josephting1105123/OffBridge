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
            if (stream == null) return
            val content = InputStreamReader(stream, Charsets.UTF_8).readText()
            
            if (content.isBlank()) return

            for ((uiName, headerName) in languageMap) {
                val startIndex = content.indexOf(headerName)
                if (startIndex != -1) {
                    val nextIndices = languageMap.values.map { content.indexOf(it) }.filter { it > startIndex }
                    val endIndex = if (nextIndices.isNotEmpty()) nextIndices.minOrNull()!! else content.length
                    val block = content.substring(startIndex, endIndex)
                    val map = mutableMapOf<String, String>()
                    
                    // Regex for all simple "key": "value" pairs that don't contain quotes in value
                    val simpleRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
                    simpleRegex.findAll(block).forEach { match ->
                        map[match.groupValues[1]] = match.groupValues[2].replace("\\n", "\n")
                    }
                    
                    // Specific extraction for multi-line / complex values
                    val privacyRegex = Regex("\"privacy_policy_text\"\\s*:\\s*\"(.*?)\"(?:\\s*,\\s*\"|\\s*\\})", RegexOption.DOT_MATCHES_ALL)
                    val privacyMatch = privacyRegex.find(block)
                    if (privacyMatch != null) {
                        map["privacy_policy_text"] = privacyMatch.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
                    }
                    
                    val agreementRegex = Regex("\"user_agreement_text\"\\s*:\\s*\"(.*?)\"(?:\\s*,\\s*\"|\\s*\\})", RegexOption.DOT_MATCHES_ALL)
                    val agreementMatch = agreementRegex.find(block)
                    if (agreementMatch != null) {
                        map["user_agreement_text"] = agreementMatch.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
                    }

                    translations[uiName] = map
                }
            }
            
            // Map System Default to English
            translations["System Default"] = translations["English"] ?: mutableMapOf()
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
