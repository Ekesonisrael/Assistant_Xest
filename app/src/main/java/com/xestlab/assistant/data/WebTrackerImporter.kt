package com.xestlab.assistant.data

import com.xestlab.assistant.ai.MoneyAndLeadParser
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Fact
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.Lead
import com.xestlab.assistant.data.model.LeadEvent
import com.xestlab.assistant.data.model.MessageTemplateEntity
import com.xestlab.assistant.data.model.Payment
import com.xestlab.assistant.service.LeadCadenceEngine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

object WebTrackerImporter {

    val PIPELINE_DEFINITIONS = mapOf(
        "setupOnce" to listOf(
            "Website tiers written: landing page, business site, e-commerce",
            "Monthly plan written: maintenance and hosting",
            "One-page agreement: 50-70% deposit, balance before handover",
            "Quote template made: middle tier highlighted, valid 7 days",
            "Pricing page on the site, matching PDF",
            "Intake form built: brand assets, references, domain access",
            "Client folder template: drafts, assets, agreement, receipts",
            "Pre-recorded demo video of past projects",
            "Lead sheet initialized: name, business, channel, status",
            "Case study or proof of work posted"
        ),
        "everyDay" to listOf(
            "10 personalized messages sent to business owners",
            "Follow-ups due sent",
            "One content post published",
            "Lead sheet updated with new contacts"
        ),
        "followUpCadence" to listOf(
            "Day 2 follow-up: quick check-in on quote",
            "Day 4 follow-up: value add or past work sample",
            "Day 7 follow-up: final check before quote expiry",
            "Day 14 follow-up: gentle reconnect"
        ),
        "salesConversation" to listOf(
            "Discovery conversation: diagnose pain before quoting",
            "Present middle tier recommendation",
            "Send agreement and invoice for deposit",
            "Confirm receipt of deposit before scheduling start",
            "Send intake form link immediately upon payment"
        ),
        "onboarding" to listOf(
            "Kickoff call within 48 hours of deposit",
            "Receive brand assets and content",
            "Domain and hosting credentials verified",
            "Deliver first prototype within 5 working days",
            "Collect structured feedback round 1",
            "Collect structured feedback round 2"
        ),
        "afterDelivery" to listOf(
            "Handover training video recorded",
            "Collect final balance payment before DNS live",
            "Request Google review or testimonial",
            "Offer monthly maintenance retainer plan",
            "Check in 30 days post-launch"
        )
    )

