package com.example.navigation

/**
 * Selects how RahYab speaks navigation guidance when the app language is Persian.
 *
 * [BUILTIN_OFFLINE] is the default so a fresh install in airplane mode works immediately
 * without requiring Android system Persian TTS data, downloads, or API keys.
 */
enum class PersianVoiceMode(val code: String) {
    BUILTIN_OFFLINE("builtin_offline"),
    SYSTEM_TTS("system_tts"),
    ENGLISH_VOICE("english_voice");

    companion object {
        fun fromCode(code: String?, legacyUseEnglishVoiceInPersian: Boolean = false): PersianVoiceMode {
            if (!code.isNullOrBlank()) {
                entries.firstOrNull { it.code.equals(code, ignoreCase = true) }?.let { return it }
            }
            return if (legacyUseEnglishVoiceInPersian) ENGLISH_VOICE else BUILTIN_OFFLINE
        }
    }
}
