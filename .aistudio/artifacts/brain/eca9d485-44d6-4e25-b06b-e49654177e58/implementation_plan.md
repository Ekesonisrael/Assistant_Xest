# General Proactive Personal Assistant - Architecture & Implementation Plan

Comprehensive implementation plan to restructure the native Android app (`com.xestlab.assistant`, Kotlin, Jetpack Compose, Room) from a studio-specific tracker into a general proactive personal assistant that operates strictly offline by default, with optional cloud enhancements (Gemini NLU & Coach, Fish Audio TTS, IMAP email checks).

---

### User Decisions & Clarifications Incorporated
- **Person Reminder "Open Chat" Routing**: When the user taps "Open chat" from a person reminder notification or spoken briefing, the app will dispatch an Android Intent with `Intent.createChooser` (routing to WhatsApp or SMS apps according to user selection).
- **Background IMAP Email Check**: Implemented via Android WorkManager periodic worker (`PeriodicWorkRequestBuilder<EmailCheckWorker>(30, TimeUnit.MINUTES)`) using `com.sun.mail:android-mail` / Jakarta Mail with SSL, checking read-only email with Wi-Fi constraint toggle and quiet hours (22:00 to wake time) voice silence.
- **Voice Playback & Confirmations**: Natural conversational replies vocalized aloud when mic is used, with stop speech controls. Executed actions confirmed both audibly and visually.
- **Offline Guarantee**: Universal input works fully offline via a local intent parser (`AssistantParser.kt`). No alarms are blocked or delayed by network calls.

---

## 1. Architecture & Component Blueprint

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose (4 Tabs)                        │
│          Home          │       Plan      │     Notes     │  Settings   │
│   (Universal Input,    │ (Timeline, Year │ (Searchable,  │ (Backup, AI,│
│   "Next Up", Voice/Chat│  Month, Pipeline│  Voice Read,  │  IMAP, Mem, │
│    Planner Proposals)  │   Checklists)   │   Reminders)  │  Alarms)    │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │ State & Commands
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          AssistantViewModel                            │
│  - Conversation State (last 50 turns in DB + delete capability)        │
│  - Planner Recommendation State ("Now or Later" proposal before save)  │
│  - Universal Input processing (Voice/Text dispatch)                    │
│  - Web Goal Tracker JSON Importer integration                          │
└──────────────────┬─────────────────────────────────┬───────────────────┘
                   │                                 │
         Online & AI Enabled                  Offline or Fallback
                   ▼                                 ▼
┌────────────────────────────────────┐  ┌────────────────────────────────┐
│             AiClient               │  │        AssistantParser         │
│  - Gemini REST endpoint            │  │  - Local Regex & Intent Rules  │
│  - Context: Schedule, Today, Facts │  │  - Reminders, Tasks, Notes,    │
│  - JSON: {"reply","actions"}       │  │    Completions ("done X")      │
└──────────────────┬─────────────────┘  └────────────────┬───────────────┘
                   │                                     │
                   └──────────────────┬──────────────────┘
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│                             PlannerEngine                              │
│  - Evaluates today's unfinished items, alarms, wake & quiet hours      │
│  - Computes proposal: Now / Later today / Tomorrow / Drop + 1-sent why │
│  - Requires explicit user choice (Save nothing until confirmed)        │
└─────────────────────────────────────┬──────────────────────────────────┘
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        Room AppDatabase (v2)                           │
│  - items (Generic Item table: TASK, GOAL, NOTE, REMINDER, HABIT)       │
│  - facts (id, text, category, createdAt) for persistent memory         │
│  - conversations (id, sender, text, timestamp, actionsJson)            │
│  - [Preserved from v1]: goals, checklist_items, daily_logs, etc.       │
└─────────────────────────────────────┬──────────────────────────────────┘
                                      │
                                      ▼
