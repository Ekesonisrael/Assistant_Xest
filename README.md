# Proactive Personal Assistant (Android)

Native Android (Kotlin, Jetpack Compose, Room) proactive personal assistant that works fully offline by default, with optional cloud AI enhancements (Gemini NLU & Coach, Fish Audio TTS, and read-only IMAP email checks).

---

## 1. Universal Input

Home is the assistant:
- **"Tell me anything"**: Centered prompt accepting any natural language goal, task, note, reminder, or question by voice or text. No forms.
- **Pulsing Mic**: Tap the circular microphone button; an animated outer ring indicates voice capture. The spoken reply is read aloud via TextToSpeech.
- **Stop Speech**: Stop button immediately silences spoken playback at any time.

---

## 2. Generic Items Architecture (Room v2)

Unified `Item` entity replaces single-purpose tables:
- `Item(id, type, title, details, dueAt, durationMinutes, priority, status, repeatRule, targetAmount, unit, progress, contactName, sourceText, createdAt)`
- **Types supported**: `TASK`, `GOAL`, `NOTE`, `REMINDER`, `HABIT`.
- **Non-Destructive Room Migration (v1 -> v2)**: Real SQL migration (`MIGRATION_1_2`) adds `items`, `facts`, and `conversation_turns` tables while preserving existing checklist data, daily logs, and revenue goals.
- **Computed Daily Rate**: Goals with amounts and deadlines (e.g., "320 pages by Friday") compute daily required amounts (`(targetAmount - progress) / daysRemaining`) dynamically for any unit.
- **Searchable Notes**: Notes can be searched by title or content, read aloud with one tap, or attached to exact reminders.

---

## 3. Planner: Now or Later

When a new item or task arrives:
1. Evaluates today's unfinished checklist, active alarms, and free time between user's wake time (default 06:30) and quiet hours (default 22:00).
2. Proposes a recommendation: `Now`, `Later today at HH:mm`, `Tomorrow at HH:mm`, or `Drop`, accompanied by a one-sentence rationale.
3. Shows interactive confirmation buttons and accepts spoken `yes` or `no`.
4. **Guaranteed Safety**: Saves nothing to the database until explicitly accepted. Never reschedules existing items without user permission.

---

## 4. How It Understands

- **Online & AI Enabled**: Queries Gemini via standard `HttpURLConnection` and `org.json` with a 6-second timeout. Returns structured JSON `{"reply": "...", "actions": [...]}` supporting `create_item`, `update_item`, `complete_item`, `snooze`, `add_note`, `propose_plan`, `remember_fact`, and `read_emails`.
- **Offline / AI Off**: A local intent parser (`AssistantParser.kt`) processes commands:
  - `remind me to message <name> at <time>` -> Person reminder
  - `remind me to <action> at/tomorrow <time>` -> Scheduled reminder
  - `note: <text>` -> Searchable note
  - `remember that <fact>` -> Persistent memory fact
  - `add task <text>` -> Task item
  - `done <title>` -> Completion
  - *Fallback*: Unparsed text is saved as a note and explained clearly.

---

## 5. Proactive Engine

The assistant acts proactively in the background:
- **Wake-up Alarm**: Uses `AlarmManager.setAlarmClock`. Sounds an alarm, speaks the day's plan, and repeats until dismissed or snoozed.
- **Scheduled Briefings**: Morning (07:30), midday (12:30), evening (19:30), Sunday review (18:00), and custom times, utilizing exact alarms and foreground TTS with lifecycle `UtteranceProgressListener`.
- **Person Reminders**: e.g., "remind me to message Tola at 4 pm". Posts a heads-up notification with action buttons: `[Open chat]`, `[Done]`, `[Snooze 15m]`. Tapping `Open chat` launches the system app chooser to message via WhatsApp or SMS. The app never sends messages autonomously.
- **Read-Only IMAP Email Check**: WorkManager periodic check every 30 minutes with Wi-Fi only toggle. Identifies priority/urgent sender subjects; strictly suppresses voice read-aloud between 22:00 and wake-up time.
- **Reboot & Update Recovery**: `BootReceiver` and `MY_PACKAGE_REPLACED` reschedule all alarms and workers.

---

## 6. Import from Web Goal Tracker

Imports exported JSON from `index.html` (containing `settings`, `days`, `weeks`, `months`, `pipeline`):
- Maps settings to `Goal` and `UserSettings`.
- Maps days into `DailyLog`.
- Maps pipeline stages (`setupOnce`, `everyDay`, `followUpCadence`, etc.) into `Item` tasks.
- Maps months into `Item` revenue goals.
- **Idempotent**: Prevents duplicates across re-imports.

---

## 7. Data Safety, Privacy & Memory

- **Strict Key Segregation**: Gemini API key, Fish Audio API key, and IMAP app passwords are stored strictly in app-private `SharedPreferences`. Keys never appear in code, logs, or JSON backup exports.
- **Backup & Restore**: Settings > "Back up now" generates sanitized JSON and opens the Android share sheet. Sunday reminder prompts weekly backup.
- **Memory Facts**: Table `facts(id, text, category, createdAt)` stores facts ("remember that..."), provides a view/delete dialog, and injects facts into the AI context.
- **Chat History**: Persists the last 50 conversational turns with a dedicated "Clear History" button.

---

## 8. Screens & Visual Design

- **Four Navigation Tabs**: `Home`, `Plan`, `Notes`, `Settings`.
- **Dark Theme by Default**: Background `#0E1412`, Cards `#161F1C`, Surface `#1E2A27`, Text `#F2F1EC`, Accent Teal `#2BA5A0`, Alert `#E05349`.
- **Typography & Geometry**: 32sp system serif greeting on Home screen. Strict 8px corner radius throughout (`Shapes8px`). Zero pill buttons, zero gradients, zero emojis, zero em dashes.

---

## 9. Signing & Build

- **Debug Keystore**: Configured in `app/build.gradle.kts` using `app/debug.keystore` (password: `android`, alias: `androiddebugkey`, key password: `android`).
- **GitHub Actions**: Continuous workflow in `.github/workflows/build-apk.yml` builds debug APK with `gradle/actions/setup-gradle` without requiring a wrapper JAR.
- **Local Build**:
  ```bash
  gradle assembleDebug
  ```
