package com.xestlab.assistant.ai

import com.xestlab.assistant.service.LeadCadenceEngine
import java.util.Locale

object MoneyAndLeadParser {

    private val SMALL_NUMBERS = mapOf(
        "zero" to 0L,
        "one" to 1L,
        "a" to 1L,
        "two" to 2L,
        "three" to 3L,
        "four" to 4L,
        "five" to 5L,
        "six" to 6L,
        "seven" to 7L,
        "eight" to 8L,
        "nine" to 9L,
        "ten" to 10L,
        "eleven" to 11L,
        "twelve" to 12L,
        "thirteen" to 13L,
        "fourteen" to 14L,
        "fifteen" to 15L,
        "sixteen" to 16L,
        "seventeen" to 17L,
        "eighteen" to 18L,
        "nineteen" to 19L,
        "twenty" to 20L,
        "thirty" to 30L,
        "forty" to 40L,
        "fourty" to 40L,
        "fifty" to 50L,
        "sixty" to 60L,
        "seventy" to 70L,
        "eighty" to 80L,
        "ninety" to 90L
    )

    fun isAffirmativeConfirmation(raw: String): Boolean {
        val clean = raw.trim().lowercase(Locale.US).trimEnd('.', '!', '?')
        return clean in setOf(
            "yes",
            "yeah",
            "yep",
            "confirm",
            "confirmed",
            "save",
            "save it",
            "log it",
            "do it",
            "proceed",
            "go ahead",
            "yes confirm",
            "yes please",
            "correct"
        )
    }

    fun isNegativeConfirmation(raw: String): Boolean {
        val clean = raw.trim().lowercase(Locale.US).trimEnd('.', '!', '?')
        return clean in setOf(
            "no",
            "nope",
            "cancel",
            "stop",
            "discard",
            "never mind",
            "nevermind",
            "don't save",
            "do not save"
        )
    }

    /**
     * Parses Nigerian currency amounts including:
     * - "150k", "80,000", "1.5m", "2 million", "₦250,000"
     * - Spelled-out numbers: "one hundred and fifty thousand", "eighty thousand",
     *   "one point five million", "two hundred k"
     */
    fun parseNairaAmount(raw: String): Long? {
        if (raw.isBlank()) return null
        val cleaned = raw
            .replace("₦", " ")
            .replace(Regex("\\b(?:ngn|naira)\\b", RegexOption.IGNORE_CASE), " ")
            .trim()

        // 1. Numeric with k / m / million / thousand suffix or plain digits/commas
        // Avoid matching Nigerian 10-11 digit phone numbers starting with 070/080/081/090/091 or +234
        val withoutPhones = cleaned.replace(
            Regex("(?:\\+?234|0)[789][01]\\d[\\s\\-]?\\d{3}[\\s\\-]?\\d{4}"),
            " "
        )

        val numericRegex = Regex(
            "(?<![\\d+])(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*(million|mill|mn|m|thousand|k)?\\b",
            RegexOption.IGNORE_CASE
        )

        for (match in numericRegex.findAll(withoutPhones)) {
            val numRaw = match.groupValues[1].replace(",", "")
            val suffix = match.groupValues[2].lowercase(Locale.US)
            val base = numRaw.toDoubleOrNull() ?: continue
            val multiplier = when (suffix) {
                "million", "mill", "mn", "m" -> 1_000_000.0
                "thousand", "k" -> 1_000.0
                else -> 1.0
            }
            val computed = (base * multiplier).toLong()
            if (computed >= 500L || suffix.isNotEmpty()) {
                return computed
            }
        }

        // 2. Spelled-out decimal million/thousand e.g. "one point five million"
        val pointWordRegex = Regex(
            "\\b([a-z]+)\\s+point\\s+([a-z]+)\\s+(million|thousand|m|k)\\b",
            RegexOption.IGNORE_CASE
        )
        pointWordRegex.find(withoutPhones)?.let { m ->
            val whole = SMALL_NUMBERS[m.groupValues[1].lowercase(Locale.US)]
            val frac = SMALL_NUMBERS[m.groupValues[2].lowercase(Locale.US)]
            val unit = m.groupValues[3].lowercase(Locale.US)
            if (whole != null && frac != null && frac < 10L) {
                val combined = whole.toDouble() + (frac.toDouble() / 10.0)
                val mult = if (unit == "million" || unit == "m") 1_000_000.0 else 1_000.0
                return (combined * mult).toLong()
            }
        }

        // 3. General spelled-out English numbers ("one hundred and fifty thousand", "eighty thousand")
        return parseSpelledOutNumber(withoutPhones)
    }

