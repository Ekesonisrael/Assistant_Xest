package com.xestlab.assistant.ai

import com.xestlab.assistant.data.model.Item
import java.text.SimpleDateFormat
import java.util.*

sealed class ParsedIntent {
    data class PersonReminder(val contactName: String, val time: String, val details: String) : ParsedIntent()
    data class Reminder(val title: String, val time: String, val isTomorrow: Boolean) : ParsedIntent()
    data class Note(val text: String) : ParsedIntent()
    data class RememberFact(val fact: String) : ParsedIntent()
    data class Task(val title: String, val durationMinutes: Int = 30) : ParsedIntent()
    data class Goal(val title: String, val amount: Double, val unit: String, val daysRemaining: Int) : ParsedIntent()
    data class Done(val targetName: String) : ParsedIntent()
    data class Question(val question: String) : ParsedIntent()
    data class FallbackNote(val text: String) : ParsedIntent()
}

object AssistantParser {

    private val TIME_REGEX = Regex("(\\d{1,2})(:(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedIntent {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. "remember that <fact>"
        if (lower.startsWith("remember that ") || lower.startsWith("remember: ")) {
            val fact = trimmed.substringAfter("remember").replaceFirst("^[\\s:]*(that\\s*)?".toRegex(), "").trim()
            if (fact.isNotBlank()) return ParsedIntent.RememberFact(fact)
        }

        // 2. "remind me to message <name> at <time>" or "message <name> at <time>"
        val personReminderRegex = Regex("(?:remind me to )?(?:message|call|text|whatsapp|dm)\\s+([a-zA-Z]+)\\s+(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)", RegexOption.IGNORE_CASE)
        val personMatch = personReminderRegex.find(lower)
        if (personMatch != null) {
            val name = personMatch.groupValues[1].replaceFirstChar { it.uppercase() }
            val rawTime = personMatch.groupValues[2]
            val standardTime = normalizeTime(rawTime)
            return ParsedIntent.PersonReminder(name, standardTime, trimmed)
        }

        // 3. "remind me to <action> at/tomorrow <time>"
        if (lower.startsWith("remind me to ") || lower.startsWith("remind me ")) {
            val body = trimmed.substringAfter("remind me").replaceFirst("^[\\s:]*(to\\s*)?".toRegex(), "").trim()
            val isTomorrow = lower.contains("tomorrow")
            val timeMatch = TIME_REGEX.find(body)
            val time = if (timeMatch != null) normalizeTime(timeMatch.value) else "12:00"
            val title = body.replace(Regex("\\b(at|by|for|tomorrow)\\b.*$", RegexOption.IGNORE_CASE), "").trim()
            return ParsedIntent.Reminder(title.ifEmpty { "Reminder" }, time, isTomorrow)
        }

        // 4. "done <item>" or "finished <item>" or "complete <item>"
        if (lower.startsWith("done ") || lower.startsWith("finished ") || lower.startsWith("complete ")) {
            val target = trimmed.substringAfter(" ").trim()
            return ParsedIntent.Done(target)
        }

        // 5. "note: <text>" or "note that <text>"
        if (lower.startsWith("note:") || lower.startsWith("note that ") || lower.startsWith("take a note: ")) {
            val noteText = trimmed.replaceFirst("(?i)^(note:\\s*|note that\\s*|take a note:\\s*)".toRegex(), "").trim()
            return ParsedIntent.Note(noteText)
        }

        // 6. "add task <text>" or "task: <text>"
        if (lower.startsWith("add task ") || lower.startsWith("task: ") || lower.startsWith("new task ")) {
            val taskTitle = trimmed.replaceFirst("(?i)^(add task\\s*|task:\\s*|new task\\s*)".toRegex(), "").trim()
            return ParsedIntent.Task(taskTitle)
        }

        // 7. Goal pattern: e.g. "320 pages by friday" or "read 320 pages in 5 days"
        val goalRegex = Regex("(\\d+(?:\\.\\d+)?)\\s+([a-zA-Z]+)\\s+(?:by|in)\\s+([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
        val goalMatch = goalRegex.find(lower)
        if (goalMatch != null) {
            val amount = goalMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val unit = goalMatch.groupValues[2]
            val deadlineWord = goalMatch.groupValues[3]
            val days = parseDaysLeft(deadlineWord)
            return ParsedIntent.Goal(trimmed, amount, unit, days)
        }

        // 8. Questions (starts with what, how, when, who, why, is, can)
        if (lower.startsWith("what ") || lower.startsWith("how ") || lower.startsWith("when ") ||
            lower.startsWith("who ") || lower.startsWith("why ") || lower.endsWith("?")
        ) {
            return ParsedIntent.Question(trimmed)
        }

        // 9. Fallback: Save as a note and explain so
        return ParsedIntent.FallbackNote(trimmed)
    }

    private fun normalizeTime(raw: String): String {
        val clean = raw.trim().lowercase(Locale.ROOT)
        var isPm = clean.contains("pm")
        val isAm = clean.contains("am")
        val digits = clean.replace("[^0-9:]".toRegex(), "")
        val parts = digits.split(":")
        var hour = parts.getOrNull(0)?.toIntOrNull() ?: 12
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0

        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0

        return String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    private fun parseDaysLeft(word: String): Int {
        val w = word.lowercase(Locale.ROOT)
        if (w.toIntOrNull() != null) return w.toInt().coerceAtLeast(1)
        val daysOfWeek = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
        val targetDay = daysOfWeek.indexOfFirst { it.startsWith(w) }
        if (targetDay != -1) {
            val cal = Calendar.getInstance()
            val currentDay = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday
            var diff = targetDay - currentDay
            if (diff <= 0) diff += 7
            return diff
        }
        return 7
    }
}
