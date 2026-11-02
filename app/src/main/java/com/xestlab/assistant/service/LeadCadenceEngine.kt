package com.xestlab.assistant.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.Lead
import com.xestlab.assistant.data.model.LeadEvent
import com.xestlab.assistant.data.model.MessageTemplateEntity
import com.xestlab.assistant.data.model.Payment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

data class DueFollowUpUiModel(
    val lead: Lead,
    val reminderItemId: Long?,
    val stepKey: String,
    val dueAt: Long,
    val displayLine: String
)

data class RevenueGaugeMetrics(
    val ytdCash: Long,
    val yearlyGoal: Long,
    val startDate: String,
    val percentOfGoal: Double,
    val monthlyPace: Long,
    val projectedFinish: Long,
    val proRatedTargetToday: Long,
    val proRatedGapToday: Long,
    val remainingYearGap: Long,
    val websitePrice: Long,
    val retainerPrice: Long,
    val websitesForTodayGap: Int,
    val retainersForTodayGap: Int,
    val websitesForYearGap: Int,
    val retainersForYearGap: Int,
    val todayCash: Long,
    val weekCash: Long,
    val monthCash: Long,
    val monthTarget: Long,
    val monthGap: Long,
    val homeGaugeLine: String
)

object LeadCadenceEngine {

    const val STAGE_NEW = "NEW"
    const val STAGE_CONTACTED = "CONTACTED"
    const val STAGE_REPLIED = "REPLIED"
    const val STAGE_QUOTED = "QUOTED"
    const val STAGE_WON = "WON"
    const val STAGE_LOST = "LOST"

    val ALL_STAGES = listOf(
        STAGE_NEW,
        STAGE_CONTACTED,
        STAGE_REPLIED,
        STAGE_QUOTED,
        STAGE_WON,
        STAGE_LOST
    )

    const val SERVICE_WEBSITE = "WEBSITE"
    const val SERVICE_SOCIAL = "SOCIAL"
    const val SERVICE_OTHER = "OTHER"

    val ALL_SERVICES = listOf(SERVICE_WEBSITE, SERVICE_SOCIAL, SERVICE_OTHER)

    const val STEP_DAY_0_MESSAGE = "DAY_0_MESSAGE"
    const val STEP_DAY_2_CALL_OR_VN = "DAY_2_CALL_OR_VN"
    const val STEP_DAY_5_EXAMPLE = "DAY_5_EXAMPLE"
    const val STEP_DAY_10_CLOSEOUT = "DAY_10_CLOSEOUT"
    const val STEP_SEND_QUOTE = "SEND_QUOTE"
    const val STEP_QUOTE_DAY_2 = "QUOTE_DAY_2"
    const val STEP_QUOTE_DAY_5 = "QUOTE_DAY_5"

    const val PAYMENT_WEBSITE = "WEBSITE"
    const val PAYMENT_RETAINER = "RETAINER"
    const val PAYMENT_MAINTENANCE = "MAINTENANCE"
    const val PAYMENT_OTHER = "OTHER"

    val ALL_PAYMENT_TYPES = listOf(
        PAYMENT_WEBSITE,
        PAYMENT_RETAINER,
        PAYMENT_MAINTENANCE,
        PAYMENT_OTHER
    )

    private const val STALE_THRESHOLD_MS = 7L * 86_400_000L