    private fun parseSpelledOutNumber(text: String): Long? {
        val tokens = text.lowercase(Locale.US)
            .replace("-", " ")
            .replace(",", " ")
            .split(Regex("\\s+"))
            .map { it.trim('.', '?', '!', ':', ';') }
            .filter { it.isNotEmpty() && it != "and" }

        var total = 0L
        var current = 0L
        var matchedAnyNumberWord = false

        for (token in tokens) {
            val small = SMALL_NUMBERS[token]
            when {
                token == "a" && !matchedAnyNumberWord -> {
                    // Only treat "a" as 1 if immediately followed by hundred/thousand/million, handled below
                    continue
                }
                small != null -> {
                    current += small
                    matchedAnyNumberWord = true
                }
                token == "hundred" -> {
                    current = (if (current == 0L) 1L else current) * 100L
                    matchedAnyNumberWord = true
                }
                token == "thousand" || token == "k" -> {
                    if (matchedAnyNumberWord || current > 0L) {
                        total += (if (current == 0L) 1L else current) * 1_000L
                        current = 0L
                        matchedAnyNumberWord = true
                    }
                }
                token == "million" || token == "m" -> {
                    if (matchedAnyNumberWord || current > 0L) {
                        total += (if (current == 0L) 1L else current) * 1_000_000L
                        current = 0L
                        matchedAnyNumberWord = true
                    }
                }
                else -> {
                    if (matchedAnyNumberWord && (total + current) >= 1_000L) {
                        return total + current
                    } else if (matchedAnyNumberWord) {
                        total = 0L
                        current = 0L
                        matchedAnyNumberWord = false
                    }
                }
            }
        }

        val finalValue = total + current
        return if (matchedAnyNumberWord && finalValue >= 1_000L) finalValue else null
    }

    fun inferPaymentType(raw: String): String {
        val lower = raw.lowercase(Locale.US)
        return when {
            lower.contains("retainer") || lower.contains("monthly plan") || lower.contains("social retainer") ->
                LeadCadenceEngine.PAYMENT_RETAINER
            lower.contains("maintenance") || lower.contains("hosting") || lower.contains("domain") || lower.contains("support") ->
                LeadCadenceEngine.PAYMENT_MAINTENANCE
            lower.contains("other") ->
                LeadCadenceEngine.PAYMENT_OTHER
            else ->
                LeadCadenceEngine.PAYMENT_WEBSITE
        }
    }

