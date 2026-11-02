package com.xestlab.assistant.ai

import java.util.Calendar
import java.util.Locale

sealed class ParsedIntent {
    data object ReadPlan : ParsedIntent()

    data object CheckEmails : ParsedIntent()

    data class ExplicitNote(
        val title: String,
        val details: String,
        val sourceText: String
    ) : ParsedIntent()

    data class PersonReminder(
        val contactName: String,
        val actionTitle: String,
        val dueAt: Long,
        val sourceText: String
    ) : ParsedIntent()

    data class Reminder(
        val title: String,
        val dueAt: Long,
        val sourceText: String
    ) : ParsedIntent()

    data class RememberFact(
        val factText: String,
        val category: String = "PREFERENCE"
    ) : ParsedIntent()

    data class Task(
        val title: String,
        val dueAt: Long?,
        val durationMinutes: Int,
        val priority: String,
        val sourceText: String
    ) : ParsedIntent()

    data class Goal(
        val title: String,
        val targetAmount: Double,
        val unit: String,
        val dueAt: Long,
        val sourceText: String
    ) : ParsedIntent()

    data class Done(
        val targetQuery: String
    ) : ParsedIntent()

    data object WhoIsDueToday : ParsedIntent()

    data class AddLeadVoice(
        val name: String,
        val service: String,
        val phone: String,
        val observation: String,
        val sourceText: String
    ) : ParsedIntent()

    data class LeadStageVoice(
        val leadName: String,
        val targetStage: String,
        val quoteAmount: Long?,
        val sourceText: String
    ) : ParsedIntent()

    data class LogPaymentVoice(
        val clientName: String,
        val amount: Long,
        val paymentType: String,
        val note: String,
        val sourceText: String
    ) : ParsedIntent()

    /**
     * Any conversational message, question, or open voice prompt.
     * Never saved as a Note (Notes require explicit "Note: [text]").
     */
    data class ChatOrQuestion(
        val query: String
    ) : ParsedIntent()
}

object AssistantParser {

