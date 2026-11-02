package com.xestlab.assistant.service

import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.EmailSenderRuleEntity
import com.xestlab.assistant.data.model.UnreadEmailEntity
import com.xestlab.assistant.data.model.learnedKeywordsList
import com.xestlab.assistant.data.model.tagsList
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class EmailClassificationDecision(
    val priority: String, // "HIGH", "NORMAL", "LOW"
    val reason: String,
    val isActionable: Boolean,
    val category: String, // "INVOICE", "MEETING", "TASK", "GENERAL"
    val extractedTaskTitle: String?
)

object EmailPriorityEngine {

    val BUILT_IN_TAGS = listOf("Meeting", "Client", "Money", "Personal", "Low")

    private val EMAIL_EXTRACT_REGEX = Regex("([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})")

    private val STOP_WORDS = setOf(
        "from", "with", "this", "that", "your", "have", "will", "about", "http", "https",
        "hello", "dear", "regards", "thanks", "thank", "email", "message", "today", "tomorrow",
        "please", "there", "their", "would", "could", "should", "sent", "just", "more", "some",
        "into", "been", "were", "they", "them", "what", "when", "where", "which", "here", "fw",
        "fwd", "re", "subject", "unread", "inbox", "team", "info", "support", "contact"
    )

    fun extractEmailAddress(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        EMAIL_EXTRACT_REGEX.find(trimmed)?.let {
            return it.groupValues[1].lowercase(Locale.US)
        }
        return ""
    }

    fun normalizeSenderKey(senderAddress: String, senderDisplay: String): String {
        val extracted = extractEmailAddress(senderAddress).ifBlank {
            extractEmailAddress(senderDisplay)
        }
        if (extracted.isNotBlank()) return extracted
        return senderDisplay.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
    }

    fun isNoReplyAddress(senderAddress: String, senderDisplay: String): Boolean {
        val combined = "${senderAddress.lowercase(Locale.US)} ${senderDisplay.lowercase(Locale.US)}"
        return listOf(
            "no-reply",
            "noreply",
            "do-not-reply",
            "donotreply",
            "mailer-daemon",
            "postmaster@"
        ).any { combined.contains(it) }
    }