┌────────────────────────────────────────────────────────────────────────┐
│                            Proactive Engine                            │
│  - AlarmScheduler: AlarmManager.setAlarmClock (Wake-up repeating alarm)│
│  - Exact Alarms: Morning (07:30), Midday (12:30), Evening (19:30),     │
│    Sunday (18:00) briefings + Person Reminders                         │
│  - VoiceReminderService: Foreground TTS with UtteranceProgressListener │
│    (Fish Audio fallback to Android TextToSpeech)                       │
│  - EmailCheckWorker: 30m WorkManager periodic check (Wi-Fi constraint, │
│    quiet hours silence between 22:00 and wake time)                    │
│  - BootReceiver & PackageReplaced: Full alarm rescheduling             │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Detailed Technical Plan

### Phase 1: Database Migration & Entities (Room v2)
1. **Generic `Item` Entity**:
   - `id: Long = 0` (Primary Key, autogenerate)
   - `type: String` (`TASK`, `GOAL`, `NOTE`, `REMINDER`, `HABIT`)
   - `title: String`, `details: String = ""`
   - `dueAt: Long?`, `durationMinutes: Int = 0`, `priority: String = "NORMAL"`
   - `status: String = "PENDING"` (`PENDING`, `COMPLETED`, `DROPPED`)
   - `repeatRule: String?` (e.g. `DAILY`, `WEEKLY`, `CRON`)
   - `targetAmount: Double?`, `unit: String?`, `progress: Double = 0.0`
   - `contactName: String?`, `sourceText: String = ""`
   - `createdAt: Long = System.currentTimeMillis()`
   - *Computed Daily Amount*: for `GOAL`, `(targetAmount - progress) / max(1, daysRemaining)`.
2. **`Fact` Entity**:
   - `id: Long = 0`, `text: String`, `category: String = "GENERAL"`, `createdAt: Long`.
3. **`ConversationRecord` Entity**:
   - `id: Long = 0`, `sender: String` ("USER" | "ASSISTANT"), `text: String`, `timestamp: Long`, `actionsJson: String?`.
4. **Non-Destructive Room Migration**:
   - Increment `AppDatabase` version to `2`.
   - Provide `MIGRATION_1_2` creating tables `items`, `facts`, and `conversations` with exact indices.
   - Retain existing v1 tables (`goals`, `checklist_items`, `daily_logs`, `reminder_schedules`, `user_settings`) untouched.
5. **DAOs**:
   - `ItemDao`: CRUD, active items flow, type queries, search query for notes.
   - `FactDao`: CRUD, delete single fact, delete all facts, fetch all for AI context.
   - `ConversationDao`: insert message, query last 50 messages, clear history.

### Phase 2: Security & App-Private Credentials
1. **`SecurePreferences.kt`**:
   - Manages encrypted/app-private storage: Gemini API Key, Gemini Model Name (`gemini-2.5-flash`), Fish Audio API Key, Fish Audio Voice ID, IMAP Server, IMAP Port, IMAP Username, IMAP App Password, IMAP Wi-Fi only toggle, Wake Time, Quiet Hours (22:00 default).
   - Strict guarantee: None of these values are included in JSON exports, database dumps, or logs.

### Phase 3: Universal Input, Planner & Natural Language Understanding
1. **`AssistantParser.kt` (Offline Parser)**:
   - Regex and keyword extraction for:
     - `remind me to message <name> at <time>` -> `REMINDER` with `contactName`
     - `remind me to <action> at <time|tomorrow>` -> `REMINDER`
     - `note: <text>` or `note that <text>` -> `NOTE`
     - `remember that <fact>` -> `Fact` memory
     - `add task <text>` -> `TASK`
     - `done <title>` -> `complete_item`
     - Fallback: Unmatched input becomes a `NOTE` with source text preserved.
2. **`PlannerEngine.kt` ("Now or Later" Decision Engine)**:
   - When a new item is captured, the engine evaluates:
     - Unfinished items for today.
     - Scheduled exact alarms and briefings.
     - Free time slots between user's wake time (default 06:30) and quiet hours (22:00).
   - Generates a proposal:
     - Recommended action: `NOW`, `LATER_TODAY (HH:mm)`, `TOMORROW (HH:mm)`, or `DROP`.
     - One-sentence rationale (e.g., *"You have 45 minutes free before your 12:30 midday briefing."*).
   - State held in `AssistantViewModel`: Displays interactive proposal card on Home screen with options:
     - `Accept Recommendation`, `Schedule Later`, `Drop`, or spoken `yes`/`no`.
     - **Save nothing to Room until user chooses**. Never reschedules existing items without asking.