    fun parse(rawInput: String, nowMillis: Long = System.currentTimeMillis()): ParsedIntent {
        val cleaned = rawInput.trim()
        val lower = cleaned.lowercase(Locale.getDefault())

        if (cleaned.isEmpty()) {
            return ParsedIntent.ChatOrQuestion("")
        }

        // 1. STRICT Explicit Note Rule: ONLY save notes when user explicitly says "Note: [text]" (or "Note [text]")
        val explicitNoteRegex = Regex("^(?:save\\s+note|note)\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE)
        explicitNoteRegex.find(cleaned)?.let { match ->
            val content = match.groupValues[1].trim()
            if (content.isNotBlank()) {
                return ParsedIntent.ExplicitNote(
                    title = content.take(60),
                    details = content,
                    sourceText = cleaned
                )
            }
        }
        // SpokenSpeechRecognizer often omits the colon when user speaks "Note buy domain tomorrow"
        val spokenNoteRegex = Regex("^note\\s+(.+)$", RegexOption.IGNORE_CASE)
        if (!cleaned.endsWith("?")) {
            spokenNoteRegex.find(cleaned)?.let { match ->
                val content = match.groupValues[1].trim()
                if (content.isNotBlank() && !content.lowercase(Locale.getDefault()).startsWith("that ")) {
                    return ParsedIntent.ExplicitNote(
                        title = content.take(60),
                        details = content,
                        sourceText = cleaned
                    )
                }
            }
        }

        // 1b. Leads & Payments voice/text commands
        if (MoneyAndLeadParser.isWhoIsDueToday(cleaned)) {
            return ParsedIntent.WhoIsDueToday
        }
        MoneyAndLeadParser.tryParseAddLead(cleaned)?.let { return it }
        MoneyAndLeadParser.tryParsePaymentCommand(cleaned)?.let { return it }
        MoneyAndLeadParser.tryParseLeadStageCommand(cleaned)?.let { return it }

        // 2. Explicit raw inbox check commands only (specific questions like "do I have any meetings tomorrow?" route to Groq)
        if (
            lower == "check email" ||
            lower == "check my email" ||
            lower == "check my emails" ||
            lower == "read my email" ||
            lower == "read my emails" ||
            lower == "what did i get in my email today" ||
            lower == "what did i get in my email today?"
        ) {
            return ParsedIntent.CheckEmails
        }

        // 3. Read daily plan voice/text command
        if (
            lower == "read plan" ||
            lower == "read my plan" ||
            lower == "read daily plan" ||
            lower == "read my daily plan" ||
            lower == "read out my daily plan" ||
            lower == "brief me" ||
            lower == "morning briefing" ||
            lower.contains("read out my plan") ||
            lower.contains("read my schedule")
        ) {
            return ParsedIntent.ReadPlan
        }

        // 4. Remember fact ("remember that ...")
        val rememberRegex = Regex("^(?:please\\s+)?remember\\s+(?:that\\s+)?(.+)$", RegexOption.IGNORE_CASE)
        rememberRegex.find(cleaned)?.let { match ->
            val factBody = match.groupValues[1].trim()
            if (factBody.isNotBlank() && !factBody.lowercase(Locale.getDefault()).startsWith("to ")) {
                return ParsedIntent.RememberFact(factText = factBody)
            }
        }

        // 5. Mark item done ("done outreach", "completed study", "mark followups done")
        val donePrefixRegex = Regex("^(?:done|completed|finished|check\\s+off)\\s+(.+)$", RegexOption.IGNORE_CASE)
        donePrefixRegex.find(cleaned)?.let { match ->
            return ParsedIntent.Done(targetQuery = match.groupValues[1].trim())
        }
        val markDoneRegex = Regex("^mark\\s+(.+?)\\s+(?:as\\s+)?(?:done|complete|completed)$", RegexOption.IGNORE_CASE)
        markDoneRegex.find(cleaned)?.let { match ->
            return ParsedIntent.Done(targetQuery = match.groupValues[1].trim())
        }

        // 6. Person Reminder ("remind me to text Chidi at 4pm")
        val personRegex = Regex(
            "^(?:remind\\s+me\\s+to\\s+)(text|message|whatsapp|call|email|ping|reach\\s+out\\s+to)\\s+([A-Z][a-zA-Z0-9_'-]+|[a-zA-Z0-9_'-]+)(.*)$",
            RegexOption.IGNORE_CASE
        )
        personRegex.find(cleaned)?.let { match ->
            val verb = match.groupValues[1].trim()
            val personRaw = match.groupValues[2].trim()
            val remainder = match.groupValues[3].trim()
            val stopWords = setOf("my", "the", "a", "an", "all", "everyone", "back", "today", "tomorrow")
            if (personRaw.lowercase(Locale.getDefault()) !in stopWords) {
                val contactName = personRaw.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                }
                val dueAt = extractTimeMillis(cleaned, nowMillis) ?: (nowMillis + 60 * 60_000L)
                val cleanTopic = stripTimePhrases(remainder).trim()
                val actionTitle = if (cleanTopic.isNotBlank()) {
                    "${verb.replaceFirstChar { it.uppercase() }} $contactName $cleanTopic"
                } else {
                    "${verb.replaceFirstChar { it.uppercase() }} $contactName"
                }
                return ParsedIntent.PersonReminder(
                    contactName = contactName,
                    actionTitle = actionTitle,
                    dueAt = dueAt,
                    sourceText = cleaned
                )
            }
        }

        // 7. Standard Reminder ("remind me to ... at 5pm / in 20 min")
        if (lower.startsWith("remind me") || lower.startsWith("set a reminder") || lower.startsWith("alert me")) {
            val dueAt = extractTimeMillis(cleaned, nowMillis) ?: (nowMillis + 60 * 60_000L)
            val stripped = cleaned.replace(
                Regex(
                    "^(?:remind\\s+me\\s+(?:to\\s+)?|set\\s+a\\s+reminder\\s+(?:to\\s+|for\\s+)?|alert\\s+me\\s+(?:to\\s+)?)",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            val title = stripTimePhrases(stripped).trim().ifBlank { stripped.trim() }
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            return ParsedIntent.Reminder(
                title = title,
                dueAt = dueAt,
                sourceText = cleaned
            )
        }

        // 8. Numeric Goal with deadline ("read 300 pages by Friday")
        if (!cleaned.endsWith("?")) {
            val goalRegex = Regex(
                "^([a-zA-Z]+)\\s+(\\d+(?:\\.\\d+)?)\\s+([a-zA-Z]+)\\s+(?:by|in|within)\\s+(.+)$",
                RegexOption.IGNORE_CASE
            )
            goalRegex.find(cleaned)?.let { match ->
                val verb = match.groupValues[1]
                val amount = match.groupValues[2].toDoubleOrNull()
                val unit = match.groupValues[3]
                if (amount != null && amount > 0) {
                    val dueAt = extractDeadlineMillis(match.groupValues[4], nowMillis)
                    val title = "${verb.replaceFirstChar { it.uppercase() }} ${match.groupValues[2]} $unit"
                    return ParsedIntent.Goal(
                        title = title,
                        targetAmount = amount,
                        unit = unit,
                        dueAt = dueAt,
                        sourceText = cleaned
                    )
                }
            }
        }

        // 9. Explicit Task command ("add a task: ...", "add task ...", "create a task ...", "task: ...", "todo: ...", "schedule ...")
        val explicitTaskRegex = Regex(
            "^(?:(?:please|can\\s+you|could\\s+you|coach)\\s+)?(?:(?:add|create|make|save|put)\\s+(?:a\\s+|new\\s+)?task(?:\\s+to)?|new\\s+task|task\\s*[:\\-]|to-?do\\s*[:\\-]|schedule)(?:\\s*[:\\-]\\s*|\\s+)(.+)$",
            RegexOption.IGNORE_CASE
        )
        explicitTaskRegex.find(cleaned)?.let { match ->
            val rawBody = match.groupValues[1].trim().trimEnd('?')
            val isQuestionAboutSchedule = lower.startsWith("schedule") && cleaned.endsWith("?")
            if (rawBody.isNotBlank() && !isQuestionAboutSchedule) {
                val extractedDue = extractTimeMillis(rawBody, nowMillis)
                val durationMins = extractDurationMinutes(rawBody)
                val priority = when {
                    lower.contains("urgent") || lower.contains("asap") || lower.contains("important") || lower.contains("high priority") -> "HIGH"
                    lower.contains("low priority") -> "LOW"
                    else -> "NORMAL"
                }
                val cleanedTitle = stripTimePhrases(rawBody)
                    .trim()
                    .removePrefix(":")
                    .removePrefix("-")
                    .trim()
                    .ifBlank { rawBody }
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

                return ParsedIntent.Task(
                    title = cleanedTitle,
                    dueAt = extractedDue,
                    durationMinutes = durationMins,
                    priority = priority,
                    sourceText = cleaned
                )
            }
        }

        // 10. All other questions & chat go to ChatOrQuestion (NEVER saved as Notes)
        return ParsedIntent.ChatOrQuestion(query = cleaned)
    }

    fun isWhatsOnMyListQuery(rawInput: String): Boolean {
        val lower = rawInput.trim().lowercase(Locale.getDefault()).trimEnd('?', '.', '!')
        return lower == "what's on my list" ||
            lower == "whats on my list" ||
            lower == "what is on my list" ||
            lower == "what's on my plan" ||
            lower == "whats on my plan" ||
            lower == "what's pending" ||
            lower == "whats pending" ||
            lower == "what is pending" ||
            lower == "what tasks do i have" ||
            lower == "what tasks are left" ||
            lower == "what do i have left" ||
            lower == "what's left today" ||
            lower == "whats left today" ||
            lower == "show my list" ||
            lower == "show my tasks" ||
            lower == "list my tasks"
    }

    fun isStopQuestionsPhrase(rawInput: String): Boolean {
        val lower = rawInput.trim().lowercase(Locale.getDefault()).trimEnd('.', '!', '?')
        val stopPhrases = listOf(
            "that's all",
            "thats all",
            "no more",
            "let me rest",
            "i want to rest",
            "let me sleep",
            "nothing else",
            "stop asking",
            "no more questions",
            "that will be all",
            "that is all"
        )
        return stopPhrases.any { lower == it || lower.startsWith("$it,") || lower.startsWith("$it ") || lower.endsWith(" $it") }
    }

    fun extractTimeMillis(input: String, nowMillis: Long = System.currentTimeMillis()): Long? {
        val lower = input.lowercase(Locale.getDefault())

        val relMinRegex = Regex("\\bin\\s+(\\d+)\\s*(?:min|mins|minute|minutes)\\b")
        relMinRegex.find(lower)?.let { m ->
            val mins = m.groupValues[1].toLongOrNull() ?: return null
            return nowMillis + mins * 60_000L
        }

        val relHourRegex = Regex("\\bin\\s+(\\d+)\\s*(?:hr|hrs|hour|hours)\\b")
        relHourRegex.find(lower)?.let { m ->
            val hrs = m.groupValues[1].toLongOrNull() ?: return null
            return nowMillis + hrs * 3_600_000L
        }

        val isTomorrow = lower.contains("tomorrow")
        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        if (isTomorrow) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val clockRegex = Regex("\\b(?:at|by|for)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b")
        clockRegex.find(lower)?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return null
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3]

            if (ampm == "pm" && hour in 1..11) hour += 12
            if (ampm == "am" && hour == 12) hour = 0
            if (ampm.isEmpty() && hour in 1..6) {
                hour += 12
            }

            cal.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            cal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            if (!isTomorrow && cal.timeInMillis <= nowMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        if (isTomorrow) {
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        if (lower.contains("tonight") || lower.contains("this evening")) {
            cal.set(Calendar.HOUR_OF_DAY, 19)
            cal.set(Calendar.MINUTE, 30)
            cal.set(Calendar.SECOND, 0)
            if (cal.timeInMillis <= nowMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        return null
    }

    private fun extractDeadlineMillis(deadlinePhrase: String, nowMillis: Long): Long {
        val lower = deadlinePhrase.lowercase(Locale.getDefault()).trim()
        Regex("(\\d+)\\s*days?").find(lower)?.let { m ->
            val days = m.groupValues[1].toLongOrNull() ?: 7L
            return nowMillis + days * 86_400_000L
        }
        Regex("(\\d+)\\s*weeks?").find(lower)?.let { m ->
            val weeks = m.groupValues[1].toLongOrNull() ?: 1L
            return nowMillis + weeks * 7L * 86_400_000L
        }

        val dayMap = mapOf(
            "sunday" to Calendar.SUNDAY,
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY
        )
        for ((name, calDay) in dayMap) {
            if (lower.contains(name)) {
                val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
                var daysAhead = (calDay - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (daysAhead == 0) daysAhead = 7
                cal.add(Calendar.DAY_OF_YEAR, daysAhead)
                cal.set(Calendar.HOUR_OF_DAY, 18)
                cal.set(Calendar.MINUTE, 0)
                return cal.timeInMillis
            }
        }

        return nowMillis + 7L * 86_400_000L
    }

    private fun extractDurationMinutes(input: String): Int {
        val lower = input.lowercase(Locale.getDefault())
        Regex("for\\s+(\\d+)\\s*(?:min|mins|minute|minutes)").find(lower)?.let {
            return it.groupValues[1].toIntOrNull() ?: 30
        }
        Regex("for\\s+(\\d+)\\s*(?:hr|hrs|hour|hours)").find(lower)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 1) * 60
        }
        return 30
    }

    private fun stripTimePhrases(input: String): String {
        return input
            .replace(Regex("\\bin\\s+\\d+\\s*(?:min|mins|minute|minutes|hr|hrs|hour|hours)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(?:at|by)\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\btonight\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }
}
