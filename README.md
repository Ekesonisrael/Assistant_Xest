# Xestlab Proactive Personal Assistant (Android)

Native Android (`Kotlin`, `Jetpack Compose`, `Room`, `DataStore`, `WorkManager`, `EncryptedSharedPreferences`) proactive personal assistant and executive coach built for **King Israel** (`com.xestlab.assistant`).

---

## 1. Secure API Storage & Resilient Error Handling
- **`EncryptedSharedPreferences` (`AES256_GCM`)**: Securely stores the Gemini API key, Groq API key, ElevenLabs API key, and IMAP app password via AndroidX Security Crypto (`MasterKey.KeyScheme.AES256_GCM`). Credentials are never stored in plain text or included in backup exports.
- **Strict OkHttp Timeouts & Spoken Recovery**: All AI network calls run through `OkHttpClient` with strict connect/read/write/call timeouts and `try/catch` guards. If an API call fails or a key is invalid, the assistant speaks aloud:
  > `"I cannot reach the AI brain right now. Check your internet or API key."`

---

## 2. Dual LLM Routing (Gemini & Groq)
- **Settings Provider Toggle**: Switch seamlessly between **Gemini** and **Groq** in Settings (`Enable AI Brain Routing` + `Gemini` / `Groq` selector).
- **Groq OpenAI-Compatible Endpoint**: Posts via `OkHttp` to `https://api.groq.com/openai/v1/chat/completions` with `Authorization: Bearer <Groq_API_Key>` using model `llama3-70b-8192` and parses the OpenAI JSON format (`choices[0].message.content`).
- **Executive Coach System Prompt**:
  > `"You are Xestlab Assistant, a strict but supportive business coach for King Israel. Keep replies conversational, short, and punchy."`
- **Strict Note Discipline**: Conversational questions and voice chats are **never** saved as notes. Notes are only saved when explicitly prefixed with `"Note: [text]"` (or added directly on the Notes tab).

---

## 3. Proper Voice Engine (ElevenLabs + Natural Android TTS Fallback)
- **ElevenLabs Primary Voice**: When an ElevenLabs API key is saved in Settings, speech routes through `https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM` (Rachel) and plays the returned MP3 via `MediaPlayer`.
- **Natural Android TTS Fallback**: When no ElevenLabs key is configured (or offline), `VoiceEngine` falls back automatically to native Android `TextToSpeech` tuned to **`speechRate = 0.9f`** and **`pitch = 1.0f`**—used across daily briefings, congratulations prompts on task completion, chat responses, and alarms.

---

## 4. Smart Email Checker (IMAP to Task)
- **30-Minute Background Sync**: `EmailCheckWorker` runs every 30 minutes via `WorkManager` over IMAPS (`993`) to read unread inbox messages into Room (`unread_emails` table, `AppDatabase` v3).
- **AI + Heuristic Task & Invoice Extraction**: Unread messages that look like tasks or invoices are analyzed by the active LLM (with offline keyword fallback), trigger a notification with an **`Add to Tasks`** action button (`EmailTaskActionReceiver`), and appear on the Home screen with one-tap **`Add to Tasks`** promotion.
- **Voice Email Query**: Ask `"What did I get in my email today?"` by voice or text to have the assistant read out unread email senders and subjects from the local Room cache.

---

## 5. UI, Onboarding, JSON Pipeline & 7:30 AM Wake-Up Alarm
- **First-Launch Name Onboarding (`DataStore`)**: Prompts for your name on first launch (pre-filled for `King Israel`) and persists it in Jetpack `DataStore<Preferences>`.
- **Centered Home Microphone & Clean 4-Tab Bar**: Features a centered custom vector microphone button with live `SpeechRecognizer` transcript feedback and a clean 4-tab bottom navigation bar (`Home`, `Plan`, `Notes`, `Settings`).
- **7:30 AM Device Wake-Up Alarm**: Scheduled with `AlarmManager.setAlarmClock`, wakes the screen with a `PARTIAL_WAKE_LOCK` + full-screen heads-up notification, and speaks naturally:
  > `"Good morning King Israel, how are you doing? Let's start with..."`
- **JSON Pipeline Import & Room v3**: Retains idempotent Web Goal Tracker JSON pipeline import, checklist completion congratulations TTS, Now-or-Later planner, person reminders, and memory facts.

---

## Build Debug APK
```bash
./gradlew assembleDebug
```