    /**
     * Parses "add lead Tola, website, 0803..." or "new lead Chidi social 08123456789".
     */
    fun tryParseAddLead(raw: String): ParsedIntent.AddLeadVoice? {
        val cleaned = raw.trim()
        val addLeadRegex = Regex("^(?:add|create|new)\\s+lead\\s*[:\\-]?\\s*(.+)$", RegexOption.IGNORE_CASE)
        val match = addLeadRegex.find(cleaned) ?: return null
        val body = match.groupValues[1].trim()
        if (body.isBlank()) return null

        val phoneMatch = Regex("(\\+?\\d[\\d\\s\\-]{6,15}\\d)").find(body)
        val extractedPhone = phoneMatch?.value?.replace(Regex("\\s+"), "")?.trim().orEmpty()
        val bodyWithoutPhone = if (phoneMatch != null) {
            body.removeRange(phoneMatch.range).trim()
        } else {
            body
        }

        val parts = bodyWithoutPhone
            .split(Regex("[,;|]"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        var name = ""
        var service = LeadCadenceEngine.SERVICE_WEBSITE
        var observation = ""

        if (parts.size >= 2) {
            name = parts[0]
            service = LeadCadenceEngine.normalizeService(parts[1])
            if (parts.size >= 3) {
                observation = parts.drop(2).joinToString(", ")
            }
        } else {
            // Single space-separated phrase like "Tola website" or "Tola Gourmet social"
            val tokens = bodyWithoutPhone.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val serviceIdx = tokens.indexOfFirst { tok ->
                val u = tok.uppercase(Locale.US)
                u in setOf("WEBSITE", "WEB", "SITE", "SOCIAL", "INSTAGRAM", "OTHER")
            }
            if (serviceIdx > 0) {
                name = tokens.subList(0, serviceIdx).joinToString(" ")
                service = LeadCadenceEngine.normalizeService(tokens[serviceIdx])
                if (serviceIdx + 1 < tokens.size) {
                    observation = tokens.subList(serviceIdx + 1, tokens.size).joinToString(" ")
                }
            } else {
                name = bodyWithoutPhone
            }
        }

        val cleanName = name
            .replace(Regex("\\b(?:for|service)\\b$", RegexOption.IGNORE_CASE), "")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        if (cleanName.isBlank()) return null

        return ParsedIntent.AddLeadVoice(
            name = cleanName,
            service = service,
            phone = extractedPhone,
            observation = observation.trim(),
            sourceText = cleaned
        )
    }

    /**
     * Parses:
     * - "who is due today" / "who's due today" / "due follow ups"
     */
    fun isWhoIsDueToday(raw: String): Boolean {
        val lower = raw.trim().lowercase(Locale.US).trimEnd('?', '.', '!')
        return lower == "who is due today" ||
            lower == "whos due today" ||
            lower == "who's due today" ||
            lower == "who is due for follow up" ||
            lower == "who is due for follow up today" ||
            lower == "who is due for follow-up today" ||
            lower == "due follow ups" ||
            lower == "due follow-ups" ||
            lower == "follow ups due today" ||
            lower == "follow-ups due today"
    }

    /**
     * Parses lead stage voice commands:
     * - "Tola replied"
     * - "quoted Tola 150k" / "Tola quoted 150k"
     * - "won Tola" / "Tola won"
     * - "lost Tola" / "Tola lost"
     */
    fun tryParseLeadStageCommand(raw: String): ParsedIntent.LeadStageVoice? {
        val cleaned = raw.trim().trimEnd('.', '!')
        if (cleaned.endsWith("?")) return null

        // "quoted Tola 150k" or "quoted Tola for 250,000"
        Regex("^quoted\\s+([a-zA-Z][a-zA-Z0-9'\\s.&-]{0,40}?)(?:\\s+(?:for|at)?\\s*(.+))?$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.let { m ->
                val rawRest = m.groupValues[2].trim()
                val amount = if (rawRest.isNotBlank()) parseNairaAmount(rawRest) else parseNairaAmount(cleaned)
                val rawName = stripTrailingAmountFromName(m.groupValues[1])
                if (rawName.isNotBlank()) {
                    return ParsedIntent.LeadStageVoice(
                        leadName = titleCaseName(rawName),
                        targetStage = LeadCadenceEngine.STAGE_QUOTED,
                        quoteAmount = amount,
                        sourceText = raw
                    )
                }
            }

        // "won Tola" / "lost Tola"
        Regex("^(won|lost)\\s+(?:lead\\s+)?([a-zA-Z][a-zA-Z0-9'\\s.&-]{0,40})$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.let { m ->
                val stage = if (m.groupValues[1].equals("won", ignoreCase = true)) {
                    LeadCadenceEngine.STAGE_WON
                } else {
                    LeadCadenceEngine.STAGE_LOST
                }
                val leadName = m.groupValues[2].trim()
                if (leadName.isNotBlank() && leadName.lowercase(Locale.US) !in setOf("a", "the", "my", "game")) {
                    return ParsedIntent.LeadStageVoice(
                        leadName = titleCaseName(leadName),
                        targetStage = stage,
                        quoteAmount = null,
                        sourceText = raw
                    )
                }
            }

        // "<Name> replied"
        Regex("^([a-zA-Z][a-zA-Z0-9'\\s.&-]{0,35})\\s+replied$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.let { m ->
                val leadName = m.groupValues[1].trim()
                if (leadName.isNotBlank() && leadName.lowercase(Locale.US) !in setOf("nobody", "no one", "who", "he", "she", "they")) {
                    return ParsedIntent.LeadStageVoice(
                        leadName = titleCaseName(leadName),
                        targetStage = LeadCadenceEngine.STAGE_REPLIED,
                        quoteAmount = null,
                        sourceText = raw
                    )
                }
            }

        return null
    }

    /**
     * Parses payment commands:
     * - "Kaizy paid 150k"
     * - "Udofe paid 80,000 for retainer"
     * - "Log payment 150k from Kaizy"
     * - "Received 250k from Chidi for website"
     */
    fun tryParsePaymentCommand(raw: String): ParsedIntent.LogPaymentVoice? {
        val cleaned = raw.trim().trimEnd('.', '!')
        if (cleaned.endsWith("?")) return null

        // Pattern A: "<Client> paid <amount/rest>"
        val clientPaidRegex = Regex(
            "^([a-zA-Z][a-zA-Z0-9'\\s.&-]{0,40}?)\\s+paid\\s+(.+)$",
            RegexOption.IGNORE_CASE
        )
        clientPaidRegex.find(cleaned)?.let { m ->
            val rawClient = m.groupValues[1].trim()
            val rest = m.groupValues[2].trim()
            if (rawClient.lowercase(Locale.US) !in setOf("i", "we", "you", "who", "have", "has")) {
                val amount = parseNairaAmount(rest)
                if (amount != null && amount > 0L) {
                    val payType = inferPaymentType(rest)
                    return ParsedIntent.LogPaymentVoice(
                        clientName = titleCaseName(rawClient),
                        amount = amount,
                        paymentType = payType,
                        note = cleaned,
                        sourceText = raw
                    )
                }
            }
        }

        // Pattern B: "(log payment|received|collected) <amount> from <Client> [for <type>]"
        val receivedFromRegex = Regex(
            "^(?:log\\s+payment|logged\\s+payment|received|collected)\\s+(.+?)\\s+from\\s+([a-zA-Z][a-zA-Z0-9'\\s.&-]{1,40}?)(?:\\s+for\\s+(.+))?$",
            RegexOption.IGNORE_CASE
        )
        receivedFromRegex.find(cleaned)?.let { m ->
            val amountPart = m.groupValues[1]
            val clientPart = m.groupValues[2].trim()
            val reasonPart = m.groupValues[3].trim()
            val amount = parseNairaAmount(amountPart) ?: parseNairaAmount(cleaned)
            if (amount != null && amount > 0L && clientPart.isNotBlank()) {
                val payType = inferPaymentType("$amountPart $reasonPart")
                return ParsedIntent.LogPaymentVoice(
                    clientName = titleCaseName(clientPart),
                    amount = amount,
                    paymentType = payType,
                    note = cleaned,
                    sourceText = raw
                )
            }
        }

        return null
    }

    private fun stripTrailingAmountFromName(rawName: String): String {
        return rawName
            .replace(Regex("\\s+\\d[\\d,.kmKM]*$"), "")
            .trim()
    }

    private fun titleCaseName(input: String): String {
        return input.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
                }
            }
    }
}