3. **`AiClient.kt` Enhancement**:
   - Supports Gemini REST call with structured JSON schema:
     `{"reply": "...", "actions": [{"action": "...", "params": {...}}]}`
   - Actions: `create_item`, `update_item`, `complete_item`, `snooze`, `add_note`, `propose_plan`, `remember_fact`, `read_emails`.
   - Compact context builder: Schedule summary, wake time, unfinished tasks, last 7 days metrics, stored facts memory, user name.

### Phase 4: Proactive Engine & Background Services
1. **Wake-up Alarm via `AlarmManager.setAlarmClock`**:
   - Configurable wake-up time in Settings (default 06:30).
   - Uses `setAlarmClock(AlarmClockInfo(triggerTime, showIntent), pendingIntent)`.
   - Fires `WakeUpReceiver`: Launches full-screen alarm notification, plays audio alarm tone, and triggers `VoiceReminderService` to speak today's planned agenda. Repeats until dismissed or snoozed.
2. **Exact Spoken Briefings & Nudges**:
   - Morning (07:30), Midday (12:30), Evening (19:30), Sunday Review (18:00), and custom user reminder times.
   - `VoiceReminderService`: Foreground service with `UtteranceProgressListener`. Calls `stopSelf()` and dismisses notification as soon as speech finishes.
   - Integrates optional Fish Audio TTS endpoint (`POST https://api.fish.audio/v1/tts`) if enabled and online, with seamless fallback to Android `TextToSpeech`.
3. **Person Reminders**:
   - Triggers notification with action buttons: `[Open chat]`, `[Done]`, `[Snooze 15m]`.
   - `Open chat` launches `Intent.createChooser` targeting messaging apps (WhatsApp / SMS) addressed to `contactName`.
4. **Background IMAP Email Check via WorkManager**:
   - `EmailCheckWorker`: Enqueued via `WorkManager` with 30-minute interval and `NetworkType.CONNECTED` (or `UNMETERED` if Wi-Fi only toggle is enabled).
   - Connects to IMAP host via `com.sun.mail:android-mail` with SSL/TLS.
   - Inspects unread messages in `INBOX` since last check.
   - High-priority / urgent subject detection: creates a high-priority heads-up notification.
   - **Quiet Hours Enforcement**: Voice read-aloud is strictly suppressed between 22:00 and wake-up time.
5. **Sunday Review & End-of-Day Summary**:
   - 21:30 daily notification: items completed vs left.
   - Sunday 18:00 spoken review: weekly progress recap, upcoming week preview, and prompt: *"Time to back up your data in Settings."*
6. **Device Boot & Setup Flow**:
   - `BootReceiver` and `MY_PACKAGE_REPLACED` reschedule all alarms and WorkManager tasks.
   - First-run setup checklist in Settings: Exact Alarms permission (`SCHEDULE_EXACT_ALARM`), Post Notifications (`POST_NOTIFICATIONS`), Battery Optimization whitelist (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), Autostart instructions, and "Test wake-up in 1 minute" trigger.

### Phase 5: Web Goal Tracker JSON Importer & Views
1. **`WebTrackerImporter.kt`**:
   - Reads exported JSON from `index.html` (containing `settings`, `days`, `weeks`, `months`, `pipeline`).
   - Maps:
     - `settings` -> user settings and annual targets.
     - `pipeline` -> pipeline checklist `Item` records with type `TASK` and category tag.
     - `months` & `weeks` -> milestone `Item` records with type `GOAL`.
     - `days` -> historical `DailyLog` entries.
   - Fully idempotent: checks existing IDs/timestamps to prevent duplicates.
2. **Plan Tab Hierarchy**:
   - Sub-views/tabs: `Today's Timeline`, `Week`, `Month`, `Year`, and `Pipeline Checklist`.