    fun serializeTags(tags: Iterable<String>): String {
        return tags
            .map { it.trim().replace(",", " ") }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.US) }
            .joinToString(",")
    }

    fun extractSalientKeywords(subject: String, snippet: String, maxKeywords: Int = 6): List<String> {
        val subjectTokens = tokenizeWords(subject)
        val snippetTokens = tokenizeWords(snippet.take(140))
        val combined = LinkedHashSet<String>()
        for (w in subjectTokens) {
            combined.add(w)
            if (combined.size >= maxKeywords) break
        }
        if (combined.size < maxKeywords) {
            for (w in snippetTokens) {
                combined.add(w)
                if (combined.size >= maxKeywords) break
            }
        }
        return combined.toList()
    }

    private fun tokenizeWords(raw: String): List<String> {
        return raw.lowercase(Locale.US)
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { token ->
                token.length in 4..24 &&
                    token !in STOP_WORDS &&
                    !token.all { ch -> ch.isDigit() }
            }
    }

    /**
     * Offline email priority classifier using ONLY sender, subject, short snippet,
     * plus header flags (unsubscribe / reply) and learned/saved sender rules.
     */
    fun classifyOffline(
        senderDisplay: String,
        senderAddress: String,
        subject: String,
        snippet: String,
        hasUnsubscribeHeader: Boolean = false,
        isReplyHeader: Boolean = false,
        isCalendarInvite: Boolean = false,
        senderRule: EmailSenderRuleEntity? = null,
        allLearnedKeywords: Set<String> = emptySet()
    ): EmailClassificationDecision {
        val shortSnippet = snippet.take(180)
        val subjLower = subject.lowercase(Locale.US).trim()
        val combined = "$subjLower ${shortSnippet.lowercase(Locale.US)}"
        val senderLower = "${senderDisplay.lowercase(Locale.US)} ${senderAddress.lowercase(Locale.US)}"
        val noReply = isNoReplyAddress(senderAddress, senderDisplay)

        val invoiceMatch = firstMatchedKeyword(
            combined,
            listOf(
                "invoice", "payment due", "payment received", "payment", "wire transfer",
                "remittance", "receipt", "billing", "overdue", "proposal", "quote"
            )
        )
        val meetingMatch = firstMatchedKeyword(
            combined,
            listOf(
                "meeting", "invitation", "calendar invite", "invite", "zoom", "google meet",
                "microsoft teams", "phone call", "schedule a call", "call with", "interview",
                "appointment"
            )
        )
        val deadlineUrgentMatch = firstMatchedKeyword(
            combined,
            listOf(
                "deadline", "due today", "due tomorrow", "urgent", "asap",
                "action required", "time-sensitive", "immediate"
            )
        )
        val securityMatch = firstMatchedKeyword(
            combined,
            listOf(
                "security alert", "security notice", "verification code", "one-time passcode",
                "sign-in attempt", "suspicious", "unusual sign-in", "password reset", "two-factor"
            )
        )

        val promoMatch = firstMatchedKeyword(
            combined,
            listOf(
                "% off", "flash sale", "limited time offer", "promo code", "promotion",
                "discount", "special offer", "free shipping", "shop now", "clearance",
                "black friday", "cyber monday", "deal of the day"
            )
        )
        val newsletterMatch = firstMatchedKeyword(
            combined,
            listOf(
                "newsletter", "weekly digest", "daily digest", "substack", "unsubscribe",
                "view in browser", "manage preferences", "mailing list", "weekly roundup"
            )
        )
        val socialGameMatch = firstMatchedKeyword(
            "$senderLower $combined",
            listOf(
                "linkedin", "facebook", "instagram", "twitter", "x.com", "tiktok", "youtube",
                "discord", "reddit", "pinterest", "snapchat", "twitch", "steam", "playstation",
                "xbox", "epic games", "new follower", "liked your post", "commented on your",
                "connection request", "game invite"
            )
        )

        val baseCategory = when {
            invoiceMatch != null -> "INVOICE"
            isCalendarInvite || meetingMatch != null -> "MEETING"
            deadlineUrgentMatch != null -> "TASK"
            else -> "GENERAL"
        }

        val suggestedTaskTitle = when (baseCategory) {
            "INVOICE" -> "Review invoice: ${subject.take(65)} ($senderDisplay)"
            "MEETING" -> "Prepare for: ${subject.take(65)} ($senderDisplay)"
            else -> "${subject.take(65)} (from $senderDisplay)"
        }

        // 1. Explicit or learned sender overrides
        when (senderRule?.priorityOverride?.uppercase(Locale.US)) {
            "HIGH" -> {
                val reason = if (senderRule.positiveCount > 0) {
                    "Priority sender (learned from your tasks/tags)"
                } else {
                    "On your priority sender list"
                }
                return EmailClassificationDecision(
                    priority = "HIGH",
                    reason = reason,
                    isActionable = true,
                    category = if (baseCategory == "GENERAL") "TASK" else baseCategory,
                    extractedTaskTitle = suggestedTaskTitle
                )
            }
            "LOW" -> {
                val reason = if (senderRule.dismissCount >= 3) {
                    "Lowered to Low after ${senderRule.dismissCount} dismissals"
                } else {
                    "Sender set to Low priority"
                }
                return EmailClassificationDecision(
                    priority = "LOW",
                    reason = reason,
                    isActionable = false,
                    category = baseCategory,
                    extractedTaskTitle = suggestedTaskTitle
                )
            }
            "NORMAL" -> {
                return EmailClassificationDecision(
                    priority = "NORMAL",
                    reason = "Sender set to Normal priority",
                    isActionable = baseCategory != "GENERAL",
                    category = baseCategory,
                    extractedTaskTitle = suggestedTaskTitle
                )
            }
        }

        if ((senderRule?.dismissCount ?: 0) >= 3) {
            return EmailClassificationDecision(
                priority = "LOW",
                reason = "Lowered to Low after ${senderRule?.dismissCount} dismissals",
                isActionable = false,
                category = baseCategory,
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        // 2. High rules
        if (securityMatch != null) {
            return EmailClassificationDecision(
                priority = "HIGH",
                reason = "Security alert ('$securityMatch')",
                isActionable = true,
                category = "TASK",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        val isReply = isReplyHeader || subjLower.startsWith("re:")
        if (senderRule?.hasEmailedBefore == true && isReply) {
            return EmailClassificationDecision(
                priority = "HIGH",
                reason = "Reply from someone you have emailed",
                isActionable = true,
                category = if (baseCategory == "GENERAL") "TASK" else baseCategory,
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (isCalendarInvite) {
            return EmailClassificationDecision(
                priority = "HIGH",
                reason = "Calendar invitation",
                isActionable = true,
                category = "MEETING",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        val looksBulk = hasUnsubscribeHeader || newsletterMatch != null || promoMatch != null

        if (invoiceMatch != null && promoMatch == null) {
            return EmailClassificationDecision(
                priority = "HIGH",
                reason = "Invoice, payment, or proposal ('$invoiceMatch')",
                isActionable = true,
                category = "INVOICE",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (meetingMatch != null && !looksBulk) {
            return EmailClassificationDecision(
                priority = "HIGH",
                reason = "Meeting, call, interview, or appointment ('$meetingMatch')",
                isActionable = true,
                category = "MEETING",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (deadlineUrgentMatch != null && !looksBulk) {
            return EmailClassificationDecision(
                priority = "HIGH",
                reason = "Urgent or deadline ('$deadlineUrgentMatch')",
                isActionable = true,
                category = "TASK",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (!looksBulk && !noReply && allLearnedKeywords.isNotEmpty()) {
            val matchedLearned = tokenizeWords("$subject $shortSnippet")
                .firstOrNull { it in allLearnedKeywords }
            if (matchedLearned != null) {
                return EmailClassificationDecision(
                    priority = "HIGH",
                    reason = "Matches learned keyword '$matchedLearned'",
                    isActionable = true,
                    category = if (baseCategory == "GENERAL") "TASK" else baseCategory,
                    extractedTaskTitle = suggestedTaskTitle
                )
            }
        }

        // 3. Low rules
        if (hasUnsubscribeHeader) {
            return EmailClassificationDecision(
                priority = "LOW",
                reason = "Contains unsubscribe header",
                isActionable = false,
                category = "GENERAL",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (noReply) {
            return EmailClassificationDecision(
                priority = "LOW",
                reason = "No-reply sender address",
                isActionable = false,
                category = "GENERAL",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (newsletterMatch != null) {
            return EmailClassificationDecision(
                priority = "LOW",
                reason = "Newsletter ('$newsletterMatch')",
                isActionable = false,
                category = "GENERAL",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (promoMatch != null) {
            return EmailClassificationDecision(
                priority = "LOW",
                reason = "Promotion ('$promoMatch')",
                isActionable = false,
                category = "GENERAL",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        if (socialGameMatch != null) {
            return EmailClassificationDecision(
                priority = "LOW",
                reason = "Social or game notification ('$socialGameMatch')",
                isActionable = false,
                category = "GENERAL",
                extractedTaskTitle = suggestedTaskTitle
            )
        }

        // 4. Everything else is Normal
        return EmailClassificationDecision(
            priority = "NORMAL",
            reason = if (senderRule?.hasEmailedBefore == true) {
                "Known contact you have emailed"
            } else {
                "Direct unread message"
            },
            isActionable = false,
            category = baseCategory,
            extractedTaskTitle = suggestedTaskTitle
        )
    }

    private fun firstMatchedKeyword(haystackLower: String, keywords: List<String>): String? {
        for (kw in keywords) {
            if (kw.contains(' ') || kw.contains('%') || kw.contains('.')) {
                if (haystackLower.contains(kw)) return kw
            } else {
                val pattern = Regex("\\b${Regex.escape(kw)}\\b")
                if (pattern.containsMatchIn(haystackLower)) return kw
            }
        }
        return null
    }

    /**
     * Parses DTSTART from an iCalendar (VCALENDAR / .ics) payload if present.
     */
    fun parseIcsStartMillis(icsContent: String): Long? {
        if (icsContent.isBlank()) return null
        val unfolded = icsContent.replace("\r\n ", "").replace("\n ", "")
        val lines = unfolded.lines()
        val dtStartLine = lines.firstOrNull {
            val upper = it.trim().uppercase(Locale.US)
            upper.startsWith("DTSTART:") || upper.startsWith("DTSTART;")
        } ?: return null

        val colonIdx = dtStartLine.indexOf(':')
        if (colonIdx <= 0) return null
        val paramsPart = dtStartLine.substring(0, colonIdx)
        val rawValue = dtStartLine.substring(colonIdx + 1).trim()
        if (rawValue.length < 8) return null

        val tzId = Regex("TZID=([^;:]+)", RegexOption.IGNORE_CASE)
            .find(paramsPart)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        return try {
            when {
                rawValue.endsWith("Z", ignoreCase = true) && rawValue.length >= 15 -> {
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    fmt.parse(rawValue.uppercase(Locale.US))?.time
                }
                rawValue.contains("T", ignoreCase = true) && rawValue.length >= 15 -> {
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                        timeZone = if (!tzId.isNullOrBlank()) {
                            TimeZone.getTimeZone(tzId)
                        } else {
                            TimeZone.getDefault()
                        }
                    }
                    fmt.parse(rawValue.take(15).uppercase(Locale.US))?.time
                }
                rawValue.contains("T", ignoreCase = true) && rawValue.length >= 13 -> {
                    val fmt = SimpleDateFormat("yyyyMMdd'T'HHmm", Locale.US).apply {
                        timeZone = if (!tzId.isNullOrBlank()) {
                            TimeZone.getTimeZone(tzId)
                        } else {
                            TimeZone.getDefault()
                        }
                    }
                    fmt.parse(rawValue.take(13).uppercase(Locale.US))?.time
                }
                else -> {
                    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
                    val date = fmt.parse(rawValue.take(8)) ?: return null
                    Calendar.getInstance().apply {
                        time = date
                        set(Calendar.HOUR_OF_DAY, 9)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detects an explicit date + time inside an email's subject and short snippet
     * so we can offer "Remind me 30 minutes before".
     */
    fun detectDateTimeInText(
        subject: String,
        snippet: String,
        referenceMillis: Long = System.currentTimeMillis()
    ): Long? {
        val text = "$subject ${snippet.take(220)}"
        val lower = text.lowercase(Locale.US)

        val parsedClock = extractClockHourMinute(lower) ?: return null
        val (hour24, minute) = parsedClock
        val dateCal = extractDateCalendar(lower, referenceMillis) ?: return null

        dateCal.set(Calendar.HOUR_OF_DAY, hour24.coerceIn(0, 23))
        dateCal.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        dateCal.set(Calendar.SECOND, 0)
        dateCal.set(Calendar.MILLISECOND, 0)
        return dateCal.timeInMillis
    }

    private fun extractClockHourMinute(lower: String): Pair<Int, Int>? {
        val amPmRegex = Regex("\\b(1[0-2]|0?[1-9])(?::([0-5]\\d))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)\\b")
        amPmRegex.find(lower)?.let { m ->
            var h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val mer = m.groupValues[3].replace(".", "")
            if (mer == "pm" && h in 1..11) h += 12
            if (mer == "am" && h == 12) h = 0
            return h to min
        }

        val twentyFourRegex = Regex("\\b(?:at|from|@|\\s)([01]\\d|2[0-3]):([0-5]\\d)\\b")
        twentyFourRegex.find(lower)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            return h to min
        }

        if (Regex("\\bnoon\\b").containsMatchIn(lower)) {
            return 12 to 0
        }
        return null
    }

    private fun extractDateCalendar(lower: String, referenceMillis: Long): Calendar? {
        val baseCal = Calendar.getInstance().apply { timeInMillis = referenceMillis }

        // 1. ISO yyyy-MM-dd
        Regex("\\b(20\\d{2})-(0?[1-9]|1[0-2])-(0?[1-9]|[12]\\d|3[01])\\b").find(lower)?.let { m ->
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt() - 1
            val day = m.groupValues[3].toInt()
            return (baseCal.clone() as Calendar).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
            }
        }

        val monthMap = mapOf(
            "jan" to Calendar.JANUARY, "january" to Calendar.JANUARY,
            "feb" to Calendar.FEBRUARY, "february" to Calendar.FEBRUARY,
            "mar" to Calendar.MARCH, "march" to Calendar.MARCH,
            "apr" to Calendar.APRIL, "april" to Calendar.APRIL,
            "may" to Calendar.MAY,
            "jun" to Calendar.JUNE, "june" to Calendar.JUNE,
            "jul" to Calendar.JULY, "july" to Calendar.JULY,
            "aug" to Calendar.AUGUST, "august" to Calendar.AUGUST,
            "sep" to Calendar.SEPTEMBER, "sept" to Calendar.SEPTEMBER, "september" to Calendar.SEPTEMBER,
            "oct" to Calendar.OCTOBER, "october" to Calendar.OCTOBER,
            "nov" to Calendar.NOVEMBER, "november" to Calendar.NOVEMBER,
            "dec" to Calendar.DECEMBER, "december" to Calendar.DECEMBER
        )
        val monthAlternation = monthMap.keys.sortedByDescending { it.length }.joinToString("|")

        // 2. "Nov 5" / "November 5th, 2026"
        Regex("\\b($monthAlternation)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s*(20\\d{2}))?\\b")
            .find(lower)
            ?.let { m ->
                val month = monthMap[m.groupValues[1]] ?: return null
                val day = m.groupValues[2].toIntOrNull() ?: return null
                val explicitYear = m.groupValues[3].toIntOrNull()
                return (baseCal.clone() as Calendar).apply {
                    if (explicitYear != null) set(Calendar.YEAR, explicitYear)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
            }

        // 3. "5 Nov" / "5th November 2026"
        Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($monthAlternation)\\.?(?:,?\\s*(20\\d{2}))?\\b")
            .find(lower)
            ?.let { m ->
                val day = m.groupValues[1].toIntOrNull() ?: return null
                val month = monthMap[m.groupValues[2]] ?: return null
                val explicitYear = m.groupValues[3].toIntOrNull()
                return (baseCal.clone() as Calendar).apply {
                    if (explicitYear != null) set(Calendar.YEAR, explicitYear)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
            }

        // 4. Relative words: tomorrow / today / tonight
        if (Regex("\\btomorrow\\b").containsMatchIn(lower)) {
            return (baseCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        if (Regex("\\b(today|tonight|this afternoon|this morning|this evening)\\b").containsMatchIn(lower)) {
            return (baseCal.clone() as Calendar)
        }

        // 5. Weekday names ("Monday", "Tue", etc.)
        val weekdayMap = listOf(
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY
        )
        for ((dayName, targetDow) in weekdayMap) {
            if (Regex("\\b$dayName\\b").containsMatchIn(lower)) {
                return (baseCal.clone() as Calendar).apply {
                    var delta = (targetDow - get(Calendar.DAY_OF_WEEK) + 7) % 7
                    if (delta == 0 && lower.contains("next $dayName")) {
                        delta = 7
                    }
                    add(Calendar.DAY_OF_YEAR, delta)
                }
            }
        }

        return null
    }

    /**
     * Learns from the user when an email is added to Tasks or given a non-Low tag:
     * remembers the sender and its keywords, and raises that sender's priority to HIGH.
     */
    suspend fun learnPositiveInteraction(
        db: AppDatabase,
        senderDisplay: String,
        senderAddress: String,
        subject: String,
        snippet: String
    ) {
        val key = normalizeSenderKey(senderAddress, senderDisplay)
        if (key.isBlank()) return

        val existing = db.emailRuleDao().getSenderRule(key)
        val newKeywords = extractSalientKeywords(subject, snippet)
        val mergedKeywords = buildList {
            addAll(newKeywords)
            existing?.learnedKeywordsList?.forEach { add(it) }
        }.distinct().take(20)

        val updatedRule = EmailSenderRuleEntity(
            senderKey = key,
            senderAddress = extractEmailAddress(senderAddress).ifBlank { existing?.senderAddress.orEmpty() },
            senderDisplayName = senderDisplay.ifBlank { existing?.senderDisplayName.orEmpty() },
            priorityOverride = "HIGH",
            dismissCount = 0,
            positiveCount = (existing?.positiveCount ?: 0) + 1,
            learnedKeywordsCsv = mergedKeywords.joinToString(","),
            hasEmailedBefore = existing?.hasEmailedBefore ?: false,
            updatedAt = System.currentTimeMillis()
        )
        db.emailRuleDao().upsertSenderRule(updatedRule)
        recalculateCachedEmails(db)
    }

    /**
     * Dismisses an email ONLY inside the local app cache (never marks, moves, or deletes on IMAP).
     * If the user dismisses 3 emails from the same sender, lowers that sender to LOW.
     *
     * @return true if this dismissal lowered the sender to LOW priority.
     */
    suspend fun recordDismissalInAppOnly(
        db: AppDatabase,
        email: UnreadEmailEntity
    ): Boolean {
        db.emailDao().markDismissedInApp(email.id)

        val key = normalizeSenderKey(email.senderAddress, email.sender)
        if (key.isBlank()) return false

        val existing = db.emailRuleDao().getSenderRule(key)
        val nextDismissCount = (existing?.dismissCount ?: 0) + 1
        val shouldLowerToLow = nextDismissCount >= 3
        val updatedOverride = if (shouldLowerToLow) {
            "LOW"
        } else {
            existing?.priorityOverride ?: "AUTO"
        }

        val updatedRule = EmailSenderRuleEntity(
            senderKey = key,
            senderAddress = email.senderAddress.ifBlank { existing?.senderAddress.orEmpty() },
            senderDisplayName = email.sender.ifBlank { existing?.senderDisplayName.orEmpty() },
            priorityOverride = updatedOverride,
            dismissCount = nextDismissCount,
            positiveCount = existing?.positiveCount ?: 0,
            learnedKeywordsCsv = existing?.learnedKeywordsCsv.orEmpty(),
            hasEmailedBefore = existing?.hasEmailedBefore ?: false,
            updatedAt = System.currentTimeMillis()
        )
        db.emailRuleDao().upsertSenderRule(updatedRule)

        if (shouldLowerToLow) {
            recalculateCachedEmails(db)
        }
        return shouldLowerToLow
    }

    /**
     * Re-evaluates all cached unread emails in Room whenever sender rules or learned keywords change.
     */
    suspend fun recalculateCachedEmails(db: AppDatabase) {
        val allRules = db.emailRuleDao().getAllSenderRulesDirect()
        val rulesByKey = allRules.associateBy { it.senderKey }
        val learnedKeywords = allRules
            .filter { it.priorityOverride == "HIGH" || it.positiveCount > 0 }
            .flatMap { it.learnedKeywordsList }
            .toSet()

        val cachedEmails = db.emailDao().getRecentUnreadDirect(100)
        if (cachedEmails.isEmpty()) return

        val reevaluated = cachedEmails.map { email ->
            val key = normalizeSenderKey(email.senderAddress, email.sender)
            val rule = rulesByKey[key]
            val userTaggedLow = email.tagsList.any { it.equals("Low", ignoreCase = true) }

            if (userTaggedLow && rule?.priorityOverride != "HIGH") {
                email.copy(
                    priority = "LOW",
                    priorityReason = "Tagged Low by you"
                )
            } else {
                val decision = classifyOffline(
                    senderDisplay = email.sender,
                    senderAddress = email.senderAddress,
                    subject = email.subject,
                    snippet = email.snippet,
                    hasUnsubscribeHeader = email.hasUnsubscribeOrNoReply &&
                        !isNoReplyAddress(email.senderAddress, email.sender),
                    isReplyHeader = email.subject.startsWith("re:", ignoreCase = true),
                    isCalendarInvite = email.isCalendarInvite,
                    senderRule = rule,
                    allLearnedKeywords = learnedKeywords
                )
                email.copy(
                    priority = decision.priority,
                    priorityReason = decision.reason,
                    isActionable = decision.isActionable,
                    category = decision.category,
                    extractedTaskTitle = email.extractedTaskTitle ?: decision.extractedTaskTitle
                )
            }
        }
        db.emailDao().upsertEmails(reevaluated)
    }
}
