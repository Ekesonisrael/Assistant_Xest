package com.xestlab.assistant.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Target action inferred from explicit trigger keyword.
 */
enum class ThoughtActionType {
    EXECUTE_COMMAND,
    SAVE_NOTE,
    ADD_TASK,
    PROCESS_GENERAL
}

/**
 * Structured model delivered when the user speaks an explicit termination trigger.
 *
 * @param rawText Complete transcribed buffer including the trigger phrase.
 * @param cleanText Sanitized content with trigger tokens removed.
 * @param triggerKeyword The specific keyword that triggered execution (e.g. "Execute", "Save note").
 * @param actionType Inferred action classification based on the trigger.
 * @param timestampMillis Epoch timestamp when finalized.
 */
data class FinalizedThought(
    val rawText: String,
    val cleanText: String,
    val triggerKeyword: String,
    val actionType: ThoughtActionType,
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Semantic Thought Buffer & Explicit Termination Engine.
 *
 * Keeps the assistant silent while the user thinks out loud, rambles, or pauses.
 * Text is accumulated in a local memory buffer and is only finalized and dispatched
 * when an explicit termination keyword (such as "Execute", "Process", "Save note") is detected.
 */
class ThoughtBufferManager(
    private val defaultTriggerKeywords: Set<String> = DEFAULT_TRIGGERS
) {
    companion object {
        val DEFAULT_TRIGGERS = setOf(
            "execute",
            "process",
            "save note",
            "take note",
            "add task",
            "create task",
            "done thinking",
            "over to you",
            "go ahead"
        )

        private val PUNCTUATION_CLEAN_REGEX = Regex("[.,!?;:\\s]+$")
    }

    private val lock = ReentrantLock()
    private val bufferBuilder = StringBuilder()

    private val _bufferDraftFlow = MutableStateFlow("")
    val bufferDraftFlow: StateFlow<String> = _bufferDraftFlow.asStateFlow()

    private val _finalizedThoughtFlow = MutableSharedFlow<FinalizedThought>(extraBufferCapacity = 16)
    val finalizedThoughtFlow: SharedFlow<FinalizedThought> = _finalizedThoughtFlow.asSharedFlow()

    private var lastChunkReceivedTime: Long = 0L

    /**
     * Appends a newly transcribed text chunk from continuous speech recognition.
     *
     * @param chunk Freshly recognized words from the audio stream.
     * @return [FinalizedThought] if an explicit termination trigger was detected; null otherwise.
     */
    fun appendChunk(chunk: String): FinalizedThought? {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return null

        return lock.withLock {
            lastChunkReceivedTime = System.currentTimeMillis()

            if (bufferBuilder.isNotEmpty() && !bufferBuilder.endsWith(" ")) {
                bufferBuilder.append(" ")
            }
            bufferBuilder.append(trimmed)

            val currentBuffer = bufferBuilder.toString().trim()
            _bufferDraftFlow.value = currentBuffer

            // Inspect the accumulated buffer for explicit termination trigger tokens
            checkAndExtractTrigger(currentBuffer)?.also { finalized ->
                // Clear the buffer after trigger activation
                bufferBuilder.clear()
                _bufferDraftFlow.value = ""
                _finalizedThoughtFlow.tryEmit(finalized)
                return@withLock finalized
            }
            null
        }
    }

    /**
     * Replaces or updates the active in-progress draft (e.g. from partial speech recognizer updates).
     * If the partial stream already contains an explicit trigger keyword, finalizes it immediately.
     */
    fun updatePartialDraft(partialText: String): FinalizedThought? {
        val clean = partialText.trim()
        if (clean.isEmpty()) return null

        return lock.withLock {
            val candidate = if (bufferBuilder.isEmpty()) {
                clean
            } else {
                "${bufferBuilder.toString().trim()} $clean"
            }
            _bufferDraftFlow.value = candidate

            checkAndExtractTrigger(candidate)?.also { finalized ->
                bufferBuilder.clear()
                _bufferDraftFlow.value = ""
                _finalizedThoughtFlow.tryEmit(finalized)
                return@withLock finalized
            }
            null
        }
    }

    /**
     * Checks whether [fullText] contains one of the configured explicit termination keywords.
     * If found, extracts the keyword, removes it from the clean payload, and classifies the action.
     */
    fun checkAndExtractTrigger(fullText: String): FinalizedThought? {
        val lower = fullText.lowercase(Locale.ROOT)

        for (trigger in defaultTriggerKeywords) {
            val triggerRegex = Regex("(?:^|\\b)${Regex.escape(trigger)}(?:[.,!?;:\\s]|$)", RegexOption.IGNORE_CASE)
            val match = triggerRegex.find(fullText)

            if (match != null) {
                // Remove the matched trigger phrase from the user's thought body
                val start = match.range.first
                val end = match.range.last + 1
                val before = fullText.substring(0, start).trimEnd { it == ',' || it == ';' || it.isWhitespace() }.trim()
                val after = fullText.substring(end).trimStart { it == ',' || it == ';' || it.isWhitespace() }.trim()

                val cleanBody = listOf(before, after)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .trim()

                val actionType = when (trigger.lowercase(Locale.ROOT)) {
                    "save note", "take note" -> ThoughtActionType.SAVE_NOTE
                    "add task", "create task" -> ThoughtActionType.ADD_TASK
                    "execute" -> ThoughtActionType.EXECUTE_COMMAND
                    else -> ThoughtActionType.PROCESS_GENERAL
                }

                val matchedKeywordCapitalized = trigger.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }

                return FinalizedThought(
                    rawText = fullText.trim(),
                    cleanText = cleanBody.ifBlank { fullText.trim() },
                    triggerKeyword = matchedKeywordCapitalized,
                    actionType = actionType
                )
            }
        }
        return null
    }

    /**
     * Manually forces finalization of the current buffer (e.g. user taps an "Execute" button in the UI).
     */
    fun forceFinalize(triggerLabel: String = "Manual Tap"): FinalizedThought? {
        return lock.withLock {
            val current = bufferBuilder.toString().trim()
            if (current.isEmpty()) return@withLock null

            val finalized = FinalizedThought(
                rawText = current,
                cleanText = current,
                triggerKeyword = triggerLabel,
                actionType = ThoughtActionType.PROCESS_GENERAL
            )
            bufferBuilder.clear()
            _bufferDraftFlow.value = ""
            _finalizedThoughtFlow.tryEmit(finalized)
            finalized
        }
    }

    /**
     * Clears and discards the accumulated buffer without dispatching.
     */
    fun clear() {
        lock.withLock {
            bufferBuilder.clear()
            _bufferDraftFlow.value = ""
        }
    }

    fun getCurrentDraft(): String = lock.withLock { bufferBuilder.toString() }

    fun hasPendingThoughts(): Boolean = lock.withLock { bufferBuilder.isNotBlank() }
}
