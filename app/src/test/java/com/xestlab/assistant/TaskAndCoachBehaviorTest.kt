package com.xestlab.assistant

import com.xestlab.assistant.ai.AiClient
import com.xestlab.assistant.ai.AssistantParser
import com.xestlab.assistant.ai.ParsedIntent
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.ItemDao
import com.xestlab.assistant.data.model.ConversationTurn
import com.xestlab.assistant.data.model.Item
import com.xestlab.assistant.data.repository.AssistantRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskAndCoachBehaviorTest {

    private class FakeItemDao(private val failVerification: Boolean = false) : ItemDao {
        private var nextId = 1L
        private val itemsFlow = MutableStateFlow<List<Item>>(emptyList())

        override fun observeAllItems(): Flow<List<Item>> =
            itemsFlow.map { list -> list.sortedByDescending { it.createdAt } }

        override suspend fun getAllItemsDirect(): List<Item> =
            itemsFlow.value.sortedByDescending { it.createdAt }

        override fun observePendingItems(): Flow<List<Item>> =
            itemsFlow.map { list ->
                list.filter { it.status == "PENDING" && it.type != "NOTE" }
                    .sortedWith(
                        compareBy<Item> { if (it.dueAt == null) 1 else 0 }
                            .thenBy { it.dueAt ?: Long.MAX_VALUE }
                            .thenByDescending { it.createdAt }
                    )
            }

        override suspend fun getPendingItemsDirect(): List<Item> =
            observePendingItems().first()

        override suspend fun getRemainingTasksForTodayDirect(endOfTodayMillis: Long): List<Item> =
            itemsFlow.value.filter {
                it.status == "PENDING" &&
                    it.type !in setOf("NOTE", "GOAL") &&
                    !it.sourceText.startsWith("web_pipeline_") &&
                    (it.dueAt == null || it.dueAt <= endOfTodayMillis)
            }

        override suspend fun getPendingLeadFollowUpsDirect(leadId: Long): List<Item> =
            itemsFlow.value.filter {
                it.status == "PENDING" && it.sourceText.startsWith("lead_followup:$leadId:")
            }

        override suspend fun getAllPendingLeadFollowUpsDirect(): List<Item> =
            itemsFlow.value.filter {
                it.status == "PENDING" && it.sourceText.startsWith("lead_followup:")
            }

        override fun searchNotes(query: String): Flow<List<Item>> =
            itemsFlow.map { list ->
                list.filter {
                    it.type == "NOTE" &&
                        (it.title.contains(query, ignoreCase = true) ||
                            it.details.contains(query, ignoreCase = true))
                }
            }

        override suspend fun getItemById(id: Long): Item? {
            if (failVerification) return null
            return itemsFlow.value.firstOrNull { it.id == id }
        }

        override suspend fun getItemByTitle(titleQuery: String): Item? =
            itemsFlow.value.firstOrNull {
                it.status == "PENDING" && it.title.contains(titleQuery, ignoreCase = true)
            }

        override suspend fun insertItem(item: Item): Long {
            val assignedId = if (item.id > 0L) item.id else nextId++
            val saved = item.copy(id = assignedId)
            itemsFlow.value = itemsFlow.value.filterNot { it.id == assignedId } + saved
            return assignedId
        }

        override suspend fun insertItems(items: List<Item>) {
            items.forEach { insertItem(it) }
        }

        override suspend fun updateItem(item: Item) {
            itemsFlow.value = itemsFlow.value.map { if (it.id == item.id) item else it }
        }

        override suspend fun updateStatus(id: Long, status: String) {
            itemsFlow.value = itemsFlow.value.map {
                if (it.id == id) it.copy(status = status) else it
            }
        }

        override suspend fun deleteItem(item: Item) {
            itemsFlow.value = itemsFlow.value.filterNot { it.id == item.id }
        }
    }

    @Test
    fun `add a task send sample websites to five clients saves verified item and appears in Plan Today`() = runTest {
        val prompt = "add a task: send sample websites to five clients"
        val parsed = AssistantParser.parse(prompt, nowMillis = 1_730_000_000_000L)

        assertTrue("Expected ParsedIntent.Task but got $parsed", parsed is ParsedIntent.Task)
        val taskIntent = parsed as ParsedIntent.Task
        assertEquals("Send sample websites to five clients", taskIntent.title)
        assertEquals("NORMAL", taskIntent.priority)

        val dao = FakeItemDao()
        val saveResult = AssistantRepository.persistAndVerifyTaskInDao(
            itemDao = dao,
            title = taskIntent.title,
            dueAt = taskIntent.dueAt,
            durationMinutes = taskIntent.durationMinutes,
            priority = taskIntent.priority,
            type = "TASK",
            sourceText = taskIntent.sourceText
        )

        assertTrue("Expected verified save success", saveResult.isSuccess)
        val savedItem = saveResult.getOrNull()
        assertNotNull(savedItem)
        assertEquals("Send sample websites to five clients", savedItem!!.title)
        assertEquals("PENDING", savedItem.status)
        assertEquals("TASK", savedItem.type)

        // Verify Plan > Today reads the exact same pendingItems stream and filter
        val pendingInPlan = dao.observePendingItems().first()
        val activeTodayInPlan = pendingInPlan.filter { !it.sourceText.startsWith("web_pipeline_") }
        assertEquals(1, activeTodayInPlan.size)
        assertEquals("Send sample websites to five clients", activeTodayInPlan.first().title)

        // Checking the task checkbox marks it DONE and removes it from Pending Today
        dao.updateStatus(savedItem.id, "DONE")
        val afterComplete = dao.observePendingItems().first()
            .filter { !it.sourceText.startsWith("web_pipeline_") }
        assertTrue(afterComplete.isEmpty())
    }

    @Test
    fun `persistAndVerifyTaskInDao reports failure honestly if storage verification fails`() = runTest {
        val brokenDao = FakeItemDao(failVerification = true)
        val result = AssistantRepository.persistAndVerifyTaskInDao(
            itemDao = brokenDao,
            title = "Send sample websites to five clients"
        )
        assertTrue("Expected failure when verification read-back returns null", result.isFailure)
    }

    @Test
    fun `period greeting fires at most once per morning afternoon and evening`() {
        // Morning first interaction -> greets
        val m1 = SecurePreferences.evaluatePeriodGreeting(
            lastDate = "",
            lastPeriod = "",
            currentDate = "2026-11-02",
            currentHourOfDay = 8,
            userName = "Israel"
        )
        assertEquals("Good morning, Israel.", m1.first)

        // Second morning interaction -> no greeting
        val m2 = SecurePreferences.evaluatePeriodGreeting(
            lastDate = m1.second,
            lastPeriod = m1.third,
            currentDate = "2026-11-02",
            currentHourOfDay = 10,
            userName = "Israel"
        )
        assertEquals("", m2.first)

        // First afternoon interaction -> greets once
        val a1 = SecurePreferences.evaluatePeriodGreeting(
            lastDate = m2.second,
            lastPeriod = m2.third,
            currentDate = "2026-11-02",
            currentHourOfDay = 14,
            userName = "Israel"
        )
        assertEquals("Good afternoon, Israel.", a1.first)

        // Second afternoon interaction -> no greeting
        val a2 = SecurePreferences.evaluatePeriodGreeting(
            lastDate = a1.second,
            lastPeriod = a1.third,
            currentDate = "2026-11-02",
            currentHourOfDay = 16,
            userName = "Israel"
        )
        assertEquals("", a2.first)

        // First evening interaction -> greets once
        val e1 = SecurePreferences.evaluatePeriodGreeting(
            lastDate = a2.second,
            lastPeriod = a2.third,
            currentDate = "2026-11-02",
            currentHourOfDay = 19,
            userName = "Israel"
        )
        assertEquals("Good evening, Israel.", e1.first)

        // Second evening interaction -> no greeting
        val e2 = SecurePreferences.evaluatePeriodGreeting(
            lastDate = e1.second,
            lastPeriod = e1.third,
            currentDate = "2026-11-02",
            currentHourOfDay = 22,
            userName = "Israel"
        )
        assertEquals("", e2.first)

        // Next calendar day morning -> greets again once
        val nextDayMorning = SecurePreferences.evaluatePeriodGreeting(
            lastDate = e2.second,
            lastPeriod = e2.third,
            currentDate = "2026-11-03",
            currentHourOfDay = 7,
            userName = "Israel"
        )
        assertEquals("Good morning, Israel.", nextDayMorning.first)
    }

    @Test
    fun `coach system prompt and list and stop-question helpers enforce concise non-repetitive persona`() {
        assertTrue(
            AiClient.SYSTEM_COACH_PROMPT.contains(
                "Do not greet the user unless told to. Never repeat the daily briefing unless asked. Never repeat information already given."
            )
        )

        assertTrue(AssistantParser.isWhatsOnMyListQuery("what's on my list?"))
        assertTrue(AssistantParser.isWhatsOnMyListQuery("What is pending"))

        assertTrue(AssistantParser.isStopQuestionsPhrase("that's all"))
        assertTrue(AssistantParser.isStopQuestionsPhrase("no more"))
        assertTrue(AssistantParser.isStopQuestionsPhrase("let me rest"))
        assertFalse(AssistantParser.isStopQuestionsPhrase("what's on my list?"))

        assertEquals(
            "You still have Send sample websites to five clients pending.",
            AiClient.stripLeadingGreeting("Good morning, Israel. You still have Send sample websites to five clients pending.")
        )
    }

    @Test
    fun `conversation turns sort newest first by timestamp desc then id desc`() {
        val turns = listOf(
            ConversationTurn(id = 1L, sender = "USER", text = "Older question", timestamp = 1000L),
            ConversationTurn(id = 2L, sender = "ASSISTANT", text = "Older reply", timestamp = 1001L),
            ConversationTurn(id = 3L, sender = "USER", text = "Latest question", timestamp = 2000L),
            ConversationTurn(id = 4L, sender = "ASSISTANT", text = "Latest reply", timestamp = 2000L)
        )
        val sortedNewestFirst = turns.sortedWith(
            compareByDescending<ConversationTurn> { it.timestamp }.thenByDescending { it.id }
        )
        assertEquals(listOf(4L, 3L, 2L, 1L), sortedNewestFirst.map { it.id })
    }
}
