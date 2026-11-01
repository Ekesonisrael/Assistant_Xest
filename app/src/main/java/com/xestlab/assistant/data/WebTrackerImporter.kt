package com.xestlab.assistant.data

import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.DailyLog
import com.xestlab.assistant.data.model.Goal
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.model.UserSettings
import org.json.JSONArray
import org.json.JSONObject

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

    /**
     * Imports data from web goal tracker (or native assistant) JSON export.
     * Fully idempotent: will not insert duplicate records.
     */
    suspend fun importData(db: AppDatabase, jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)

            // 1. Settings & Goals
            if (root.has("settings")) {
                val sObj = root.getJSONObject("settings")
                val curGoal = db.goalDao().getGoalsDirect() ?: Goal()
                val curSettings = db.settingsDao().getSettingsDirect() ?: UserSettings()

                val yGoal = sObj.optLong("yearGoal", curGoal.yearlyGoal)
                val sDate = sObj.optString("startDate", curGoal.startDate)

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
                        q4Target = q4
                    )
                )

                val name = sObj.optString("userName", curSettings.userName)
                val qStart = sObj.optString("quietHoursStart", curSettings.quietHoursStart)
                val qEnd = sObj.optString("quietHoursEnd", curSettings.quietHoursEnd)
                val qEnabled = sObj.optBoolean("quietHoursEnabled", curSettings.quietHoursEnabled)
                val vSpeed = sObj.optDouble("voiceSpeed", curSettings.voiceSpeed.toDouble()).toFloat()

                db.settingsDao().saveSettings(
                    curSettings.copy(
                        userName = name,
                        quietHoursStart = qStart,
                        quietHoursEnd = qEnd,
                        quietHoursEnabled = qEnabled,
                        voiceSpeed = vSpeed
                    )
                )
            }

            // 2. Daily logs (days or logs)
            if (root.has("days")) {
                val daysObj = root.getJSONObject("days")
                val keys = daysObj.keys()
                val existingLogs = db.dailyLogDao().getAllLogs().associateBy { "${it.date}_${it.itemId}" }
                val newLogs = mutableListOf<DailyLog>()

                while (keys.hasNext()) {
                    val dateKey = keys.next()
                    val dayData = daysObj.getJSONObject(dateKey)
                    val msgs = dayData.optInt("messagesSent", 0)
                    if (msgs > 0 && !existingLogs.containsKey("${dateKey}_messages10")) {
                        newLogs.add(DailyLog(date = dateKey, itemId = "messages10", isDone = true, countValue = msgs))
                    }
                    if (dayData.has("checklist")) {
                        val cl = dayData.getJSONObject("checklist")
                        val checkMap = listOf("followups", "content", "study", "leadsheet")
                        for (itemKey in checkMap) {
                            if (cl.optBoolean(itemKey, false) && !existingLogs.containsKey("${dateKey}_$itemKey")) {
                                newLogs.add(DailyLog(date = dateKey, itemId = itemKey, isDone = true))
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

            // 3. Pipeline checklist -> Generic Items
            if (root.has("pipeline")) {
                val pipeObj = root.getJSONObject("pipeline")
                val existingItems = db.itemDao().getAllItemsDirect().associateBy { it.title }
                val newItems = mutableListOf<Item>()

                PIPELINE_DEFINITIONS.forEach { (stageKey, itemTitles) ->
                    if (pipeObj.has(stageKey)) {
                        val arr = pipeObj.getJSONArray(stageKey)
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

            // 4. Months milestones -> Generic Items (type = GOAL)
            if (root.has("months")) {
                val monthsObj = root.getJSONObject("months")
                val keys = monthsObj.keys()
                val existingItems = db.itemDao().getAllItemsDirect().associateBy { it.title }
                val newGoals = mutableListOf<Item>()

                while (keys.hasNext()) {
                    val mKey = keys.next()
                    val mData = monthsObj.getJSONObject(mKey)
                    val targetRev = mData.optDouble("revenueTarget", 0.0)
                    val actualRev = mData.optDouble("revenueActual", 0.0)
                    val title = "Monthly Revenue Target: $mKey"

                    if (!existingItems.containsKey(title) && targetRev > 0) {
                        newGoals.add(
                            Item(
                                type = "GOAL",
                                title = title,
                                details = "Target: ₦$targetRev",
                                targetAmount = targetRev,
                                unit = "naira",
                                progress = actualRev,
                                status = if (actualRev >= targetRev && targetRev > 0) "COMPLETED" else "PENDING",
                                sourceText = "web_month_$mKey"
                            )
                        )
                    }
                }
                if (newGoals.isNotEmpty()) {
                    db.itemDao().insertItems(newGoals)
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