    fun defaultTemplates(now: Long = System.currentTimeMillis()): List<MessageTemplateEntity> = listOf(
        MessageTemplateEntity(
            stepKey = STEP_DAY_0_MESSAGE,
            title = "Day 0 — First Outreach Message",
            bodyTemplate = "Hi {name}, I noticed {observation}. I help businesses upgrade their {service} so more visitors turn into paying clients. Open to a quick 2-minute idea?",
            orderIndex = 1,
            updatedAt = now
        ),
        MessageTemplateEntity(
            stepKey = STEP_DAY_2_CALL_OR_VN,
            title = "Day 2 — Call or Voice Note with Tip",
            bodyTemplate = "Hi {name}, quick follow-up on my note about {observation}. Dropping a short 30-second tip you can apply to your {service} right away — let me know if I can send a quick voice note here.",
            orderIndex = 2,
            updatedAt = now
        ),
        MessageTemplateEntity(
            stepKey = STEP_DAY_5_EXAMPLE,
            title = "Day 5 — Past Work Example",
            bodyTemplate = "Hi {name}, wanted to share a recent {service} example we built for a business like yours. Happy to drop the live link here if you'd like a quick look.",
            orderIndex = 3,
            updatedAt = now
        ),
        MessageTemplateEntity(
            stepKey = STEP_DAY_10_CLOSEOUT,
            title = "Day 10 — Polite Close-Out",
            bodyTemplate = "Hi {name}, I won't crowd your messages — pausing follow-ups for now. Whenever upgrading your {service} is on your radar, just reply here and we'll pick right up. Wishing you a great week!",
            orderIndex = 4,
            updatedAt = now
        ),
        MessageTemplateEntity(
            stepKey = STEP_SEND_QUOTE,
            title = "Replied — Send Quote (within 24h)",
            bodyTemplate = "Hi {name}, thanks for getting back to me! Here is the {service} quote ({quoteAmount}) tailored around {observation}. Let me know if you'd like to kick off this week.",
            orderIndex = 5,
            updatedAt = now
        ),
        MessageTemplateEntity(
            stepKey = STEP_QUOTE_DAY_2,
            title = "Quoted Day 2 — Quote Follow-Up",
            bodyTemplate = "Hi {name}, checking in on the {service} quote ({quoteAmount}) I sent over. Do you have any questions on the deliverables or timeline?",
            orderIndex = 6,
            updatedAt = now
        ),
        MessageTemplateEntity(
            stepKey = STEP_QUOTE_DAY_5,
            title = "Quoted Day 5 — Final Slot Check",
            bodyTemplate = "Hi {name}, quick follow-up on your {service} quote ({quoteAmount}) as I'm confirming our project schedule for next week. Would you like me to hold a build slot for you?",
            orderIndex = 7,
            updatedAt = now
        )
    )

    fun buildDueSentence(leadName: String, stepKey: String?): String {
        val cleanName = leadName.trim().ifBlank { "Lead" }
        return when (stepKey) {
            STEP_DAY_0_MESSAGE -> "$cleanName is due a day 0 message."
            STEP_DAY_2_CALL_OR_VN -> "$cleanName is due a day 2 voice note."
            STEP_DAY_5_EXAMPLE -> "$cleanName is due a day 5 example."
            STEP_DAY_10_CLOSEOUT -> "$cleanName is due a day 10 polite close-out."
            STEP_SEND_QUOTE -> "$cleanName is due a quote within 24 hours."
            STEP_QUOTE_DAY_2 -> "$cleanName is due a day 2 quote follow-up."
            STEP_QUOTE_DAY_5 -> "$cleanName is due a day 5 quote follow-up."
            else -> "$cleanName is due a follow-up."
        }
    }

    fun stepShortLabel(stepKey: String?): String {
        return when (stepKey) {
            STEP_DAY_0_MESSAGE -> "Day 0 message"
            STEP_DAY_2_CALL_OR_VN -> "Day 2 call / voice note"
            STEP_DAY_5_EXAMPLE -> "Day 5 example"
            STEP_DAY_10_CLOSEOUT -> "Day 10 close-out"
            STEP_SEND_QUOTE -> "Quote (24h)"
            STEP_QUOTE_DAY_2 -> "Quote Day 2 follow-up"
            STEP_QUOTE_DAY_5 -> "Quote Day 5 follow-up"
            else -> stepKey?.replace('_', ' ')?.lowercase(Locale.getDefault()) ?: "None"
        }
    }

    fun makeFollowUpSourceText(leadId: Long, stepKey: String): String =
        "lead_followup:$leadId:$stepKey"

    fun parseFollowUpSourceText(sourceText: String): Pair<Long, String>? {
        if (!sourceText.startsWith("lead_followup:")) return null
        val parts = sourceText.split(":")
        if (parts.size < 3) return null
        val leadId = parts[1].toLongOrNull() ?: return null
        val stepKey = parts[2]
        return leadId to stepKey
    }

    fun normalizeService(raw: String): String {
        val upper = raw.trim().uppercase(Locale.US)
        return when {
            upper.contains("WEB") || upper.contains("SITE") || upper.contains("LANDING") || upper.contains("ECOM") -> SERVICE_WEBSITE
            upper.contains("SOC") || upper.contains("INSTA") || upper.contains("IG") || upper.contains("TIKTOK") || upper.contains("CONTENT") -> SERVICE_SOCIAL
            upper in ALL_SERVICES -> upper
            else -> SERVICE_OTHER
        }
    }

