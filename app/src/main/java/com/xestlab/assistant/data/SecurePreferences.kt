@file:Suppress("DEPRECATION")

package com.xestlab.assistant.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class LlmProvider {
    GEMINI,
    GROQ;

    companion object {
        fun fromString(raw: String?): LlmProvider {
            return if (raw.equals("GEMINI", ignoreCase = true)) GEMINI else GROQ
        }
    }
}

/**
 * Thread-safe singleton AES-256 EncryptedSharedPreferences vault for all sensitive credentials:
 * - Groq API Key (Primary Engine)
 * - Gemini API Key (Disabled for now)
 * - Picovoice Access Key
 * - ElevenLabs API Key
 * - IMAP Password
 *
 * Uses synchronous .commit() writes so keys are persisted immediately and never stored in plain text.
 */
@SuppressLint("ApplySharedPref")
class SecurePreferences(context: Context) {

    companion object {
        private const val VAULT_FILE_NAME = "xestlab_encrypted_credentials_v2"
        const val DEFAULT_ELEVENLABS_VOICE_ID = "EXAVITQu4vr4xnSDxMaL"
        const val DEFAULT_WAKE_WORD_THRESHOLD = 0.35f
        const val MONTHLY_ELEVENLABS_CHAR_LIMIT = 10_000
        const val MONTHLY_ELEVENLABS_WARN_THRESHOLD = 8_000
        const val CLOUD_VOICE_PAUSE_DURATION_MS = 24L * 60L * 60L * 1000L
        private val LOCK = Any()

        @Volatile
        private var sharedEncryptedPrefs: SharedPreferences? = null

        private fun getOrCreateEncryptedPrefs(appContext: Context): SharedPreferences {
            sharedEncryptedPrefs?.let { return it }
            synchronized(LOCK) {
                sharedEncryptedPrefs?.let { return it }
                val created = try {
                    buildEncryptedPrefs(appContext)
                } catch (_: Exception) {
                    appContext.deleteSharedPreferences(VAULT_FILE_NAME)
                    buildEncryptedPrefs(appContext)
                }
                sharedEncryptedPrefs = created
                return created
            }
        }

        private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                appContext,
                VAULT_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        fun sanitizeApiKey(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .removeSurrounding("<", ">")
                .removePrefix("xi-api-key:")
                .removePrefix("xi-api-key")
                .removePrefix("Bearer ")
                .removePrefix("bearer ")
                .trim()
                .replace(Regex("\\s+"), "")
        }

        fun dayPeriodForHour(hourOfDay: Int): String {
            return when (hourOfDay) {
                in 0..11 -> "MORNING"
                in 12..16 -> "AFTERNOON"
                else -> "EVENING"
            }
        }

        fun formatPeriodGreeting(period: String, userName: String): String {
            val cleanName = userName.trim().ifBlank { "Israel" }
            val prefix = when (period) {
                "MORNING" -> "Good morning"
                "AFTERNOON" -> "Good afternoon"
                else -> "Good evening"
            }
            return "$prefix, $cleanName."
        }

        /**
         * Pure deterministic period-greeting evaluator:
         * Returns Triple(greetingToPrependOrEmpty, updatedLastDate, updatedLastPeriod).
         */
        fun evaluatePeriodGreeting(
            lastDate: String,
            lastPeriod: String,
            currentDate: String,
            currentHourOfDay: Int,
            userName: String
        ): Triple<String, String, String> {
            val currentPeriod = dayPeriodForHour(currentHourOfDay)
            return if (lastDate == currentDate && lastPeriod == currentPeriod) {
                Triple("", lastDate, lastPeriod)
            } else {
                Triple(formatPeriodGreeting(currentPeriod, userName), currentDate, currentPeriod)
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = getOrCreateEncryptedPrefs(appContext)

    var isAiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled_v3", true)
        set(value) {
            prefs.edit().putBoolean("ai_enabled_v3", value).commit()
        }

    var llmProvider: LlmProvider
        get() = LlmProvider.fromString(prefs.getString("llm_provider_v3", LlmProvider.GROQ.name))
        set(value) {
            prefs.edit().putString("llm_provider_v3", value.name).commit()
        }

    var geminiApiKey: String
        get() = sanitizeApiKey(prefs.getString("gemini_api_key", null))
        set(value) {
            prefs.edit().putString("gemini_api_key", sanitizeApiKey(value)).commit()
        }

    var groqApiKey: String
        get() = sanitizeApiKey(prefs.getString("groq_api_key", null))
        set(value) {
            prefs.edit().putString("groq_api_key", sanitizeApiKey(value)).commit()
        }

    fun getKeyForProvider(provider: LlmProvider): String? {
        val raw = when (provider) {
            LlmProvider.GROQ -> groqApiKey
            LlmProvider.GEMINI -> geminiApiKey
        }
        return raw.takeIf { it.isNotBlank() }
    }

    fun maskedKeyPreview(provider: LlmProvider): String {
        val key = getKeyForProvider(provider) ?: return "None saved"
        return if (key.length <= 8) {
            "Saved (${key.length} chars)"
        } else {
            "${key.take(4)}...${key.takeLast(4)} (${key.length} chars)"
        }
    }

    fun hasGeminiKey(): Boolean = geminiApiKey.isNotBlank()

    fun hasGroqKey(): Boolean = groqApiKey.isNotBlank()

    fun hasActiveLlmKey(): Boolean = groqApiKey.isNotBlank()

    var elevenLabsApiKey: String
        get() = sanitizeApiKey(prefs.getString("elevenlabs_api_key", null))
        set(value) {
            val cleaned = sanitizeApiKey(value)
            prefs.edit()
                .putString("elevenlabs_api_key", cleaned)
                .putLong("elevenlabs_paused_until_ms", 0L)
                .putString("elevenlabs_pause_message", "")
                .commit()
        }

    fun hasElevenLabsKey(): Boolean = elevenLabsApiKey.isNotBlank()

    fun maskedElevenLabsKeyPreview(): String {
        val key = elevenLabsApiKey
        if (key.isBlank()) return "None saved"
        return if (key.length <= 8) {
            "Saved (${key.length} chars)"
        } else {
            "${key.take(4)}...${key.takeLast(4)} (${key.length} chars)"
        }
    }

    var elevenLabsVoiceId: String
        get() = prefs.getString("elevenlabs_voice_id_v2", DEFAULT_ELEVENLABS_VOICE_ID)
            ?.trim()
            ?.ifBlank { DEFAULT_ELEVENLABS_VOICE_ID }
            ?: DEFAULT_ELEVENLABS_VOICE_ID
        set(value) {
            prefs.edit()
                .putString("elevenlabs_voice_id_v2", value.trim().ifBlank { DEFAULT_ELEVENLABS_VOICE_ID })
                .commit()
        }

    var elevenLabsPausedUntilMillis: Long
        get() = prefs.getLong("elevenlabs_paused_until_ms", 0L)
        set(value) {
            prefs.edit().putLong("elevenlabs_paused_until_ms", value).commit()
        }

    var elevenLabsPauseMessage: String
        get() = prefs.getString("elevenlabs_pause_message", "") ?: ""
        set(value) {
            prefs.edit().putString("elevenlabs_pause_message", value).commit()
        }

    fun isElevenLabsCurrentlyPaused(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val until = elevenLabsPausedUntilMillis
        if (until <= 0L) return false
        if (nowMillis >= until) {
            prefs.edit()
                .putLong("elevenlabs_paused_until_ms", 0L)
                .putString("elevenlabs_pause_message", "")
                .commit()
            return false
        }
        return true
    }

    fun pauseElevenLabsFor24Hours(clearMessage: String, nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong("elevenlabs_paused_until_ms", nowMillis + CLOUD_VOICE_PAUSE_DURATION_MS)
            .putString("elevenlabs_pause_message", clearMessage)
            .commit()
    }

    fun clearElevenLabsPause() {
        prefs.edit()
            .putLong("elevenlabs_paused_until_ms", 0L)
            .putString("elevenlabs_pause_message", "")
            .commit()
    }

    private fun currentYearMonthKey(nowMillis: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM", Locale.US).format(Date(nowMillis))
    }

    val elevenLabsCharsUsedThisMonth: Int
        get() {
            val currentMonth = currentYearMonthKey()
            val storedMonth = prefs.getString("elevenlabs_usage_month", "") ?: ""
            return if (storedMonth != currentMonth) {
                prefs.edit()
                    .putString("elevenlabs_usage_month", currentMonth)
                    .putInt("elevenlabs_chars_used", 0)
                    .commit()
                0
            } else {
                prefs.getInt("elevenlabs_chars_used", 0).coerceAtLeast(0)
            }
        }

    fun addElevenLabsCharsUsed(charCount: Int): Int {
        if (charCount <= 0) return elevenLabsCharsUsedThisMonth
        synchronized(LOCK) {
            val currentMonth = currentYearMonthKey()
            val storedMonth = prefs.getString("elevenlabs_usage_month", "") ?: ""
            val base = if (storedMonth == currentMonth) {
                prefs.getInt("elevenlabs_chars_used", 0).coerceAtLeast(0)
            } else {
                0
            }
            val updated = base + charCount
            prefs.edit()
                .putString("elevenlabs_usage_month", currentMonth)
                .putInt("elevenlabs_chars_used", updated)
                .commit()
            return updated
        }
    }

    var androidTtsVoiceName: String
        get() = prefs.getString("android_tts_voice_name", "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString("android_tts_voice_name", value.trim()).commit()
        }

    var isImapEnabled: Boolean
        get() = prefs.getBoolean("imap_enabled", false)
        set(value) {
            prefs.edit().putBoolean("imap_enabled", value).commit()
        }

    var imapHost: String
        get() = prefs.getString("imap_host", "imap.gmail.com") ?: "imap.gmail.com"
        set(value) {
            prefs.edit().putString("imap_host", value.trim().ifBlank { "imap.gmail.com" }).commit()
        }

    var imapPort: Int
        get() = prefs.getInt("imap_port", 993)
        set(value) {
            prefs.edit().putInt("imap_port", value).commit()
        }

    var imapUser: String
        get() = prefs.getString("imap_user", "") ?: ""
        set(value) {
            prefs.edit().putString("imap_user", value.trim()).commit()
        }

    var imapPassword: String
        get() = prefs.getString("imap_password", "") ?: ""
        set(value) {
            prefs.edit().putString("imap_password", value).commit()
        }

    var isImapWifiOnly: Boolean
        get() = prefs.getBoolean("imap_wifi_only", false)
        set(value) {
            prefs.edit().putBoolean("imap_wifi_only", value).commit()
        }

    var lastEmailCheckTimestamp: Long
        get() = prefs.getLong("last_email_check_ts", 0L)
        set(value) {
            prefs.edit().putLong("last_email_check_ts", value).commit()
        }

    var lastEmailCheckResult: String
        get() = prefs.getString("last_email_check_result", "Never checked") ?: "Never checked"
        set(value) {
            prefs.edit().putString("last_email_check_result", value).commit()
        }

    var lastEmailCheckIsError: Boolean
        get() = prefs.getBoolean("last_email_check_is_error", false)
        set(value) {
            prefs.edit().putBoolean("last_email_check_is_error", value).commit()
        }

    var lastAlarmFiredTimestamp: Long
        get() = prefs.getLong("last_alarm_fired_ts", 0L)
        set(value) {
            prefs.edit().putLong("last_alarm_fired_ts", value).commit()
        }

    var lastAlarmFiredTitle: String
        get() = prefs.getString("last_alarm_fired_title", "") ?: ""
        set(value) {
            prefs.edit().putString("last_alarm_fired_title", value).commit()
        }

    var nextAlarmTimestamp: Long
        get() = prefs.getLong("next_alarm_scheduled_ts", 0L)
        set(value) {
            prefs.edit().putLong("next_alarm_scheduled_ts", value).commit()
        }

    var nextAlarmTitle: String
        get() = prefs.getString("next_alarm_scheduled_title", "") ?: ""
        set(value) {
            prefs.edit().putString("next_alarm_scheduled_title", value).commit()
        }

    // --- Custom Wake Word & Picovoice Porcupine Encrypted Credentials ---

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", false)
        set(value) {
            prefs.edit().putBoolean("wake_word_enabled", value).commit()
        }

    var wakeWordThreshold: Float
        get() = prefs.getFloat("wake_word_threshold_v2", DEFAULT_WAKE_WORD_THRESHOLD)
            .coerceIn(0.05f, 0.95f)
        set(value) {
            prefs.edit()
                .putFloat("wake_word_threshold_v2", value.coerceIn(0.05f, 0.95f))
                .commit()
        }

    var lastWakeWordDetectionMillis: Long
        get() = prefs.getLong("last_wake_word_detection_ms", 0L)
        set(value) {
            prefs.edit().putLong("last_wake_word_detection_ms", value).commit()
        }

    var wakeWordName: String
        get() = prefs.getString("custom_wake_word_name", "")?.trim().orEmpty()
        set(value) {
            prefs.edit().putString("custom_wake_word_name", value.trim()).commit()
        }

    var picovoiceAccessKey: String
        get() = sanitizeApiKey(prefs.getString("picovoice_access_key", null))
        set(value) {
            prefs.edit().putString("picovoice_access_key", sanitizeApiKey(value)).commit()
        }

    fun hasPicovoiceKey(): Boolean = picovoiceAccessKey.isNotBlank()

    fun maskedPicovoiceKeyPreview(): String {
        val key = picovoiceAccessKey
        if (key.isBlank()) return "None saved"
        return if (key.length <= 8) {
            "Saved (${key.length} chars)"
        } else {
            "${key.take(4)}...${key.takeLast(4)} (${key.length} chars)"
        }
    }

    // --- Deterministic Time-of-Day Greeting & Daily Briefing Tracking ---

    private fun currentCalendarDateKey(nowMillis: Long = System.currentTimeMillis()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
    }

    var lastGreetingDate: String
        get() = prefs.getString("last_greeting_date_v1", "") ?: ""
        set(value) {
            prefs.edit().putString("last_greeting_date_v1", value).commit()
        }

    var lastGreetingPeriod: String
        get() = prefs.getString("last_greeting_period_v1", "") ?: ""
        set(value) {
            prefs.edit().putString("last_greeting_period_v1", value).commit()
        }

    var lastBriefingDate: String
        get() = prefs.getString("last_briefing_date_v1", "") ?: ""
        set(value) {
            prefs.edit().putString("last_briefing_date_v1", value).commit()
        }

    /**
     * Greets at most once in the morning (00-11), once in the afternoon (12-16),
     * and once in the evening (17-23) per calendar date. Returns "" on all subsequent calls
     * within the same period.
     */
    fun consumePeriodGreetingIfNeeded(
        userName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        synchronized(LOCK) {
            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val today = currentCalendarDateKey(nowMillis)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val (greeting, newDate, newPeriod) = evaluatePeriodGreeting(
                lastDate = lastGreetingDate,
                lastPeriod = lastGreetingPeriod,
                currentDate = today,
                currentHourOfDay = hour,
                userName = userName
            )
            if (greeting.isNotEmpty()) {
                prefs.edit()
                    .putString("last_greeting_date_v1", newDate)
                    .putString("last_greeting_period_v1", newPeriod)
                    .commit()
            }
            return greeting
        }
    }

    fun shouldShowAutoBriefingToday(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val today = currentCalendarDateKey(nowMillis)
        return lastBriefingDate != today
    }

    fun markBriefingShownToday(nowMillis: Long = System.currentTimeMillis()) {
        val today = currentCalendarDateKey(nowMillis)
        prefs.edit().putString("last_briefing_date_v1", today).commit()
    }
}

