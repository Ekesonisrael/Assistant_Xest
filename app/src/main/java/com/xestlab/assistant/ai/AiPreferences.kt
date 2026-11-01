package com.xestlab.assistant.ai

import android.content.Context
import com.xestlab.assistant.data.SecurePreferences

/**
 * Compatibility wrapper around SecurePreferences for AI configurations.
 */
class AiPreferences(context: Context) {
    private val secure = SecurePreferences(context)

    companion object {
        const val DEFAULT_MODEL = SecurePreferences.DEFAULT_MODEL
    }

    var isAiEnabled: Boolean
        get() = secure.isAiEnabled
        set(value) { secure.isAiEnabled = value }

    var apiKey: String
        get() = secure.geminiApiKey
        set(value) { secure.geminiApiKey = value }

    var modelName: String
        get() = secure.geminiModel
        set(value) { secure.geminiModel = value }

    fun hasValidKey(): Boolean = secure.hasGeminiKey()
}
