package com.xestlab.assistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App-private storage for sensitive credentials and network preferences.
 * Stored strictly in private SharedPreferences; never in Room, logs, or JSON backup exports.
 */
class SecurePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("xestlab_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        // AI / Gemini
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_MODEL_NAME = "gemini_model_name"
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        // Fish Audio TTS
        private const val KEY_FISH_AUDIO_ENABLED = "fish_audio_enabled"
        private const val KEY_FISH_AUDIO_KEY = "fish_audio_api_key"
        private const val KEY_FISH_AUDIO_VOICE_ID = "fish_audio_voice_id"

        // IMAP Read-Only Email
        private const val KEY_IMAP_ENABLED = "imap_enabled"
        private const val KEY_IMAP_HOST = "imap_host"
        private const val KEY_IMAP_PORT = "imap_port"
        private const val KEY_IMAP_USER = "imap_user"
        private const val KEY_IMAP_PASS = "imap_pass"
        private const val KEY_IMAP_WIFI_ONLY = "imap_wifi_only"

        // Wake-up Alarm
        private const val KEY_WAKEUP_TIME = "wakeup_time"
        private const val KEY_WAKEUP_ENABLED = "wakeup_enabled"
    }

    // AI
    var isAiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_ENABLED, value).apply()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_KEY, value.trim()).apply()

    var geminiModel: String
        get() = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL_NAME, value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()

    // Fish Audio TTS
    var isFishAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_FISH_AUDIO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FISH_AUDIO_ENABLED, value).apply()

    var fishAudioApiKey: String
        get() = prefs.getString(KEY_FISH_AUDIO_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FISH_AUDIO_KEY, value.trim()).apply()

    var fishAudioVoiceId: String
        get() = prefs.getString(KEY_FISH_AUDIO_VOICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FISH_AUDIO_VOICE_ID, value.trim()).apply()

    // IMAP
    var isImapEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMAP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IMAP_ENABLED, value).apply()

    var imapHost: String
        get() = prefs.getString(KEY_IMAP_HOST, "imap.gmail.com") ?: "imap.gmail.com"
        set(value) = prefs.edit().putString(KEY_IMAP_HOST, value.trim()).apply()

    var imapPort: Int
        get() = prefs.getInt(KEY_IMAP_PORT, 993)
        set(value) = prefs.edit().putInt(KEY_IMAP_PORT, value).apply()

    var imapUser: String
        get() = prefs.getString(KEY_IMAP_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IMAP_USER, value.trim()).apply()

    var imapPassword: String
        get() = prefs.getString(KEY_IMAP_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_IMAP_PASS, value.trim()).apply()

    var isImapWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_IMAP_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_IMAP_WIFI_ONLY, value).apply()

    // Wake-up
    var wakeUpTime: String
        get() = prefs.getString(KEY_WAKEUP_TIME, "06:30") ?: "06:30"
        set(value) = prefs.edit().putString(KEY_WAKEUP_TIME, value.trim()).apply()

    var isWakeUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKEUP_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKEUP_ENABLED, value).apply()
}