    fun normalizeStage(raw: String): String {
        val upper = raw.trim().uppercase(Locale.US)
        return when {
            upper in ALL_STAGES -> upper
            upper.contains("WON") || upper.contains("CLOSED WON") || upper.contains("PAID") -> STAGE_WON
            upper.contains("LOST") || upper.contains("DEAD") || upper.contains("DECLINED") -> STAGE_LOST
            upper.contains("QUOT") || upper.contains("PROPOS") -> STAGE_QUOTED
            upper.contains("REPL") || upper.contains("RESPOND") || upper.contains("WARM") || upper.contains("CONV") -> STAGE_REPLIED
            upper.contains("CONTACT") || upper.contains("SENT") || upper.contains("OUTREACH") -> STAGE_CONTACTED
            else -> STAGE_NEW
        }
    }

    fun serviceReadableLabel(service: String): String {
        return when (service.uppercase(Locale.US)) {
            SERVICE_WEBSITE -> "website"
            SERVICE_SOCIAL -> "social media page"
            else -> "digital setup"
        }
    }

    /**
     * Renders the offline draft from Room's editable MessageTemplateEntity.
     * Enforces that DAY_0_MESSAGE always includes the lead's observation.
     */
    suspend fun buildOfflineDraft(
        db: AppDatabase,
        lead: Lead,
        stepKey: String?
    ): String {
        val effectiveStep = stepKey ?: when (lead.stage) {
            STAGE_NEW, STAGE_CONTACTED -> STEP_DAY_0_MESSAGE
            STAGE_REPLIED -> STEP_SEND_QUOTE
            STAGE_QUOTED -> STEP_QUOTE_DAY_2
            else -> STEP_DAY_0_MESSAGE
        }

        val templateEntity = db.messageTemplateDao().getTemplateDirect(effectiveStep)
            ?: defaultTemplates().firstOrNull { it.stepKey == effectiveStep }
            ?: defaultTemplates().first()

        val firstName = lead.name.trim().split(Regex("\\s+")).firstOrNull()?.ifBlank { lead.name } ?: "there"
        val serviceText = serviceReadableLabel(lead.service)
        val observationClean = lead.observation.trim().ifBlank {
            "your current ${serviceReadableLabel(lead.service)} setup"
        }
        val quoteFormatted = lead.quoteAmount?.let { "₦${formatWithCommas(it)}" } ?: "your custom package"

        var rendered = templateEntity.bodyTemplate
            .replace("{name}", firstName, ignoreCase = true)
            .replace("{service}", serviceText, ignoreCase = true)
            .replace("{observation}", observationClean, ignoreCase = true)
            .replace("{quoteAmount}", quoteFormatted, ignoreCase = true)
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        // Enforce first-message rule: DAY_0_MESSAGE must contain the observation
        if (effectiveStep == STEP_DAY_0_MESSAGE && lead.observation.isNotBlank() &&
            !rendered.contains(lead.observation.trim(), ignoreCase = true)
        ) {
            rendered = "Hi $firstName, I noticed ${lead.observation.trim()}. $rendered"
        }
        return rendered
    }

    /**
     * Builds the draft for a lead step: uses Groq AI rewrite in a short friendly Nigerian business tone
     * when AI is enabled, falling back cleanly to the offline template if offline or unreachable.
     */
    suspend fun buildDraftForLead(
        db: AppDatabase,
        securePrefs: SecurePreferences,
        lead: Lead,
        stepKey: String?,
        allowAiRewrite: Boolean = true
    ): String {
        val effectiveStep = stepKey ?: lead.nextActionType ?: STEP_DAY_0_MESSAGE
        val offlineDraft = buildOfflineDraft(db, lead, effectiveStep)
        if (!allowAiRewrite || !securePrefs.isAiEnabled || !securePrefs.hasGroqKey()) {
            return offlineDraft
        }
        val rewritten = AiClient.rewriteLeadDraftViaGroq(
            lead = lead,
            stepKey = effectiveStep,
            offlineDraft = offlineDraft,
            securePrefs = securePrefs
        )
        return rewritten?.takeIf { it.isNotBlank() } ?: offlineDraft
    }