    suspend fun importData(
        db: AppDatabase,
        userPrefs: UserPreferencesDataStore,
        jsonString: String
    ): Boolean {
        return try {
            val root = JSONObject(jsonString)

            if (root.has("settings")) {
                val sObj = root.getJSONObject("settings")
                val curGoal = db.goalDao().getGoalsDirect() ?: Goal()
                val curPrefs = userPrefs.getSnapshot()

                val yGoal = sObj.optLong("yearGoal", curGoal.yearlyGoal)
                val sDate = sObj.optString("startDate", curGoal.startDate)
                val webPrice = sObj.optLong("websitePrice", curGoal.websitePrice)
                val retPrice = sObj.optLong("retainerPrice", curGoal.retainerPrice)
                val mTarget = sObj.optLong("monthlyTarget", curGoal.monthlyTarget)

                var wMsg = curGoal.weeklyMessagesTarget
                var wConv = curGoal.weeklyConversationsTarget
                var wQuotes = curGoal.weeklyQuotesTarget

                if (sObj.has("weeklyTargets")) {
                    val wObj = sObj.getJSONObject("weeklyTargets")
                    wMsg = wObj.optInt("messages", wMsg)
                    wConv = wObj.optInt("conversations", wConv)
                    wQuotes = wObj.optInt("quotes", wQuotes)
                }

                var q1 = curGoal.q1Target
                var q2 = curGoal.q2Target
                var q3 = curGoal.q3Target
                var q4 = curGoal.q4Target

                if (sObj.has("quarterTargets")) {
                    val qObj = sObj.getJSONObject("quarterTargets")
                    q1 = qObj.optLong("q1", q1)
                    q2 = qObj.optLong("q2", q2)
                    q3 = qObj.optLong("q3", q3)
                    q4 = qObj.optLong("q4", q4)
                }

                db.goalDao().saveGoals(
                    curGoal.copy(
                        yearlyGoal = yGoal,
                        startDate = sDate,
                        weeklyMessagesTarget = wMsg,
                        weeklyConversationsTarget = wConv,
                        weeklyQuotesTarget = wQuotes,
                        q1Target = q1,
                        q2Target = q2,
                        q3Target = q3,
                        q4Target = q4,
                        websitePrice = webPrice,
                        retainerPrice = retPrice,
                        monthlyTarget = mTarget
                    )
                )

                val importedName = sObj.optString("userName", curPrefs.userName)
                val wake = sObj.optString("wakeUpTime", curPrefs.wakeUpTime)
                val qStart = sObj.optString("quietHoursStart", curPrefs.quietHoursStart)
                val qEnd = sObj.optString("quietHoursEnd", curPrefs.quietHoursEnd)
                val qEnabled = sObj.optBoolean("quietHoursEnabled", curPrefs.quietHoursEnabled)
                val vSpeed = sObj.optDouble("voiceSpeed", curPrefs.voiceSpeed.toDouble()).toFloat()

                userPrefs.updateProfileAndHours(
                    userName = importedName,
                    wakeUpTime = wake,
                    quietHoursStart = qStart,
                    quietHoursEnd = qEnd,
                    quietHoursEnabled = qEnabled,
                    voiceSpeed = vSpeed,
                    autoReadPlanOnLaunch = curPrefs.autoReadPlanOnLaunch
                )
            }

            if (root.has("days")) {
                val daysObj = root.getJSONObject("days")
                val keys = daysObj.keys()
                val existingLogs = db.dailyLogDao().getAllLogs().associateBy { "${it.date}_${it.itemId}" }
                val newLogs = mutableListOf<DailyLog>()

                while (keys.hasNext()) {
                    val dateKey = keys.next()
                    val dayData = daysObj.optJSONObject(dateKey) ?: continue
                    val msgs = dayData.optInt("messagesSent", 0)
                    if (msgs > 0 && !existingLogs.containsKey("${dateKey}_messages10")) {
                        newLogs.add(DailyLog(date = dateKey, itemId = "messages10", isDone = true, countValue = msgs))
                    }
                    if (dayData.has("checklist")) {
                        val cl = dayData.optJSONObject("checklist")
                        if (cl != null) {
                            for (itemKey in listOf("followups", "content", "study", "leadsheet")) {
                                if (cl.optBoolean(itemKey, false) && !existingLogs.containsKey("${dateKey}_$itemKey")) {
                                    newLogs.add(DailyLog(date = dateKey, itemId = itemKey, isDone = true))
                                }
                            }
                        }
                    }
                }
                if (newLogs.isNotEmpty()) {
                    db.dailyLogDao().insertAll(newLogs)
                }
            } else if (root.has("logs")) {
                val logsArr = root.getJSONArray("logs")
                val existingLogs = db.dailyLogDao().getAllLogs().associateBy { "${it.date}_${it.itemId}" }
                val newLogs = mutableListOf<DailyLog>()

                for (i in 0 until logsArr.length()) {
                    val lObj = logsArr.getJSONObject(i)
                    val date = lObj.getString("date")
                    val itemId = lObj.getString("itemId")
                    if (!existingLogs.containsKey("${date}_$itemId")) {
                        newLogs.add(
                            DailyLog(
                                date = date,
                                itemId = itemId,
                                isDone = lObj.getBoolean("isDone"),
                                countValue = lObj.optInt("countValue", 0),
                                reasonNotDone = if (lObj.has("reasonNotDone") && !lObj.isNull("reasonNotDone")) lObj.getString("reasonNotDone") else null,
                                timestamp = lObj.optLong("timestamp", System.currentTimeMillis())
                            )
                        )
                    }
                }
                if (newLogs.isNotEmpty()) {
                    db.dailyLogDao().insertAll(newLogs)
                }
            }

            if (root.has("pipeline")) {
                val pipeAny = root.get("pipeline")
                if (pipeAny is JSONObject) {
                    val existingItems = db.itemDao().getAllItemsDirect().associateBy { it.title }
                    val newItems = mutableListOf<Item>()

                    PIPELINE_DEFINITIONS.forEach { (stageKey, itemTitles) ->
                        if (pipeAny.has(stageKey)) {
                            val arr = pipeAny.optJSONArray(stageKey) ?: return@forEach
                            for (idx in 0 until arr.length()) {
                                val isCompleted = arr.optBoolean(idx, false)
                                val title = itemTitles.getOrNull(idx) ?: "Pipeline step: $stageKey #$idx"
                                if (!existingItems.containsKey(title)) {
                                    newItems.add(
                                        Item(
                                            type = "TASK",
                                            title = title,
                                            details = "Stage: $stageKey",
                                            status = if (isCompleted) "COMPLETED" else "PENDING",
                                            priority = "NORMAL",
                                            sourceText = "web_pipeline_$stageKey"
                                        )
                                    )
                                } else if (isCompleted && existingItems[title]?.status != "COMPLETED") {
                                    db.itemDao().updateStatus(existingItems[title]!!.id, "COMPLETED")
                                }
                            }
                        }
                    }
                    if (newItems.isNotEmpty()) {
                        db.itemDao().insertItems(newItems)
                    }
                }
            }

            if (root.has("items")) {
                val itemsArr = root.getJSONArray("items")
                val existingItems = db.itemDao().getAllItemsDirect().associateBy { it.title }
                val newItems = mutableListOf<Item>()
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.getJSONObject(i)
                    val title = obj.optString("title", "")
                    if (title.isNotBlank() && !existingItems.containsKey(title)) {
                        newItems.add(
                            Item(
                                type = obj.optString("type", "TASK"),
                                title = title,
                                details = obj.optString("details", ""),
                                dueAt = if (obj.has("dueAt") && !obj.isNull("dueAt")) obj.optLong("dueAt") else null,
                                durationMinutes = obj.optInt("durationMinutes", 0),
                                priority = obj.optString("priority", "NORMAL"),
                                status = obj.optString("status", "PENDING"),
                                targetAmount = if (obj.has("targetAmount") && !obj.isNull("targetAmount")) obj.optDouble("targetAmount") else null,
                                unit = if (obj.has("unit") && !obj.isNull("unit")) obj.optString("unit") else null,
                                progress = obj.optDouble("progress", 0.0),
                                contactName = if (obj.has("contactName") && !obj.isNull("contactName")) obj.optString("contactName") else null,
                                sourceText = obj.optString("sourceText", "")
                            )
                        )
                    }
                }
                if (newItems.isNotEmpty()) {
                    db.itemDao().insertItems(newItems)
                }
            }

            if (root.has("facts")) {
                val factsArr = root.getJSONArray("facts")
                val existingFacts = db.factDao().getAllFactsDirect().map { it.text }.toSet()
                val newFacts = mutableListOf<Fact>()
                for (i in 0 until factsArr.length()) {
                    val fObj = factsArr.getJSONObject(i)
                    val text = fObj.optString("text", "")
                    if (text.isNotBlank() && !existingFacts.contains(text)) {
                        newFacts.add(Fact(text = text, category = fObj.optString("category", "GENERAL")))
                    }
                }
                if (newFacts.isNotEmpty()) {
                    db.factDao().insertFacts(newFacts)
                }
            }

            // Import editable message templates if present
            if (root.has("messageTemplates")) {
                val tplArr = root.optJSONArray("messageTemplates")
                if (tplArr != null) {
                    val templates = mutableListOf<MessageTemplateEntity>()
                    for (i in 0 until tplArr.length()) {
                        val tObj = tplArr.optJSONObject(i) ?: continue
                        val stepKey = tObj.optString("stepKey", "").trim()
                        val body = tObj.optString("bodyTemplate", "").trim()
                        if (stepKey.isNotEmpty() && body.isNotEmpty()) {
                            templates.add(
                                MessageTemplateEntity(
                                    stepKey = stepKey,
                                    title = tObj.optString("title", stepKey),
                                    bodyTemplate = body,
                                    orderIndex = tObj.optInt("orderIndex", i + 1),
                                    updatedAt = tObj.optLong("updatedAt", System.currentTimeMillis())
                                )
                            )
                        }
                    }
                    if (templates.isNotEmpty()) {
                        db.messageTemplateDao().upsertTemplates(templates)
                    }
                }
            }

            // Import Leads from backup or web tracker ("leads", "prospects", "clients", or "pipeline.leads")
            // without duplicating on repeat imports.
            val oldLeadIdToRoomId = importLeadsDeduplicated(db, root)

            // Import LeadEvents without duplicating on repeat imports.
            importLeadEventsDeduplicated(db, root, oldLeadIdToRoomId)

            // Import Payments / Revenue records without duplicating on repeat imports.
            importPaymentsDeduplicated(db, root, oldLeadIdToRoomId)

            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun importLeadsDeduplicated(
        db: AppDatabase,
        root: JSONObject
    ): Map<Long, Long> {
        val candidateArrays = mutableListOf<JSONArray>()
        for (key in listOf("leads", "prospects", "clients", "leadSheet")) {
            root.optJSONArray(key)?.let { candidateArrays.add(it) }
        }
        val pipeAny = root.opt("pipeline")
        if (pipeAny is JSONArray) {
            candidateArrays.add(pipeAny)
        } else if (pipeAny is JSONObject) {
            for (key in listOf("leads", "prospects", "clients", "records")) {
                pipeAny.optJSONArray(key)?.let { candidateArrays.add(it) }
            }
        }

        val idMapping = HashMap<Long, Long>()
        if (candidateArrays.isEmpty()) return idMapping

        val existingLeads = db.leadDao().getAllLeadsDirect().toMutableList()

        for (arr in candidateArrays) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawName = firstNonBlank(
                    obj.optString("name"),
                    obj.optString("business"),
                    obj.optString("businessName"),
                    obj.optString("client"),
                    obj.optString("clientName"),
                    obj.optString("contact")
                )
                if (rawName.isBlank()) continue

                val rawPhone = firstNonBlank(
                    obj.optString("phone"),
                    obj.optString("whatsapp"),
                    obj.optString("phoneNumber"),
                    obj.optString("number")
                )
                val rawSource = firstNonBlank(
                    obj.optString("source"),
                    obj.optString("channel"),
                    obj.optString("platform")
                )
                val rawReferredBy = firstNonBlank(
                    obj.optString("referredBy"),
                    obj.optString("referral"),
                    obj.optString("referrer")
                )
                val rawStage = firstNonBlank(
                    obj.optString("stage"),
                    obj.optString("status")
                )
                val rawService = firstNonBlank(
                    obj.optString("service"),
                    obj.optString("package"),
                    obj.optString("tier"),
                    obj.optString("offer")
                )
                val rawObservation = firstNonBlank(
                    obj.optString("observation"),
                    obj.optString("pain"),
                    obj.optString("hook")
                )
                val rawNotes = firstNonBlank(
                    obj.optString("notes"),
                    obj.optString("note"),
                    obj.optString("nextAction")
                )

                val parsedQuote = parseJsonLongOrCurrency(
                    obj,
                    listOf("quoteAmount", "quote", "proposalAmount", "dealValue", "value")
                )
                val createdAt = parseTimestampOrDate(obj, "createdAt")
                    ?: parseTimestampOrDate(obj, "dateAdded")
                    ?: parseTimestampOrDate(obj, "date")
                    ?: System.currentTimeMillis()
                val lastContactAt = parseTimestampOrDate(obj, "lastContactAt")
                    ?: parseTimestampOrDate(obj, "lastContact")
                val nextActionAt = parseTimestampOrDate(obj, "nextActionAt")
                    ?: parseTimestampOrDate(obj, "followUpDate")
                val nextActionType = obj.optString("nextActionType", "")
                    .takeIf { it.isNotBlank() && it != "null" }

                val normalizedStage = if (rawStage.isBlank()) {
                    LeadCadenceEngine.STAGE_NEW
                } else {
                    LeadCadenceEngine.normalizeStage(rawStage)
                }
                val normalizedService = if (rawService.isBlank()) {
                    LeadCadenceEngine.SERVICE_WEBSITE
                } else {
                    LeadCadenceEngine.normalizeService(rawService)
                }

                val oldJsonId = obj.optLong("id", -1L)
                val existingMatch = findMatchingLead(existingLeads, rawName, rawPhone)

                if (existingMatch != null) {
                    val merged = existingMatch.copy(
                        phone = existingMatch.phone.ifBlank { rawPhone },
                        source = existingMatch.source.ifBlank { rawSource },
                        referredBy = existingMatch.referredBy.ifBlank { rawReferredBy },
                        observation = existingMatch.observation.ifBlank { rawObservation },
                        quoteAmount = existingMatch.quoteAmount ?: parsedQuote,
                        notes = existingMatch.notes.ifBlank { rawNotes }
                    )
                    if (merged != existingMatch) {
                        db.leadDao().updateLead(merged)
                        val idx = existingLeads.indexOfFirst { it.id == existingMatch.id }
                        if (idx >= 0) existingLeads[idx] = merged
                    }
                    if (oldJsonId > 0L) {
                        idMapping[oldJsonId] = existingMatch.id
                    }
                } else {
                    val insertedId = db.leadDao().insertLead(
                        Lead(
                            name = rawName.trim(),
                            phone = rawPhone.trim(),
                            source = rawSource.trim().ifBlank { "Web Tracker Import" },
                            referredBy = rawReferredBy.trim(),
                            stage = normalizedStage,
                            service = normalizedService,
                            observation = rawObservation.trim(),
                            quoteAmount = parsedQuote,
                            notes = rawNotes.trim(),
                            createdAt = createdAt,
                            lastContactAt = lastContactAt,
                            nextActionAt = nextActionAt,
                            nextActionType = nextActionType
                        )
                    )
                    val saved = db.leadDao().getLeadById(insertedId)
                    if (saved != null) {
                        existingLeads.add(saved)
                    }
                    if (oldJsonId > 0L) {
                        idMapping[oldJsonId] = insertedId
                    }
                    db.leadDao().insertEvent(
                        LeadEvent(
                            leadId = insertedId,
                            type = "IMPORTED",
                            text = "Imported lead (${normalizedStage} · ${normalizedService}).",
                            at = createdAt
                        )
                    )
                }
            }
        }
        return idMapping
    }

    private suspend fun importLeadEventsDeduplicated(
        db: AppDatabase,
        root: JSONObject,
        oldLeadIdToRoomId: Map<Long, Long>
    ) {
        val eventsArr = root.optJSONArray("leadEvents") ?: return
        val existingEvents = db.leadDao().getAllEventsDirect()
            .map { "${it.leadId}|${it.type}|${it.at}|${it.text}" }
            .toMutableSet()

        val toInsert = mutableListOf<LeadEvent>()
        for (i in 0 until eventsArr.length()) {
            val eObj = eventsArr.optJSONObject(i) ?: continue
            val rawLeadId = eObj.optLong("leadId", -1L)
            val resolvedLeadId = oldLeadIdToRoomId[rawLeadId] ?: rawLeadId
            if (resolvedLeadId <= 0L) continue
            val type = eObj.optString("type", "NOTE").trim()
            val text = eObj.optString("text", "").trim()
            val at = eObj.optLong("at", System.currentTimeMillis())
            val sig = "$resolvedLeadId|$type|$at|$text"
            if (text.isNotEmpty() && existingEvents.add(sig)) {
                toInsert.add(
                    LeadEvent(
                        leadId = resolvedLeadId,
                        type = type,
                        text = text,
                        at = at
                    )
                )
            }
        }
        if (toInsert.isNotEmpty()) {
            db.leadDao().insertEvents(toInsert)
        }
    }

    private suspend fun importPaymentsDeduplicated(
        db: AppDatabase,
        root: JSONObject,
        oldLeadIdToRoomId: Map<Long, Long>
    ) {
        val candidateArrays = mutableListOf<JSONArray>()
        for (key in listOf("payments", "revenue", "transactions", "income", "cashLog")) {
            root.optJSONArray(key)?.let { candidateArrays.add(it) }
        }
        if (candidateArrays.isEmpty()) return

        val allLeads = db.leadDao().getAllLeadsDirect()
        val existingSignatures = db.paymentDao().getAllPaymentsDirect()
            .map { paymentDedupSignature(it.clientName, it.amount, it.paidAt, it.type) }
            .toMutableSet()

        val newPayments = mutableListOf<Payment>()
        for (arr in candidateArrays) {
            for (i in 0 until arr.length()) {
                val pObj = arr.optJSONObject(i) ?: continue
                val clientName = firstNonBlank(
                    pObj.optString("clientName"),
                    pObj.optString("client"),
                    pObj.optString("name"),
                    pObj.optString("from")
                ).ifBlank { "Client" }

                val amount = parseJsonLongOrCurrency(
                    pObj,
                    listOf("amount", "paid", "value", "revenue")
                ) ?: continue
                if (amount <= 0L) continue

                val rawType = firstNonBlank(
                    pObj.optString("type"),
                    pObj.optString("category"),
                    pObj.optString("service")
                )
                val payType = when (rawType.uppercase(Locale.US)) {
                    "WEBSITE", "RETAINER", "MAINTENANCE", "OTHER" -> rawType.uppercase(Locale.US)
                    else -> MoneyAndLeadParser.inferPaymentType(rawType)
                }

                val paidAt = parseTimestampOrDate(pObj, "paidAt")
                    ?: parseTimestampOrDate(pObj, "date")
                    ?: parseTimestampOrDate(pObj, "timestamp")
                    ?: System.currentTimeMillis()
                val note = firstNonBlank(pObj.optString("note"), pObj.optString("notes"))

                val rawLeadId = if (pObj.has("leadId") && !pObj.isNull("leadId")) pObj.optLong("leadId", -1L) else -1L
                val linkedLeadId = when {
                    rawLeadId > 0L && oldLeadIdToRoomId.containsKey(rawLeadId) -> oldLeadIdToRoomId[rawLeadId]
                    rawLeadId > 0L -> rawLeadId
                    else -> findMatchingLead(allLeads, clientName, "")?.id
                }

                val sig = paymentDedupSignature(clientName, amount, paidAt, payType)
                if (existingSignatures.add(sig)) {
                    newPayments.add(
                        Payment(
                            clientName = clientName.trim(),
                            leadId = linkedLeadId,
                            amount = amount,
                            type = payType,
                            paidAt = paidAt,
                            note = note.trim()
                        )
                    )
                }
            }
        }
        if (newPayments.isNotEmpty()) {
            db.paymentDao().insertPayments(newPayments)
        }
    }

    private fun findMatchingLead(leads: List<Lead>, name: String, phone: String): Lead? {
        val normName = name.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        val normDigits = phone.filter { it.isDigit() }.takeLast(10)

        return leads.firstOrNull { existing ->
            val exName = existing.name.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
            val exDigits = existing.phone.filter { it.isDigit() }.takeLast(10)
            when {
                normDigits.length >= 10 && exDigits == normDigits -> true
                exName.isNotEmpty() && exName == normName -> true
                else -> false
            }
        }
    }

    private fun paymentDedupSignature(clientName: String, amount: Long, paidAt: Long, type: String): String {
        val normClient = clientName.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        val dayBucket = paidAt / 86_400_000L
        return "$normClient|$amount|$dayBucket|${type.uppercase(Locale.US)}"
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (v in values) {
            if (!v.isNullOrBlank() && v.trim() != "null") {
                return v.trim()
            }
        }
        return ""
    }

    private fun parseJsonLongOrCurrency(obj: JSONObject, keys: List<String>): Long? {
        for (k in keys) {
            if (!obj.has(k) || obj.isNull(k)) continue
            val rawAny = obj.opt(k)
            when (rawAny) {
                is Number -> {
                    val v = rawAny.toLong()
                    if (v > 0L) return v
                }
                is String -> {
                    val direct = rawAny.replace(",", "").trim().toLongOrNull()
                    if (direct != null && direct > 0L) return direct
                    val parsed = MoneyAndLeadParser.parseNairaAmount(rawAny)
                    if (parsed != null && parsed > 0L) return parsed
                }
            }
        }
        return null
    }

    private fun parseTimestampOrDate(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val raw = obj.opt(key)
        when (raw) {
            is Number -> {
                val v = raw.toLong()
                if (v > 0L) return if (v < 10_000_000_000L) v * 1000L else v
            }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty() || trimmed == "null") return null
                trimmed.toLongOrNull()?.let { v ->
                    if (v > 0L) return if (v < 10_000_000_000L) v * 1000L else v
                }
                for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.US)
                        val parsed = sdf.parse(trimmed.take(19))
                        if (parsed != null) return parsed.time
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }
}
