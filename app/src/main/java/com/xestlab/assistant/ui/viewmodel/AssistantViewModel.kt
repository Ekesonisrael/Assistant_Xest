package com.xestlab.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xestlab.assistant.ai.*
import com.xestlab.assistant.data.SecurePreferences
import com.xestlab.assistant.data.local.AppDatabase
import com.xestlab.assistant.data.model.*
import com.xestlab.assistant.data.repository.AssistantRepository
import com.xestlab.assistant.data.repository.WeeklySummaryData
import com.xestlab.assistant.service.AlarmScheduler
import com.xestlab.assistant.service.EmailHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String, // "You" or "Assistant"
    val text: String,
    val executedActions: List<String> = emptyList(),
    val proposal: PlannerProposal? = null,
    val timestamp: Long = System.currentTimeMillis()
)

// Backward compatibility alias
typealias JarvisMessage = ChatMessage

class AssistantViewModel(application: Application) : AndroidViewModel(application), TextToSpeech.OnInitListener {

    private val db = AppDatabase.getInstance(application)
    val repository = AssistantRepository(db)
    val securePreferences = SecurePreferences(application)
    val aiPreferences = AiPreferences(application)

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    val goals: StateFlow<Goal> = repository.goals
        .map { it ?: Goal() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Goal())