    /**
     * Cancels all pending reminder items & exact alarms for a lead.
     */
    suspend fun cancelAllPendingForLead(
        context: Context,
        db: AppDatabase,
        leadId: Long
    ) {
        val pending = db.itemDao().getPendingLeadFollowUpsDirect(leadId)
        pending.forEach { item ->
            AlarmScheduler.cancelItemReminder(context, item.id)
            db.itemDao().updateStatus(item.id, "DROPPED")
        }
    }

    /**
     * Marks a lead CONTACTED and schedules:
     * - Day 0 message
     * - Day 2 call or voice note with a tip
     * - Day 5 example
     * - Day 10 polite close-out, then stop.
     * Each step is a reminder Item with an exact alarm.
     */
    suspend fun markLeadContacted(
        context: Context,
        db: AppDatabase,
        leadId: Long,
        day0AlreadySent: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val lead = db.leadDao().getLeadById(leadId) ?: return
        cancelAllPendingForLead(context, db, leadId)

        val stepsToSchedule = buildList {
            if (!day0AlreadySent) {
                add(STEP_DAY_0_MESSAGE to (nowMillis + 60_000L))
            }
            add(STEP_DAY_2_CALL_OR_VN to timeInDaysAtHour(nowMillis, daysAhead = 2, hour = 9, minute = 0))
            add(STEP_DAY_5_EXAMPLE to timeInDaysAtHour(nowMillis, daysAhead = 5, hour = 9, minute = 0))
            add(STEP_DAY_10_CLOSEOUT to timeInDaysAtHour(nowMillis, daysAhead = 10, hour = 9, minute = 0))
        }

        scheduleCadenceItems(context, db, lead, stepsToSchedule)

        if (day0AlreadySent) {
            db.leadDao().insertEvent(
                LeadEvent(
                    leadId = leadId,
                    type = "MESSAGE_SENT",
                    text = "Sent Day 0 intro message.",
                    at = nowMillis
                )
            )
        }
        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = "CONTACTED",
                text = "Marked CONTACTED — scheduled Day 0, Day 2, Day 5, and Day 10 follow-up cadence.",
                at = nowMillis
            )
        )

        syncLeadNextAction(
            db = db,
            leadId = leadId,
            newStage = STAGE_CONTACTED,
            lastContactAt = nowMillis
        )
        AlarmScheduler.syncAlarmTelemetry(context)
    }

    /**
     * When a lead replies: pause the outreach cadence and schedule a QUOTE task within 24 hours.
     */
    suspend fun markLeadReplied(
        context: Context,
        db: AppDatabase,
        leadId: Long,
        note: String = "",
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val lead = db.leadDao().getLeadById(leadId) ?: return
        cancelAllPendingForLead(context, db, leadId)

        val tomorrowMorning = timeInDaysAtHour(nowMillis, daysAhead = 1, hour = 10, minute = 0)
        val within24h = minOf(nowMillis + 23L * 3_600_000L, tomorrowMorning)
            .coerceAtLeast(nowMillis + 15 * 60_000L)

        scheduleCadenceItems(
            context = context,
            db = db,
            lead = lead,
            steps = listOf(STEP_SEND_QUOTE to within24h)
        )

        val detail = if (note.isNotBlank()) {
            "Lead replied ($note) — cadence paused, quote scheduled within 24h."
        } else {
            "Lead replied — cadence paused, quote scheduled within 24h."
        }
        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = "REPLIED",
                text = detail,
                at = nowMillis
            )
        )

        syncLeadNextAction(
            db = db,
            leadId = leadId,
            newStage = STAGE_REPLIED,
            lastContactAt = nowMillis
        )
        AlarmScheduler.syncAlarmTelemetry(context)
    }

    /**
     * When QUOTED: record quoteAmount and schedule follow-ups on Day 2 and Day 5.
     */
    suspend fun markLeadQuoted(
        context: Context,
        db: AppDatabase,
        leadId: Long,
        quoteAmount: Long?,
        note: String = "",
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val lead = db.leadDao().getLeadById(leadId) ?: return
        cancelAllPendingForLead(context, db, leadId)

        val updatedQuote = quoteAmount ?: lead.quoteAmount
        val leadWithQuote = lead.copy(quoteAmount = updatedQuote, stage = STAGE_QUOTED)
        db.leadDao().updateLead(leadWithQuote)

        val steps = listOf(
            STEP_QUOTE_DAY_2 to timeInDaysAtHour(nowMillis, daysAhead = 2, hour = 9, minute = 30),
            STEP_QUOTE_DAY_5 to timeInDaysAtHour(nowMillis, daysAhead = 5, hour = 9, minute = 30)
        )
        scheduleCadenceItems(context, db, leadWithQuote, steps)

        val amountText = updatedQuote?.let { "₦${formatWithCommas(it)}" } ?: "custom quote"
        val extraNote = if (note.isNotBlank()) " ($note)" else ""
        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = "QUOTED",
                text = "Sent quote: $amountText$extraNote — scheduled Day 2 & Day 5 follow-ups.",
                at = nowMillis
            )
        )

        syncLeadNextAction(
            db = db,
            leadId = leadId,
            newStage = STAGE_QUOTED,
            lastContactAt = nowMillis,
            overrideQuoteAmount = updatedQuote
        )
        AlarmScheduler.syncAlarmTelemetry(context)
    }

    /**
     * When WON or LOST: cancel everything for that lead.
     */
    suspend fun markLeadClosed(
        context: Context,
        db: AppDatabase,
        leadId: Long,
        won: Boolean,
        note: String = "",
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val lead = db.leadDao().getLeadById(leadId) ?: return
        cancelAllPendingForLead(context, db, leadId)

        val targetStage = if (won) STAGE_WON else STAGE_LOST
        val eventLabel = if (won) {
            "Marked WON${if (note.isNotBlank()) " — $note" else ""}. Cancelled all remaining follow-up alarms."
        } else {
            "Marked LOST${if (note.isNotBlank()) " — $note" else ""}. Cancelled all remaining follow-up alarms."
        }

        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = targetStage,
                text = eventLabel,
                at = nowMillis
            )
        )

        db.leadDao().updateLead(
            lead.copy(
                stage = targetStage,
                lastContactAt = nowMillis,
                nextActionAt = null,
                nextActionType = null
            )
        )
        AlarmScheduler.syncAlarmTelemetry(context)
    }

    /**
     * Marks a due follow-up step done, logs MESSAGE_SENT / FOLLOWUP_DONE, and advances nextActionAt
     * to the next scheduled cadence step (stopping automatically after Day 10 / Day 5).
     */
    suspend fun completeDueFollowUpStep(
        context: Context,
        db: AppDatabase,
        leadId: Long,
        reminderItemId: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val lead = db.leadDao().getLeadById(leadId) ?: return
        val pending = db.itemDao().getPendingLeadFollowUpsDirect(leadId)
        val targetItem = if (reminderItemId != null) {
            pending.firstOrNull { it.id == reminderItemId } ?: pending.firstOrNull()
        } else {
            pending.firstOrNull()
        }

        val completedStepKey = targetItem?.let { parseFollowUpSourceText(it.sourceText)?.second }
            ?: lead.nextActionType

        if (targetItem != null) {
            AlarmScheduler.cancelItemReminder(context, targetItem.id)
            db.itemDao().updateStatus(targetItem.id, "COMPLETED")
        }

        val eventType = if (completedStepKey == STEP_SEND_QUOTE) "QUOTED" else "MESSAGE_SENT"
        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = eventType,
                text = "Completed ${stepShortLabel(completedStepKey)}.",
                at = nowMillis
            )
        )

        syncLeadNextAction(
            db = db,
            leadId = leadId,
            newStage = if (lead.stage == STAGE_NEW) STAGE_CONTACTED else lead.stage,
            lastContactAt = nowMillis
        )
        AlarmScheduler.syncAlarmTelemetry(context)
    }

    /**
     * Snoozes the current due follow-up step to tomorrow 9:00 AM and reschedules its exact alarm.
     */
    suspend fun snoozeDueFollowUpStep(
        context: Context,
        db: AppDatabase,
        leadId: Long,
        reminderItemId: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val lead = db.leadDao().getLeadById(leadId) ?: return
        val pending = db.itemDao().getPendingLeadFollowUpsDirect(leadId)
        val targetItem = if (reminderItemId != null) {
            pending.firstOrNull { it.id == reminderItemId } ?: pending.firstOrNull()
        } else {
            pending.firstOrNull()
        }

        val snoozedTo = timeInDaysAtHour(nowMillis, daysAhead = 1, hour = 9, minute = 0)
        if (targetItem != null) {
            AlarmScheduler.cancelItemReminder(context, targetItem.id)
            val updatedItem = targetItem.copy(dueAt = snoozedTo)
            db.itemDao().updateItem(updatedItem)
            AlarmScheduler.scheduleItemReminder(context, updatedItem)
        } else {
            val stepKey = lead.nextActionType ?: STEP_DAY_0_MESSAGE
            scheduleCadenceItems(context, db, lead, listOf(stepKey to snoozedTo))
        }

        db.leadDao().insertEvent(
            LeadEvent(
                leadId = leadId,
                type = "SNOOZED",
                text = "Snoozed follow-up to tomorrow 9:00 AM.",
                at = nowMillis
            )
        )

        syncLeadNextAction(
            db = db,
            leadId = leadId,
            newStage = lead.stage,
            lastContactAt = lead.lastContactAt
        )
        AlarmScheduler.syncAlarmTelemetry(context)
    }

    private suspend fun scheduleCadenceItems(
        context: Context,
        db: AppDatabase,
        lead: Lead,
        steps: List<Pair<String, Long>>
    ) {
        steps.forEach { (stepKey, dueMillis) ->
            val sentence = buildDueSentence(lead.name, stepKey)
            val item = Item(
                type = "REMINDER",
                title = sentence,
                details = "Lead (${lead.service}): ${lead.observation.ifBlank { lead.notes }}".trim(),
                dueAt = dueMillis,
                priority = "HIGH",
                status = "PENDING",
                contactName = lead.name,
                sourceText = makeFollowUpSourceText(lead.id, stepKey)
            )
            val id = db.itemDao().insertItem(item)
            val saved = db.itemDao().getItemById(id)
            if (saved != null) {
                AlarmScheduler.scheduleItemReminder(context, saved)
            }
        }
    }

    suspend fun syncLeadNextAction(
        db: AppDatabase,
        leadId: Long,
        newStage: String? = null,
        lastContactAt: Long? = null,
        overrideQuoteAmount: Long? = null
    ) {
        val current = db.leadDao().getLeadById(leadId) ?: return
        val remaining = db.itemDao().getPendingLeadFollowUpsDirect(leadId)
            .sortedBy { it.dueAt ?: Long.MAX_VALUE }
        val nextItem = remaining.firstOrNull()
        val nextStepKey = nextItem?.let { parseFollowUpSourceText(it.sourceText)?.second }

        db.leadDao().updateLead(
            current.copy(
                stage = newStage ?: current.stage,
                quoteAmount = overrideQuoteAmount ?: current.quoteAmount,
                lastContactAt = lastContactAt ?: current.lastContactAt,
                nextActionAt = nextItem?.dueAt,
                nextActionType = nextStepKey
            )
        )
    }

    /**
     * Collects all follow-ups due today (or overdue) across active leads.
     */
    fun computeDueFollowUpsToday(
        leads: List<Lead>,
        pendingItems: List<Item>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<DueFollowUpUiModel> {
        val endOfToday = endOfDayMillis(nowMillis)
        val activeLeadMap = leads
            .filter { it.stage != STAGE_WON && it.stage != STAGE_LOST }
            .associateBy { it.id }

        val followUpItemsByLead = HashMap<Long, Item>()
        pendingItems.forEach { item ->
            val parsed = parseFollowUpSourceText(item.sourceText) ?: return@forEach
            val (leadId, _) = parsed
            val due = item.dueAt ?: return@forEach
            if (due <= endOfToday && activeLeadMap.containsKey(leadId)) {
                val existing = followUpItemsByLead[leadId]
                if (existing == null || (existing.dueAt ?: Long.MAX_VALUE) > due) {
                    followUpItemsByLead[leadId] = item
                }
            }
        }

        val result = mutableListOf<DueFollowUpUiModel>()
        activeLeadMap.values.forEach { lead ->
            val matchedItem = followUpItemsByLead[lead.id]
            if (matchedItem != null) {
                val stepKey = parseFollowUpSourceText(matchedItem.sourceText)?.second
                    ?: lead.nextActionType
                    ?: STEP_DAY_0_MESSAGE
                result.add(
                    DueFollowUpUiModel(
                        lead = lead,
                        reminderItemId = matchedItem.id,
                        stepKey = stepKey,
                        dueAt = matchedItem.dueAt ?: nowMillis,
                        displayLine = buildDueSentence(lead.name, stepKey)
                    )
                )
            } else if (lead.nextActionAt != null && lead.nextActionAt <= endOfToday) {
                val stepKey = lead.nextActionType ?: STEP_DAY_0_MESSAGE
                result.add(
                    DueFollowUpUiModel(
                        lead = lead,
                        reminderItemId = null,
                        stepKey = stepKey,
                        dueAt = lead.nextActionAt,
                        displayLine = buildDueSentence(lead.name, stepKey)
                    )
                )
            }
        }

        return result.sortedBy { it.dueAt }
    }

    /**
     * Active leads (not WON/LOST) with no nextActionAt for more than 7 days.
     */
    fun computeNeedsAttentionLeads(
        leads: List<Lead>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<Lead> {
        return leads.filter { lead ->
            if (lead.stage == STAGE_WON || lead.stage == STAGE_LOST) return@filter false
            val referenceTime = lead.lastContactAt ?: lead.createdAt
            val noNextActionTooLong = lead.nextActionAt == null && (nowMillis - referenceTime) > STALE_THRESHOLD_MS
            val overdueTooLong = lead.nextActionAt != null && (nowMillis - lead.nextActionAt) > STALE_THRESHOLD_MS
            noNextActionTooLong || overdueTooLong
        }
    }

    /**
     * Normalizes a Nigerian or international phone number to digits-only format for https://wa.me/ links.
     */
    fun normalizePhoneForWhatsApp(rawPhone: String): String {
        val trimmed = rawPhone.trim()
        if (trimmed.isEmpty()) return ""
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return when {
            digits.length == 11 && digits.startsWith("0") -> "234${digits.substring(1)}"
            digits.length == 10 && (digits.startsWith("7") || digits.startsWith("8") || digits.startsWith("9")) -> "234$digits"
            else -> digits
        }
    }

    /**
     * Opens WhatsApp chat for the lead's phone number with the draft pre-filled in the text box.
     * NEVER sends any message automatically — user taps Send inside WhatsApp.
     */
    fun openWhatsAppWithDraft(
        context: Context,
        phone: String,
        draft: String
    ): Boolean {
        val waDigits = normalizePhoneForWhatsApp(phone)
        val encodedText = Uri.encode(draft)
        val url = if (waDigits.isNotEmpty()) {
            "https://wa.me/$waDigits?text=$encodedText"
        } else {
            "https://wa.me/?text=$encodedText"
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens the phone dialer with the lead's number pre-filled (never auto-dials without user confirmation).
     */
    fun openDialerForLead(context: Context, phone: String): Boolean {
        val clean = phone.trim()
        if (clean.isEmpty()) return false
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Computes the 10M Revenue Gauge, pro-rated target for today, gaps, and websites/retainers needed.
     */
    fun computeRevenueGauge(
        goal: Goal,
        payments: List<Payment>,
        nowMillis: Long = System.currentTimeMillis()
    ): RevenueGaugeMetrics {
        val yearlyGoal = goal.yearlyGoal.coerceAtLeast(1L)
        val websitePrice = goal.websitePrice.coerceAtLeast(1L)
        val retainerPrice = goal.retainerPrice.coerceAtLeast(1L)
        val monthTarget = if (goal.monthlyTarget > 0L) goal.monthlyTarget else (yearlyGoal / 12L).coerceAtLeast(1L)

        val parsedCycleStart = parseDateStartMillis(goal.startDate)
        val startOfCurrentYear = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val effectiveStartMillis = when {
            parsedCycleStart == null -> startOfCurrentYear
            nowMillis >= parsedCycleStart -> parsedCycleStart
            else -> minOf(parsedCycleStart, startOfCurrentYear)
        }

        val ytdCash = payments
            .filter { it.paidAt >= effectiveStartMillis }
            .sumOf { it.amount }

        val elapsedDays = (((nowMillis - effectiveStartMillis).coerceAtLeast(0L).toDouble()) / 86_400_000.0 + 1.0)
            .coerceIn(1.0, 365.0)

        val percentOfGoal = (ytdCash.toDouble() / yearlyGoal.toDouble()) * 100.0
        val dailyRunRate = ytdCash.toDouble() / elapsedDays
        val monthlyPace = (dailyRunRate * (365.0 / 12.0)).toLong()
        val projectedFinish = (dailyRunRate * 365.0).toLong()

        val proRatedTargetToday = ((yearlyGoal.toDouble() * elapsedDays) / 365.0)
            .toLong()
            .coerceIn(0L, yearlyGoal)
        val proRatedGapToday = (proRatedTargetToday - ytdCash).coerceAtLeast(0L)
        val remainingYearGap = (yearlyGoal - ytdCash).coerceAtLeast(0L)

        val websitesForTodayGap = if (proRatedGapToday <= 0L) 0 else ceil(proRatedGapToday.toDouble() / websitePrice.toDouble()).toInt()
        val retainersForTodayGap = if (proRatedGapToday <= 0L) 0 else ceil(proRatedGapToday.toDouble() / retainerPrice.toDouble()).toInt()
        val websitesForYearGap = if (remainingYearGap <= 0L) 0 else ceil(remainingYearGap.toDouble() / websitePrice.toDouble()).toInt()
        val retainersForYearGap = if (remainingYearGap <= 0L) 0 else ceil(remainingYearGap.toDouble() / retainerPrice.toDouble()).toInt()

        val todayStart = startOfDayMillis(nowMillis)
        val todayEnd = endOfDayMillis(nowMillis)
        val weekStart = nowMillis - 6L * 86_400_000L
        val monthStart = startOfMonthMillis(nowMillis)

        val todayCash = payments.filter { it.paidAt in todayStart..todayEnd }.sumOf { it.amount }
        val weekCash = payments.filter { it.paidAt >= startOfDayMillis(weekStart) }.sumOf { it.amount }
        val monthCash = payments.filter { it.paidAt >= monthStart }.sumOf { it.amount }
        val monthGap = (monthTarget - monthCash).coerceAtLeast(0L)

        val pctStr = if (percentOfGoal % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", percentOfGoal)
        } else {
            String.format(Locale.US, "%.1f", percentOfGoal)
        }
        val goalCompact = formatGoalCompact(yearlyGoal)
        val homeLine = "Year to date ${formatCompactNaira(ytdCash)} of $goalCompact ($pctStr percent). Pace ${formatCompactNaira(monthlyPace)} a month. On this pace you finish at ${formatCompactNaira(projectedFinish)}."

        return RevenueGaugeMetrics(
            ytdCash = ytdCash,
            yearlyGoal = yearlyGoal,
            startDate = goal.startDate,
            percentOfGoal = percentOfGoal,
            monthlyPace = monthlyPace,
            projectedFinish = projectedFinish,
            proRatedTargetToday = proRatedTargetToday,
            proRatedGapToday = proRatedGapToday,
            remainingYearGap = remainingYearGap,
            websitePrice = websitePrice,
            retainerPrice = retainerPrice,
            websitesForTodayGap = websitesForTodayGap,
            retainersForTodayGap = retainersForTodayGap,
            websitesForYearGap = websitesForYearGap,
            retainersForYearGap = retainersForYearGap,
            todayCash = todayCash,
            weekCash = weekCash,
            monthCash = monthCash,
            monthTarget = monthTarget,
            monthGap = monthGap,
            homeGaugeLine = homeLine
        )
    }

    fun formatWithCommas(amount: Long): String =
        String.format(Locale.US, "%,d", amount)

    fun formatCompactNaira(amount: Long): String {
        val absVal = kotlin.math.abs(amount)
        return when {
            absVal >= 1_000_000L -> {
                val m = amount / 1_000_000.0
                val formatted = if (m % 1.0 == 0.0) {
                    String.format(Locale.US, "%.0fM", m)
                } else {
                    String.format(Locale.US, "%.2fM", m).trimEnd('0').trimEnd('.') +
                        if (String.format(Locale.US, "%.2f", m).endsWith("00")) "" else ""
                }
                // Clean e.g. 1.50M -> 1.5M
                val cleanM = String.format(Locale.US, "%.2f", m).trimEnd('0').trimEnd('.')
                "₦${cleanM}M"
            }
            absVal >= 1_000L -> {
                val k = amount / 1_000.0
                val cleanK = String.format(Locale.US, "%.1f", k).trimEnd('0').trimEnd('.')
                "₦${cleanK}k"
            }
            else -> "₦$amount"
        }
    }

    private fun formatGoalCompact(goal: Long): String {
        return if (goal == 10_000_000L) {
            "10M"
        } else {
            formatCompactNaira(goal)
        }
    }

    private fun parseDateStartMillis(yyyyMmDd: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            sdf.parse(yyyyMmDd.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    fun timeInDaysAtHour(baseMillis: Long, daysAhead: Int, hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            timeInMillis = baseMillis
            add(Calendar.DAY_OF_YEAR, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun startOfDayMillis(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun endOfDayMillis(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    fun startOfMonthMillis(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