### Phase 6: UI, Design System & Screens (Jetpack Compose)
1. **Design System & Theme Tokens**:
   - Strict palette: Background `#0E1412`, Cards `#161F1C`, Surface Border `#24332F`, Text `#F2F1EC`, Muted Text `#8E9E99`, Accent Teal `#2BA5A0`, Accent Orange `#E07A5F`, Danger `#E5484D`.
   - Geometry: 8px corner radius on cards, buttons, dialogs. Zero pill buttons. No gradients.
   - Typography: System serif for greetings and top headings at 32sp.
   - Clean UI: No external icon fonts, no emojis, no em dashes (`—`) in copy.
2. **Screens**:
   - **Tab 1: Home (The Universal Assistant)**:
     - System serif greeting (32sp) with date.
     - "Next Up" card showing immediate upcoming item, time remaining, and completion button.
     - Conversational feed (last 50 turns) showing user speech/text, assistant replies, and executed action chips.
     - Active "Now or Later" proposal card when planning new items.
     - Central large circular microphone button with pulsing animated rings when recording.
     - Fixed bottom input bar with "Tell me anything..." placeholder, send button, and mic trigger.
     - "Stop Speech" button when TTS is speaking.
   - **Tab 2: Plan**:
     - Daily chronological schedule (alarms, tasks, reminders, quiet hours).
     - Filter chips / tabs: Today, Week, Month, Year, and Pipeline.
     - Daily required amount badge for goals (`X pages/day`).
   - **Tab 3: Notes**:
     - Instant search bar filtering by title and content.
     - Card list with note text, creation date, and reminder badge if scheduled.
     - Action buttons: "Read aloud" (TTS) and "Add reminder".
   - **Tab 4: Settings & Data Safety**:
     - "Back up now" (generates sanitized JSON without keys, opens system share sheet).
     - "Restore from file" (file picker for JSON restore).
     - "Import from Web Goal Tracker" (file picker for web `index.html` exported JSON).
     - Memory Screen entry: View and delete stored facts ("Remember that...").
     - Clear conversation history button (clears last 50 turns).
     - Permissions & Autostart status with direct links.
     - Wake-up Alarm settings (time picker, "Test wake-up in 1 minute").
     - AI Coach settings (Toggle, Gemini API key, model name, test button).
     - Fish Audio TTS settings (Toggle, API key, voice ID).
     - IMAP Email settings (Toggle, server, port, email, app password, Wi-Fi only toggle, test connection).

### Phase 7: Build, Keystore & GitHub Actions
1. **`app/build.gradle.kts`**:
   - Add debug signing config pointing to `app/debug.keystore` (`android`/`androiddebugkey`/`android`).
   - Add dependencies:
     - `androidx.work:work-runtime-ktx:2.9.1`
     - `com.sun.mail:android-mail:1.6.7` and `com.sun.mail:android-activation:1.6.7` (or Jakarta Mail Android)
   - Ensure `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`.
2. **Keystore Generation**:
   - Generate `app/debug.keystore` with standard Android debug credentials if not present.
3. **Verification**:
   - Run compilation and tests to ensure zero compiler warnings or runtime crashes.
   - Update `README.md` with complete documentation of the restructured assistant.

---

## 3. Verification & Acceptance Criteria
- [ ] Universal Input captures any natural language input by voice or text.
- [ ] Offline local parser correctly extracts reminders, person reminders, notes, and tasks.
- [ ] "Now or Later" planner checks today's free slots and requires explicit user acceptance before saving.
- [ ] Wake-up alarm uses `AlarmManager.setAlarmClock`, vocalizes agenda, and rings until snoozed/dismissed.
- [ ] Spoken briefings fire at 07:30, 12:30, 19:30, Sunday 18:00, and person reminder times.
- [ ] Person reminder "Open chat" triggers Android app chooser for WhatsApp/SMS without sending automatically.
- [ ] Email check operates in background via WorkManager, strictly silencing voice during quiet hours (22:00 to wake time).
- [ ] Web Goal Tracker JSON import runs idempotently into `Item`, `Goal`, and `DailyLog`.
- [ ] Dark palette `#0E1412` active with 8px radius, system serif greetings, no pills, no emojis, no em dashes.
- [ ] Zero API keys or secrets leak into backup JSON, logs, or codebase.
- [ ] Debug keystore configured and project compiles cleanly.