    val checklistItems: StateFlow<List<ChecklistItem>> = repository.checklistItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<ReminderSchedule>> = repository.reminders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<UserSettings> = repository.settings
        .map { it ?: UserSettings() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    val allItems: StateFlow<List<Item>> = repository.allItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingItems: StateFlow<List<Item>> = repository.pendingItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allFacts: StateFlow<List<Fact>> = repository.allFacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversationTurns: StateFlow<List<ConversationTurn>> = repository.conversationTurns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentDate = MutableStateFlow(getTodayDateString())
    val currentDate: StateFlow<String> = _currentDate.asStateFlow()

    val todayLogs: StateFlow<List<DailyLog>> = _currentDate
        .flatMapLatest { date -> repository.getLogsForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _weeklySummary = MutableStateFlow(
        WeeklySummaryData(0, 50, 0, 5, 0, 3, 0, 0)
    )
    val weeklySummary: StateFlow<WeeklySummaryData> = _weeklySummary.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    // Notes Search
    private val _notesSearchQuery = MutableStateFlow("")
    val notesSearchQuery: StateFlow<String> = _notesSearchQuery.asStateFlow()

    val searchNotes: StateFlow<List<Item>> = _notesSearchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                repository.allItems.map { list -> list.filter { it.type == "NOTE" } }
            } else {
                repository.searchNotes(query)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Planner Proposal awaiting user yes/no
    private val _activeProposal = MutableStateFlow<PlannerProposal?>(null)
    val activeProposal: StateFlow<PlannerProposal?> = _activeProposal.asStateFlow()

    // Conversational state
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(listOf(
        ChatMessage(
            sender = "Assistant",
            text = "Ready. Tell me anything: goals, tasks, notes, reminders, or questions."
        )
    ))
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    val isJarvisProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        refreshWeeklySummary()
        tts = TextToSpeech(application, this)
        // Load initial turns from database if available
        viewModelScope.launch {
            val dbTurns = db.conversationDao().getLastTurnsDirect(10)
            if (dbTurns.isNotEmpty()) {
                val mapped = dbTurns.reversed().map { turn ->
                    ChatMessage(
                        sender = if (turn.sender == "USER") "You" else "Assistant",
                        text = turn.text,
                        timestamp = turn.timestamp
                    )
                }
                _chatMessages.value = mapped
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.ENGLISH
            isTtsReady = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }
                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                }
                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                }
            })
        }
    }

    fun speakAssistant(text: String) {
        if (isTtsReady && tts != null) {
            val speed = settings.value.voiceSpeed
            tts?.setSpeechRate(speed)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant_chat_utterance")
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    fun setNotesSearch(query: String) {
        _notesSearchQuery.value = query
    }

    /**
     * Universal natural language handler for typed or voice inputs.
     */
    fun handleUniversalInput(rawInput: String, isSpoken: Boolean = false) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank() || _isProcessing.value) return

        // 1. Check if user is answering an active planner proposal with yes / no
        val currentProposal = _activeProposal.value
        if (currentProposal != null) {
            val lower = trimmed.lowercase(Locale.ROOT)
            if (lower == "yes" || lower.startsWith("yes ") || lower.contains("accept") || lower.contains("do it") || lower.contains("confirm")) {
                acceptProposal(currentProposal, isSpoken)
                return
            } else if (lower == "no" || lower.startsWith("no ") || lower.contains("drop") || lower.contains("cancel") || lower.contains("reject")) {
                rejectProposal(isSpoken)
                return
            }
        }

        appendMessage(ChatMessage(sender = "You", text = trimmed))
        viewModelScope.launch {
            repository.recordTurn(sender = "USER", text = trimmed)
        }

        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            val isOnline = AiClient.isNetworkAvailable(getApplication())
            val aiActive = securePreferences.isAiEnabled && securePreferences.hasGeminiKey() && isOnline

            if (aiActive) {
                try {
                    val contextJson = AiClient.buildLocalContext(db)
                    val historyPairs = _chatMessages.value.takeLast(6).map { it.sender to it.text }
                    val response = AiClient.generateAssistantResponse(
                        apiKey = securePreferences.geminiApiKey,
                        modelName = securePreferences.geminiModel,
                        contextJson = contextJson,
                        sessionHistory = historyPairs,
                        userInput = trimmed
                    )

                    if (response != null) {
                        handleAssistantResponse(response, trimmed, isSpoken)
                    } else {
                        handleOfflineInput(trimmed, isSpoken)
                    }
                } catch (e: Exception) {
                    handleOfflineInput(trimmed, isSpoken)
                }
            } else {
                handleOfflineInput(trimmed, isSpoken)
            }
        }
    }

    // Compatibility method for JarvisScreen
    fun sendJarvisMessage(rawInput: String) {
        handleUniversalInput(rawInput, isSpoken = true)
    }

    private suspend fun handleAssistantResponse(
        response: AssistantResponse,
        originalInput: String,
        isSpoken: Boolean
    ) {
        val executedActionNotes = mutableListOf<String>()

        response.actions.forEach { action ->
            when (action.name.lowercase(Locale.ROOT)) {
                "create_item" -> {
                    val itemType = action.type?.uppercase(Locale.ROOT) ?: "TASK"
                    val title = action.title ?: originalInput
                    val dueAt = if (action.time != null) parseTimeToMillis(action.time) else null
                    val candidate = Item(
                        type = itemType,
                        title = title,
                        details = action.details ?: "",
                        dueAt = dueAt,
                        priority = "NORMAL",
                        targetAmount = action.targetAmount,
                        unit = action.unit,
                        contactName = action.contactName,
                        sourceText = originalInput
                    )

                    // Run Planner Recommendation
                    val proposal = PlannerEngine.propose(db, candidate)
                    withContext(Dispatchers.Main) {
                        _activeProposal.value = proposal
                        val msg = ChatMessage(
                            sender = "Assistant",
                            text = "${response.reply}\n\nRecommendation: ${proposal.rationale}",
                            proposal = proposal
                        )
                        appendMessage(msg)
                        _isProcessing.value = false
                        if (isSpoken) speakAssistant(proposal.summarySpeech)
                    }
                    return
                }

                "complete_item" -> {
                    val title = action.title ?: originalInput
                    val found = db.itemDao().getItemByTitle(title)
                    if (found != null) {
                        repository.updateItemStatus(found.id, "COMPLETED")
                        executedActionNotes.add("Completed: ${found.title}")
                    } else {
                        // Check checklist items
                        val items = checklistItems.value
                        val target = items.firstOrNull { it.title.contains(title, ignoreCase = true) }
                        if (target != null) {
                            repository.markItemDone(_currentDate.value, target.id, if (target.isCountType) 10 else 0)
                            refreshWeeklySummary()
                            executedActionNotes.add("Marked ${target.title} done")
                        }
                    }
                }

                "add_note" -> {
                    val title = action.title ?: originalInput
                    val noteItem = Item(type = "NOTE", title = title, details = action.details ?: "", sourceText = originalInput)
                    repository.saveItem(noteItem)
                    executedActionNotes.add("Saved note: $title")
                }

                "remember_fact" -> {
                    val factText = action.details ?: action.title ?: originalInput
                    repository.saveFact(factText, "MEMORY")
                    executedActionNotes.add("Remembered: $factText")
                }

                "snooze" -> {
                    val mins = action.minutes ?: 15
                    AlarmScheduler.scheduleSnooze(getApplication(), "user_snooze", "Reminders Snoozed", mins)
                    executedActionNotes.add("Snoozed for $mins minutes")
                }
            }
        }

        val reply = response.reply
        withContext(Dispatchers.Main) {
            appendMessage(
                ChatMessage(sender = "Assistant", text = reply, executedActions = executedActionNotes)
            )
            _isProcessing.value = false
            if (isSpoken) speakAssistant(reply)
        }
        repository.recordTurn(sender = "ASSISTANT", text = reply)
    }

    private suspend fun handleOfflineInput(input: String, isSpoken: Boolean) {
        val parsed = AssistantParser.parse(input)
        val actions = mutableListOf<String>()
        var replyText = ""
        var proposalToSet: PlannerProposal? = null

        when (parsed) {
            is ParsedIntent.PersonReminder -> {
                val dueMillis = parseTimeToMillis(parsed.time)
                val item = Item(
                    type = "REMINDER",
                    title = "Message ${parsed.contactName}",
                    details = parsed.details,
                    dueAt = dueMillis,
                    contactName = parsed.contactName,
                    sourceText = input
                )
                val prop = PlannerEngine.propose(db, item)
                proposalToSet = prop
                replyText = "Person reminder for ${parsed.contactName} at ${parsed.time}. ${prop.rationale}"
            }

            is ParsedIntent.Reminder -> {
                val dueMillis = parseTimeToMillis(parsed.time, parsed.isTomorrow)
                val item = Item(
                    type = "REMINDER",
                    title = parsed.title,
                    dueAt = dueMillis,
                    sourceText = input
                )
                val prop = PlannerEngine.propose(db, item)
                proposalToSet = prop
                replyText = "Reminder '${parsed.title}' for ${parsed.time}. ${prop.rationale}"
            }

            is ParsedIntent.Task -> {
                val item = Item(
                    type = "TASK",
                    title = parsed.title,
                    durationMinutes = parsed.durationMinutes,
                    sourceText = input
                )
                val prop = PlannerEngine.propose(db, item)
                proposalToSet = prop
                replyText = "New task '${parsed.title}'. ${prop.rationale}"
            }

            is ParsedIntent.Goal -> {
                val deadlineMillis = System.currentTimeMillis() + parsed.daysRemaining * 86400000L
                val item = Item(
                    type = "GOAL",
                    title = parsed.title,
                    targetAmount = parsed.amount,
                    unit = parsed.unit,
                    dueAt = deadlineMillis,
                    sourceText = input
                )
                val prop = PlannerEngine.propose(db, item)
                proposalToSet = prop
                replyText = "Goal '${parsed.title}'. Target: ${parsed.amount} ${parsed.unit}. ${prop.rationale}"
            }

            is ParsedIntent.Note -> {
                val item = Item(type = "NOTE", title = parsed.text, sourceText = input)
                repository.saveItem(item)
                replyText = "Saved note: ${parsed.text}."
                actions.add("Saved note")
            }

            is ParsedIntent.RememberFact -> {
                repository.saveFact(parsed.fact, "OFFLINE_MEMORY")
                replyText = "Remembered: ${parsed.fact}."
                actions.add("Stored fact")
            }

            is ParsedIntent.Done -> {
                val target = parsed.targetName
                val found = db.itemDao().getItemByTitle(target)
                if (found != null) {
                    repository.updateItemStatus(found.id, "COMPLETED")
                    replyText = "Marked '${found.title}' completed."
                    actions.add("Completed ${found.title}")
                } else {
                    val checklist = checklistItems.value
                    val match = checklist.firstOrNull { it.title.contains(target, ignoreCase = true) }
                    if (match != null) {
                        repository.markItemDone(_currentDate.value, match.id, if (match.isCountType) 10 else 0)
                        refreshWeeklySummary()
                        replyText = "Marked '${match.title}' done."
                        actions.add("Marked ${match.title} done")
                    } else {
                        replyText = "Marked done for '$target'."
                    }
                }
            }

            is ParsedIntent.Question -> {
                val isOnline = AiClient.isNetworkAvailable(getApplication())
                replyText = if (!securePreferences.isAiEnabled) {
                    "AI is off in Settings. You can enable Gemini or ask offline schedule commands."
                } else if (!isOnline) {
                    "Offline. Can't reach Gemini right now, but your schedule and tasks are active."
                } else {
                    "I am here. How can I help you plan your day?"
                }
            }

            is ParsedIntent.FallbackNote -> {
                // If offline and cannot parse, save text as note and say so plainly
                val item = Item(type = "NOTE", title = parsed.text, sourceText = input)
                repository.saveItem(item)
                replyText = "Could not parse command offline. Saved text as a note."
                actions.add("Saved as note")
            }
        }

        withContext(Dispatchers.Main) {
            _activeProposal.value = proposalToSet
            appendMessage(
                ChatMessage(
                    sender = "Assistant",
                    text = replyText,
                    executedActions = actions,
                    proposal = proposalToSet
                )
            )
            _isProcessing.value = false
            if (isSpoken) {
                val voiceReply = proposalToSet?.summarySpeech ?: replyText
                speakAssistant(voiceReply)
            }
        }
        repository.recordTurn(sender = "ASSISTANT", text = replyText)
    }

    /**
     * User accepts planner recommendation (tapped Accept or spoken "yes").
     * Saves candidate item to database and schedules alarms if needed.
     */
    fun acceptProposal(proposal: PlannerProposal, isSpoken: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val itemToSave = proposal.item.copy(status = "PENDING")
            val newId = repository.saveItem(itemToSave)
            val savedItem = itemToSave.copy(id = newId)

            if (savedItem.dueAt != null && savedItem.dueAt > System.currentTimeMillis()) {
                AlarmScheduler.schedulePersonReminder(getApplication(), savedItem)
            }

            val confirmMsg = "Scheduled: ${savedItem.title} (${proposal.suggestedTime ?: "Confirmed"})."
            withContext(Dispatchers.Main) {
                _activeProposal.value = null
                appendMessage(
                    ChatMessage(
                        sender = "Assistant",
                        text = confirmMsg,
                        executedActions = listOf("Scheduled item")
                    )
                )
                if (isSpoken) speakAssistant("Confirmed. Scheduled ${savedItem.title}.")
            }
            repository.recordTurn(sender = "ASSISTANT", text = confirmMsg)
        }
    }

    /**
     * User rejects planner recommendation (tapped Drop or spoken "no").
     */
    fun rejectProposal(isSpoken: Boolean = false) {
        _activeProposal.value = null
        val dropMsg = "Dropped. Nothing was saved."
        appendMessage(ChatMessage(sender = "Assistant", text = dropMsg))
        if (isSpoken) speakAssistant(dropMsg)
        viewModelScope.launch {
            repository.recordTurn(sender = "ASSISTANT", text = dropMsg)
        }
    }

    fun completeItem(item: Item) {
        viewModelScope.launch {
            repository.updateItemStatus(item.id, "COMPLETED")
            _toastMessage.value = "Completed: ${item.title}"
        }
    }

    fun deleteItem(item: Item) {
        viewModelScope.launch {
            repository.deleteItem(item)
            _toastMessage.value = "Item removed"
        }
    }

    fun readItemAloud(item: Item) {
        val speech = "${item.title}. ${item.details}"
        speakAssistant(speech)
    }

    fun attachReminderToNote(note: Item, timeString: String) {
        viewModelScope.launch {
            val dueMillis = parseTimeToMillis(timeString)
            val updated = note.copy(dueAt = dueMillis)
            repository.saveItem(updated)
            AlarmScheduler.schedulePersonReminder(getApplication(), updated)
            _toastMessage.value = "Reminder set for $timeString"
        }
    }

    fun deleteFact(fact: Fact) {
        viewModelScope.launch {
            repository.deleteFact(fact)
            _toastMessage.value = "Fact deleted from memory"
        }
    }

    fun clearAllFacts() {
        viewModelScope.launch {
            repository.clearAllFacts()
            _toastMessage.value = "All memory facts cleared"
        }
    }

    fun clearConversation() {
        viewModelScope.launch {
            repository.clearConversation()
            _chatMessages.value = listOf(
                ChatMessage(
                    sender = "Assistant",
                    text = "Conversation history cleared. Ready."
                )
            )
            _toastMessage.value = "Chat history cleared"
        }
    }

    fun markItemDone(itemId: String, count: Int = 0) {
        viewModelScope.launch {
            repository.markItemDone(_currentDate.value, itemId, count)
            refreshWeeklySummary()
            _toastMessage.value = "Item marked done"
        }
    }

    fun markItemNotDone(itemId: String, reason: String) {
        viewModelScope.launch {
            repository.markItemNotDone(_currentDate.value, itemId, reason)
            refreshWeeklySummary()
            _toastMessage.value = "Item marked not done: $reason"
        }
    }

    fun refreshWeeklySummary() {
        viewModelScope.launch {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = Calendar.MONDAY
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val weekStart = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            cal.add(Calendar.DAY_OF_WEEK, 6)
            val weekEnd = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)

            _weeklySummary.value = repository.getWeeklySummary(weekStart, weekEnd)
        }
    }

    fun saveGoal(updatedGoal: Goal) {
        viewModelScope.launch {
            repository.saveGoals(updatedGoal)
            refreshWeeklySummary()
            _toastMessage.value = "Goals updated"
        }
    }

    fun saveUserSettings(updatedSettings: UserSettings) {
        viewModelScope.launch {
            repository.saveSettings(updatedSettings)
            _toastMessage.value = "Settings saved"
        }
    }

    fun saveAiSettings(enabled: Boolean, key: String, model: String) {
        securePreferences.isAiEnabled = enabled
        securePreferences.geminiApiKey = key
        securePreferences.geminiModel = model
        _toastMessage.value = "AI settings saved"
    }

    fun testAiConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = securePreferences.geminiApiKey
            val model = securePreferences.geminiModel
            if (key.isBlank()) {
                withContext(Dispatchers.Main) {
                    onResult(false, "API key is empty. Please paste your key.")
                }
                return@launch
            }
            if (!AiClient.isNetworkAvailable(getApplication())) {
                withContext(Dispatchers.Main) {
                    onResult(false, "No network connection available.")
                }
                return@launch
            }
            val res = AiClient.testConnection(key, model)
            withContext(Dispatchers.Main) {
                onResult(res.first, res.second)
            }
        }
    }

    fun testImapConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val host = securePreferences.imapHost
            val port = securePreferences.imapPort
            val user = securePreferences.imapUser
            val pass = securePreferences.imapPassword
            val res = EmailHelper.testImapConnection(host, port, user, pass)
            withContext(Dispatchers.Main) {
                onResult(res.first, res.second)
            }
        }
    }

    fun updateWakeUpTime(newTime: String) {
        securePreferences.wakeUpTime = newTime
        AlarmScheduler.scheduleWakeUpAlarm(getApplication(), newTime)
        _toastMessage.value = "Wake-up alarm set for $newTime"
    }

    fun testWakeUpInOneMinute() {
        AlarmScheduler.scheduleTestReminder(getApplication())
        _toastMessage.value = "Test alarm scheduled for 1 minute from now."
    }

    fun completeFirstRun() {
        viewModelScope.launch {
            val cur = settings.value
            repository.saveSettings(cur.copy(isFirstRunComplete = true))
            AlarmScheduler.scheduleAllActive(getApplication())
        }
    }

    fun toggleReminderActive(reminder: ReminderSchedule) {
        viewModelScope.launch {
            val updated = reminder.copy(isActive = !reminder.isActive)
            repository.saveReminder(updated)
            if (updated.isActive) {
                AlarmScheduler.scheduleReminder(getApplication(), updated)
                _toastMessage.value = "${updated.title} activated"
            } else {
                AlarmScheduler.cancelReminder(getApplication(), updated.id)
                _toastMessage.value = "${updated.title} deactivated"
            }
        }
    }

    fun toggleReminderVoice(reminder: ReminderSchedule) {
        viewModelScope.launch {
            val updated = reminder.copy(voiceEnabled = !reminder.voiceEnabled)
            repository.saveReminder(updated)
        }
    }

    fun updateReminderTime(reminder: ReminderSchedule, newTime: String) {
        viewModelScope.launch {
            val updated = reminder.copy(timeString = newTime)
            repository.saveReminder(updated)
            if (updated.isActive) {
                AlarmScheduler.scheduleReminder(getApplication(), updated)
            }
            _toastMessage.value = "Time set to $newTime"
        }
    }

    fun addCustomReminder(title: String, timeString: String, isRepeating: Boolean) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString().take(8)
            val reminder = ReminderSchedule(
                id = id,
                title = title,
                timeString = timeString,
                isRepeating = isRepeating,
                daysOfWeek = "ALL",
                voiceEnabled = true,
                isActive = true,
                isBuiltIn = false
            )
            repository.saveReminder(reminder)
            AlarmScheduler.scheduleReminder(getApplication(), reminder)
            _toastMessage.value = "Custom reminder added for $timeString"
        }
    }

    fun deleteCustomReminder(reminder: ReminderSchedule) {
        viewModelScope.launch {
            repository.deleteReminder(reminder)
            AlarmScheduler.cancelReminder(getApplication(), reminder.id)
            _toastMessage.value = "Reminder removed"
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        val current = _chatMessages.value.toMutableList()
        current.add(msg)
        _chatMessages.value = current.takeLast(50)
    }

    private fun parseTimeToMillis(timeStr: String, isTomorrow: Boolean = false): Long {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 12
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val cal = Calendar.getInstance().apply {
            if (isTomorrow) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() && !isTomorrow) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return cal.timeInMillis
    }

    private fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
