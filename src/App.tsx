/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Home, CalendarCheck, FileText, Settings, MessageSquare, Copy } from 'lucide-react';
import {
  ActiveAlertBanner,
  AssistantDatabaseState,
  AssistantResponse,
  ChatMessage,
  Goal,
  Item,
  ItemType,
  MonthlyRecord,
  PlannerProposal,
  UserSettings,
  WeeklyOverrideRecord,
} from './types';
import { normalizeTime, parseOfflineIntent } from './engine/assistantParser';
import { formatTodayDateString, proposePlanForItem } from './engine/plannerEngine';
import {
  buildAiLocalContext,
  createDefaultState,
  exportSanitizedJson,
  loadDatabaseState,
  mergeImportedJson,
  saveDatabaseState,
} from './engine/storage';
import { HomeView } from './components/HomeView';
import { PlanView } from './components/PlanView';
import { NotesView } from './components/NotesView';
import { SettingsView } from './components/SettingsView';

type MainTab = 'HOME' | 'PLAN' | 'NOTES' | 'SETTINGS';

function parseTimeToMillis(timeStr: string, isTomorrow = false): number {
  const normalized = normalizeTime(timeStr);
  const [hStr, mStr] = normalized.split(':');
  const h = parseInt(hStr || '12', 10);
  const m = parseInt(mStr || '0', 10);
  const d = new Date();
  if (isTomorrow) d.setDate(d.getDate() + 1);
  d.setHours(h, m, 0, 0);
  if (d.getTime() <= Date.now() && !isTomorrow) {
    d.setDate(d.getDate() + 1);
  }
  return d.getTime();
}

function isInsideQuietHours(settings: UserSettings, now: Date = new Date()): boolean {
  if (!settings.quietHoursEnabled) return false;
  const cur = now.getHours() * 60 + now.getMinutes();
  const [sh, sm] = settings.quietHoursStart.split(':').map((v) => parseInt(v, 10) || 0);
  const [eh, em] = settings.quietHoursEnd.split(':').map((v) => parseInt(v, 10) || 0);
  const startMin = sh * 60 + sm;
  const endMin = eh * 60 + em;
  if (startMin <= endMin) {
    return cur >= startMin && cur < endMin;
  }
  return cur >= startMin || cur < endMin;
}

export default function App() {
  const [dbState, setDbState] = useState<AssistantDatabaseState>(() => loadDatabaseState());
  const [activeTab, setActiveTab] = useState<MainTab>('HOME');

  const [messages, setMessages] = useState<ChatMessage[]>(() => {
    const loaded = loadDatabaseState();
    if (loaded.conversationTurns.length > 0) {
      return loaded.conversationTurns.slice(-50).map((t) => ({
        id: String(t.id),
        sender: t.sender === 'USER' ? 'You' : 'Assistant',
        text: t.text,
        executedActions: [],
        timestamp: t.timestamp,
      }));
    }
    return [
      {
        id: 'welcome-1',
        sender: 'Assistant',
        text: 'Ready. Tell me anything: goals, tasks, notes, reminders, or questions.',
        executedActions: [],
        timestamp: Date.now(),
      },
    ];
  });

  const [activeProposal, setActiveProposal] = useState<PlannerProposal | null>(null);
  const [isProcessing, setIsProcessing] = useState(false);
  const [isSpeaking, setIsSpeaking] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [activeAlert, setActiveAlert] = useState<ActiveAlertBanner | null>(null);
  const [chatChooser, setChatChooser] = useState<{ contactName: string; hint: string } | null>(
    null
  );
  const [testWakeAt, setTestWakeAt] = useState<number | null>(null);

  const firedScheduleKeysRef = useRef<Set<string>>(new Set());
  const stateRef = useRef(dbState);
  stateRef.current = dbState;

  useEffect(() => {
    saveDatabaseState(dbState);
  }, [dbState]);

  const showToast = useCallback((msg: string) => {
    setToast(msg);
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 3200);
    return () => clearTimeout(timer);
  }, [toast]);

  const speakText = useCallback((text: string, respectQuietHours = false) => {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    const currentSettings = stateRef.current.settings;
    if (respectQuietHours && isInsideQuietHours(currentSettings)) {
      return;
    }
    try {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.rate = currentSettings.voiceSpeed || 1.0;
      if (currentSettings.voiceUri) {
        const matched = window.speechSynthesis
          .getVoices()
          .find((v) => v.voiceURI === currentSettings.voiceUri);
        if (matched) utterance.voice = matched;
      }
      utterance.onstart = () => setIsSpeaking(true);
      utterance.onend = () => setIsSpeaking(false);
      utterance.onerror = () => setIsSpeaking(false);
      window.speechSynthesis.speak(utterance);
    } catch {
      setIsSpeaking(false);
    }
  }, []);

  const stopSpeaking = useCallback(() => {
    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    setIsSpeaking(false);
  }, []);

  const postBrowserNotification = useCallback((title: string, body: string) => {
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      try {
        new Notification(title, { body });
      } catch {
        // Ignore iframe notification restrictions
      }
    }
  }, []);

  const triggerCoachBriefing = useCallback(
    async (reminderTitle: string, isAutoBackground = false) => {
      const st = stateRef.current;
      const todayStr = formatTodayDateString();
      const doneSet = new Set(
        st.dailyLogs.filter((l) => l.date === todayStr && l.isDone).map((l) => l.itemId)
      );
      const nextUnfinished = st.checklistItems.find((ci) => !doneSet.has(ci.id));
      let spokenLine = nextUnfinished
        ? `${reminderTitle} for ${st.settings.userName}. Next focus: ${nextUnfinished.title}.`
        : `${reminderTitle} for ${st.settings.userName}. Core daily checklist is complete.`;

      if (reminderTitle.toLowerCase().includes('sunday')) {
        spokenLine += ' Time to back up your data in Settings.';
      }

      if (st.settings.isAiEnabled && navigator.onLine) {
        try {
          const resp = await fetch('/api/ai/coach', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              modelName: st.settings.geminiModel,
              context: buildAiLocalContext(st),
              reminderType: reminderTitle,
            }),
          });
          if (resp.ok) {
            const data = await resp.json();
            if (data.speech) spokenLine = data.speech;
          }
        } catch {
          // Fall back cleanly to local scripted briefing
        }
      }

      setActiveAlert({
        id: `brief-${Date.now()}`,
        kind: reminderTitle.toLowerCase().includes('wake') ? 'WAKEUP' : 'BRIEFING',
        title: reminderTitle,
        message: spokenLine,
        triggeredAt: Date.now(),
      });
      postBrowserNotification(reminderTitle, spokenLine);
      speakText(spokenLine, isAutoBackground);
    },
    [postBrowserNotification, speakText]
  );

  // Proactive Engine Background Monitor (checks wake-up, scheduled briefings, test wake-up, and due items)
  useEffect(() => {
    const interval = setInterval(() => {
      const now = new Date();
      const st = stateRef.current;
      const todayStr = formatTodayDateString(now);
      const hhmm = `${String(now.getHours()).padStart(2, '0')}:${String(
        now.getMinutes()
      ).padStart(2, '0')}`;
      const dayNames = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
      const curDay = dayNames[now.getDay()];

      // 1. Test 1-minute alarm
      if (testWakeAt && Date.now() >= testWakeAt) {
        setTestWakeAt(null);
        triggerCoachBriefing('Wake-up Test Briefing', false);
      }

      // 2. Daily Wake-Up Alarm
      const wakeKey = `wake_${todayStr}_${st.settings.wakeUpTime}`;
      if (hhmm === st.settings.wakeUpTime && !firedScheduleKeysRef.current.has(wakeKey)) {
        firedScheduleKeysRef.current.add(wakeKey);
        triggerCoachBriefing('Wake-up Alarm Briefing', false);
      }

      // 3. Scheduled Briefings
      for (const rem of st.reminders) {
        if (!rem.isActive) continue;
        if (rem.daysOfWeek !== 'ALL' && !rem.daysOfWeek.includes(curDay)) continue;
        const rKey = `rem_${rem.id}_${todayStr}_${rem.timeString}`;
        if (hhmm === rem.timeString && !firedScheduleKeysRef.current.has(rKey)) {
          firedScheduleKeysRef.current.add(rKey);
          if (rem.voiceEnabled) {
            triggerCoachBriefing(rem.title, true);
          } else {
            const msg = `Scheduled check-in: ${rem.title}`;
            setActiveAlert({
              id: rKey,
              kind: 'BRIEFING',
              title: rem.title,
              message: msg,
              triggeredAt: Date.now(),
            });
            postBrowserNotification(rem.title, msg);
          }
        }
      }

      // 4. Due Generic Items & Person Reminders
      for (const item of st.items) {
        if (item.status !== 'PENDING' || !item.dueAt) continue;
        const itemKey = `item_${item.id}_${item.dueAt}`;
        if (
          Date.now() >= item.dueAt &&
          Date.now() - item.dueAt < 60000 &&
          !firedScheduleKeysRef.current.has(itemKey)
        ) {
          firedScheduleKeysRef.current.add(itemKey);
          const isPerson = Boolean(item.contactName);
          const alertMsg = isPerson
            ? `Reminder to message ${item.contactName}: ${item.title}`
            : `Due now: ${item.title}. ${item.details || ''}`.trim();
          setActiveAlert({
            id: itemKey,
            kind: isPerson ? 'PERSON_REMINDER' : 'REMINDER',
            title: item.title,
            message: alertMsg,
            contactName: item.contactName,
            itemId: item.id,
            triggeredAt: Date.now(),
          });
          postBrowserNotification(item.title, alertMsg);
          speakText(alertMsg, true);
        }
      }
    }, 10000);

    return () => clearInterval(interval);
  }, [postBrowserNotification, speakText, testWakeAt, triggerCoachBriefing]);

  const appendTurn = useCallback(
    (
      sender: 'You' | 'Assistant',
      text: string,
      executedActions: string[] = [],
      proposal?: PlannerProposal | null
    ) => {
      const msg: ChatMessage = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        sender,
        text,
        executedActions,
        proposal,
        timestamp: Date.now(),
      };
      setMessages((prev) => [...prev.slice(-49), msg]);
      setDbState((prev) => {
        const nextId = prev.conversationTurns.reduce((m, t) => Math.max(m, t.id), 0) + 1;
        return {
          ...prev,
          conversationTurns: [
            ...prev.conversationTurns.slice(-49),
            {
              id: nextId,
              sender: sender === 'You' ? 'USER' : 'ASSISTANT',
              text,
              timestamp: Date.now(),
            },
          ],
        };
      });
    },
    []
  );

  const acceptProposal = useCallback(
    (proposal: PlannerProposal, customTime?: string, isSpoken = false) => {
      const finalDueAt = customTime
        ? parseTimeToMillis(customTime)
        : proposal.suggestedDueAtMillis;
      const timeLabel = customTime || proposal.suggestedTime || 'Confirmed';

      setDbState((prev) => {
        const nextId = prev.items.reduce((m, it) => Math.max(m, it.id), 0) + 1;
        const savedItem: Item = {
          ...proposal.item,
          id: nextId,
          dueAt: finalDueAt,
          status: 'PENDING',
          createdAt: Date.now(),
        };
        return {
          ...prev,
          items: [savedItem, ...prev.items],
        };
      });

      setActiveProposal(null);
      const confirmMsg = `Scheduled: ${proposal.item.title} (${timeLabel}).`;
      appendTurn('Assistant', confirmMsg, ['Scheduled item']);
      if (isSpoken) {
        speakText(`Confirmed. Scheduled ${proposal.item.title}.`);
      }
    },
    [appendTurn, speakText]
  );

  const rejectProposal = useCallback(
    (isSpoken = false) => {
      setActiveProposal(null);
      const dropMsg = 'Dropped. Nothing was saved.';
      appendTurn('Assistant', dropMsg);
      if (isSpoken) speakText(dropMsg);
    },
    [appendTurn, speakText]
  );

  const handleOfflineCommand = useCallback(
    (input: string, isSpoken: boolean) => {
      const st = stateRef.current;
      const parsed = parseOfflineIntent(input);

      const makeCandidate = (partial: Partial<Item> & { type: ItemType; title: string }): Item => ({
        id: 0,
        details: '',
        dueAt: null,
        durationMinutes: 30,
        priority: 'NORMAL',
        status: 'PENDING',
        repeatRule: null,
        targetAmount: null,
        unit: null,
        progress: 0,
        contactName: null,
        sourceText: input,
        createdAt: Date.now(),
        ...partial,
      });

      if (parsed.kind === 'PersonReminder') {
        const dueAt = parseTimeToMillis(parsed.time);
        const candidate = makeCandidate({
          type: 'REMINDER',
          title: `Message ${parsed.contactName}`,
          details: parsed.details,
          dueAt,
          contactName: parsed.contactName,
        });
        const prop = proposePlanForItem(st, candidate);
        setActiveProposal(prop);
        const reply = `Person reminder for ${parsed.contactName} at ${parsed.time}. ${prop.rationale}`;
        appendTurn('Assistant', reply, [], prop);
        if (isSpoken) speakText(prop.summarySpeech);
        return;
      }

      if (parsed.kind === 'Reminder') {
        const dueAt = parseTimeToMillis(parsed.time, parsed.isTomorrow);
        const candidate = makeCandidate({
          type: 'REMINDER',
          title: parsed.title,
          dueAt,
        });
        const prop = proposePlanForItem(st, candidate);
        setActiveProposal(prop);
        const reply = `Reminder '${parsed.title}' for ${parsed.time}. ${prop.rationale}`;
        appendTurn('Assistant', reply, [], prop);
        if (isSpoken) speakText(prop.summarySpeech);
        return;
      }

      if (parsed.kind === 'Task') {
        const candidate = makeCandidate({
          type: 'TASK',
          title: parsed.title,
          durationMinutes: parsed.durationMinutes,
        });
        const prop = proposePlanForItem(st, candidate);
        setActiveProposal(prop);
        const reply = `New task '${parsed.title}'. ${prop.rationale}`;
        appendTurn('Assistant', reply, [], prop);
        if (isSpoken) speakText(prop.summarySpeech);
        return;
      }

      if (parsed.kind === 'Goal') {
        const dueAt = Date.now() + parsed.daysRemaining * 86400000;
        const candidate = makeCandidate({
          type: 'GOAL',
          title: parsed.title,
          targetAmount: parsed.amount,
          unit: parsed.unit,
          dueAt,
        });
        const prop = proposePlanForItem(st, candidate);
        setActiveProposal(prop);
        const reply = `Goal '${parsed.title}'. Target: ${parsed.amount} ${parsed.unit}. ${prop.rationale}`;
        appendTurn('Assistant', reply, [], prop);
        if (isSpoken) speakText(prop.summarySpeech);
        return;
      }

      if (parsed.kind === 'Note') {
        setDbState((prev) => {
          const nextId = prev.items.reduce((m, it) => Math.max(m, it.id), 0) + 1;
          return {
            ...prev,
            items: [makeCandidate({ id: nextId, type: 'NOTE', title: parsed.text }), ...prev.items],
          };
        });
        const reply = `Saved note: ${parsed.text}.`;
        appendTurn('Assistant', reply, ['Saved note']);
        if (isSpoken) speakText(reply);
        return;
      }

      if (parsed.kind === 'RememberFact') {
        setDbState((prev) => {
          const nextId = prev.facts.reduce((m, f) => Math.max(m, f.id), 0) + 1;
          return {
            ...prev,
            facts: [
              { id: nextId, text: parsed.fact, category: 'MEMORY', createdAt: Date.now() },
              ...prev.facts,
            ],
          };
        });
        const reply = `Remembered: ${parsed.fact}.`;
        appendTurn('Assistant', reply, ['Stored fact']);
        if (isSpoken) speakText(reply);
        return;
      }

      if (parsed.kind === 'Done') {
        const q = parsed.targetName.toLowerCase();
        const matchedItem = st.items.find(
          (i) => i.status === 'PENDING' && i.title.toLowerCase().includes(q)
        );
        if (matchedItem) {
          setDbState((prev) => ({
            ...prev,
            items: prev.items.map((i) =>
              i.id === matchedItem.id ? { ...i, status: 'COMPLETED' } : i
            ),
          }));
          const reply = `Marked '${matchedItem.title}' completed.`;
          appendTurn('Assistant', reply, [`Completed ${matchedItem.title}`]);
          if (isSpoken) speakText(reply);
          return;
        }
        const matchedChecklist = st.checklistItems.find((ci) =>
          ci.title.toLowerCase().includes(q)
        );
        if (matchedChecklist) {
          const todayStr = formatTodayDateString();
          setDbState((prev) => {
            const filtered = prev.dailyLogs.filter(
              (l) => !(l.date === todayStr && l.itemId === matchedChecklist.id)
            );
            const nextId = prev.dailyLogs.reduce((m, l) => Math.max(m, l.id), 0) + 1;
            return {
              ...prev,
              dailyLogs: [
                ...filtered,
                {
                  id: nextId,
                  date: todayStr,
                  itemId: matchedChecklist.id,
                  isDone: true,
                  countValue: matchedChecklist.isCountType ? 10 : 0,
                  timestamp: Date.now(),
                },
              ],
            };
          });
          const reply = `Marked '${matchedChecklist.title}' done.`;
          appendTurn('Assistant', reply, [`Marked ${matchedChecklist.title} done`]);
          if (isSpoken) speakText(reply);
          return;
        }
        const reply = `No pending item matched '${parsed.targetName}'.`;
        appendTurn('Assistant', reply);
        if (isSpoken) speakText(reply);
        return;
      }

      if (parsed.kind === 'Question') {
        const pendingCount = st.items.filter(
          (i) => i.status === 'PENDING' && !i.sourceText.startsWith('web_pipeline_')
        ).length;
        const reply = !st.settings.isAiEnabled
          ? `AI is disabled in Settings. You have ${pendingCount} active tasks queued.`
          : `Offline mode active. You have ${pendingCount} pending items on your schedule.`;
        appendTurn('Assistant', reply);
        if (isSpoken) speakText(reply);
        return;
      }

      // FallbackNote
      setDbState((prev) => {
        const nextId = prev.items.reduce((m, it) => Math.max(m, it.id), 0) + 1;
        return {
          ...prev,
          items: [makeCandidate({ id: nextId, type: 'NOTE', title: parsed.text }), ...prev.items],
        };
      });
      const fallbackReply = 'Saved text as a note.';
      appendTurn('Assistant', fallbackReply, ['Saved as note']);
      if (isSpoken) speakText(fallbackReply);
    },
    [appendTurn, speakText]
  );

  const handleUniversalInput = useCallback(
    async (rawInput: string, isSpoken: boolean) => {
      const trimmed = rawInput.trim();
      if (!trimmed || isProcessing) return;

      // Check active proposal confirmation ("yes" / "no")
      if (activeProposal) {
        const lower = trimmed.toLowerCase();
        if (
          lower === 'yes' ||
          lower.startsWith('yes ') ||
          lower.includes('accept') ||
          lower.includes('do it') ||
          lower.includes('confirm')
        ) {
          appendTurn('You', trimmed);
          acceptProposal(activeProposal, undefined, isSpoken);
          return;
        }
        if (
          lower === 'no' ||
          lower.startsWith('no ') ||
          lower.includes('drop') ||
          lower.includes('cancel') ||
          lower.includes('reject')
        ) {
          appendTurn('You', trimmed);
          rejectProposal(isSpoken);
          return;
        }
      }

      appendTurn('You', trimmed);
      setIsProcessing(true);

      const st = stateRef.current;
      if (st.settings.isAiEnabled && navigator.onLine) {
        try {
          const resp = await fetch('/api/ai/assistant', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              modelName: st.settings.geminiModel,
              context: buildAiLocalContext(st),
              sessionHistory: messages.slice(-6).map((m) => ({
                sender: m.sender,
                text: m.text,
              })),
              userInput: trimmed,
            }),
          });

          if (resp.ok) {
            const data: AssistantResponse = await resp.json();
            const executedNotes: string[] = [];

            for (const action of data.actions || []) {
              const actionName = action.name.toLowerCase();
              if (actionName === 'create_item' || actionName === 'propose_plan') {
                const itemType = (
                  ['TASK', 'GOAL', 'NOTE', 'REMINDER', 'HABIT'].includes(
                    (action.type || '').toUpperCase()
                  )
                    ? (action.type || 'TASK').toUpperCase()
                    : 'TASK'
                ) as ItemType;

                const candidate: Item = {
                  id: 0,
                  type: itemType,
                  title: action.title || trimmed,
                  details: action.details || '',
                  dueAt: action.time ? parseTimeToMillis(action.time) : null,
                  durationMinutes: 30,
                  priority: 'NORMAL',
                  status: 'PENDING',
                  repeatRule: null,
                  targetAmount: action.targetAmount ?? null,
                  unit: action.unit ?? null,
                  progress: 0,
                  contactName: action.contactName ?? null,
                  sourceText: trimmed,
                  createdAt: Date.now(),
                };

                const proposal = proposePlanForItem(st, candidate);
                setActiveProposal(proposal);
                const fullReply = `${data.reply}\n\nRecommendation: ${proposal.rationale}`;
                appendTurn('Assistant', fullReply, executedNotes, proposal);
                setIsProcessing(false);
                if (isSpoken) speakText(proposal.summarySpeech);
                return;
              }

              if (actionName === 'complete_item') {
                const targetTitle = (action.title || trimmed).toLowerCase();
                const found = st.items.find(
                  (i) => i.status === 'PENDING' && i.title.toLowerCase().includes(targetTitle)
                );
                if (found) {
                  setDbState((prev) => ({
                    ...prev,
                    items: prev.items.map((i) =>
                      i.id === found.id ? { ...i, status: 'COMPLETED' } : i
                    ),
                  }));
                  executedNotes.push(`Completed: ${found.title}`);
                } else {
                  const clMatch = st.checklistItems.find((ci) =>
                    ci.title.toLowerCase().includes(targetTitle)
                  );
                  if (clMatch) {
                    const todayStr = formatTodayDateString();
                    setDbState((prev) => {
                      const nextId = prev.dailyLogs.reduce((m, l) => Math.max(m, l.id), 0) + 1;
                      return {
                        ...prev,
                        dailyLogs: [
                          ...prev.dailyLogs.filter(
                            (l) => !(l.date === todayStr && l.itemId === clMatch.id)
                          ),
                          {
                            id: nextId,
                            date: todayStr,
                            itemId: clMatch.id,
                            isDone: true,
                            countValue: clMatch.isCountType ? 10 : 0,
                            timestamp: Date.now(),
                          },
                        ],
                      };
                    });
                    executedNotes.push(`Marked ${clMatch.title} done`);
                  }
                }
              }

              if (actionName === 'add_note') {
                const noteTitle = action.title || trimmed;
                setDbState((prev) => {
                  const nextId = prev.items.reduce((m, it) => Math.max(m, it.id), 0) + 1;
                  return {
                    ...prev,
                    items: [
                      {
                        id: nextId,
                        type: 'NOTE',
                        title: noteTitle,
                        details: action.details || '',
                        dueAt: null,
                        durationMinutes: 0,
                        priority: 'NORMAL',
                        status: 'PENDING',
                        repeatRule: null,
                        targetAmount: null,
                        unit: null,
                        progress: 0,
                        contactName: null,
                        sourceText: trimmed,
                        createdAt: Date.now(),
                      },
                      ...prev.items,
                    ],
                  };
                });
                executedNotes.push(`Saved note: ${noteTitle}`);
              }

              if (actionName === 'remember_fact') {
                const factText = action.details || action.title || trimmed;
                setDbState((prev) => {
                  const nextId = prev.facts.reduce((m, f) => Math.max(m, f.id), 0) + 1;
                  return {
                    ...prev,
                    facts: [
                      { id: nextId, text: factText, category: 'MEMORY', createdAt: Date.now() },
                      ...prev.facts,
                    ],
                  };
                });
                executedNotes.push(`Remembered: ${factText}`);
              }

              if (actionName === 'snooze') {
                const mins = action.minutes || 15;
                setTestWakeAt(Date.now() + mins * 60000);
                executedNotes.push(`Snoozed for ${mins} minutes`);
              }
            }

            appendTurn('Assistant', data.reply, executedNotes);
            setIsProcessing(false);
            if (isSpoken) speakText(data.reply);
            return;
          }
        } catch {
          // Fall back seamlessly to offline intent parser
        }
      }

      handleOfflineCommand(trimmed, isSpoken);
      setIsProcessing(false);
    },
    [
      acceptProposal,
      activeProposal,
      appendTurn,
      handleOfflineCommand,
      isProcessing,
      messages,
      rejectProposal,
      speakText,
    ]
  );

  // Handlers for Plan / Notes / Settings
  const handleToggleChecklist = (itemId: string, done: boolean, count = 0) => {
    const todayStr = formatTodayDateString();
    setDbState((prev) => {
      const filtered = prev.dailyLogs.filter(
        (l) => !(l.date === todayStr && l.itemId === itemId)
      );
      const nextId = prev.dailyLogs.reduce((m, l) => Math.max(m, l.id), 0) + 1;
      return {
        ...prev,
        dailyLogs: [
          ...filtered,
          {
            id: nextId,
            date: todayStr,
            itemId,
            isDone: done,
            countValue: done ? count : 0,
            reasonNotDone: done ? null : 'unmarked',
            timestamp: Date.now(),
          },
        ],
      };
    });
    showToast(done ? 'Checklist item marked done' : 'Checklist item unmarked');
  };

  const handleSetTodayMessages = (count: number) => {
    const todayStr = formatTodayDateString();
    setDbState((prev) => {
      const filtered = prev.dailyLogs.filter(
        (l) => !(l.date === todayStr && l.itemId === 'messages10')
      );
      const nextId = prev.dailyLogs.reduce((m, l) => Math.max(m, l.id), 0) + 1;
      return {
        ...prev,
        dailyLogs: [
          ...filtered,
          {
            id: nextId,
            date: todayStr,
            itemId: 'messages10',
            isDone: count >= 10,
            countValue: count,
            timestamp: Date.now(),
          },
        ],
      };
    });
  };

  const handleUpdateDayNote = (date: string, note: string) => {
    setDbState((prev) => ({
      ...prev,
      dayNotes: { ...prev.dayNotes, [date]: note },
    }));
  };

  const handleCompleteItem = (item: Item) => {
    setDbState((prev) => ({
      ...prev,
      items: prev.items.map((i) => (i.id === item.id ? { ...i, status: 'COMPLETED' } : i)),
    }));
    showToast(`Completed: ${item.title}`);
  };

  const handleReopenItem = (item: Item) => {
    setDbState((prev) => ({
      ...prev,
      items: prev.items.map((i) => (i.id === item.id ? { ...i, status: 'PENDING' } : i)),
    }));
  };

  const handleUpdateGoalProgress = (item: Item, newProgress: number) => {
    setDbState((prev) => ({
      ...prev,
      items: prev.items.map((i) =>
        i.id === item.id
          ? {
              ...i,
              progress: newProgress,
              status:
                i.targetAmount != null && newProgress >= i.targetAmount ? 'COMPLETED' : i.status,
            }
          : i
      ),
    }));
  };

  const handleUpdateWeeklyOverride = (weekIndex: number, patch: Partial<WeeklyOverrideRecord>) => {
    setDbState((prev) => ({
      ...prev,
      weeklyOverrides: {
        ...prev.weeklyOverrides,
        [weekIndex]: { ...(prev.weeklyOverrides[weekIndex] || {}), ...patch },
      },
    }));
  };

  const handleUpdateMonthlyRecord = (monthIndex: number, patch: Partial<MonthlyRecord>) => {
    setDbState((prev) => ({
      ...prev,
      monthlyRecords: prev.monthlyRecords.map((m, idx) =>
        idx === monthIndex ? { ...m, ...patch } : m
      ),
    }));
  };

  const handleCopyText = async (text: string, label: string) => {
    try {
      await navigator.clipboard.writeText(text);
      showToast(label);
    } catch {
      showToast('Clipboard unavailable in this frame');
    }
  };

  const handleCreateNote = (title: string, details: string) => {
    setDbState((prev) => {
      const nextId = prev.items.reduce((m, it) => Math.max(m, it.id), 0) + 1;
      return {
        ...prev,
        items: [
          {
            id: nextId,
            type: 'NOTE',
            title,
            details,
            dueAt: null,
            durationMinutes: 0,
            priority: 'NORMAL',
            status: 'PENDING',
            repeatRule: null,
            targetAmount: null,
            unit: null,
            progress: 0,
            contactName: null,
            sourceText: `note: ${title}`,
            createdAt: Date.now(),
          },
          ...prev.items,
        ],
      };
    });
    showToast('Note saved');
  };

  const handleAttachReminderToNote = (note: Item, timeString: string) => {
    const dueAt = parseTimeToMillis(timeString);
    setDbState((prev) => ({
      ...prev,
      items: prev.items.map((i) => (i.id === note.id ? { ...i, dueAt } : i)),
    }));
    showToast(`Reminder set for ${timeString}`);
  };

  const handleDeleteItem = (item: Item) => {
    setDbState((prev) => ({
      ...prev,
      items: prev.items.filter((i) => i.id !== item.id),
    }));
    showToast('Item removed');
  };

  const handleExportBackup = () => {
    const json = exportSanitizedJson(dbState);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `xestlab-assistant-backup-${formatTodayDateString()}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    showToast('Sanitized JSON backup downloaded');
  };

  const handleImportBackupJson = (jsonStr: string): boolean => {
    try {
      const merged = mergeImportedJson(dbState, jsonStr);
      setDbState(merged);
      showToast('Backup imported successfully');
      return true;
    } catch {
      showToast('Invalid JSON backup file');
      return false;
    }
  };

  const handleTestAiConnection = async (): Promise<{ ok: boolean; message: string }> => {
    try {
      const resp = await fetch('/api/ai/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modelName: dbState.settings.geminiModel }),
      });
      const data = await resp.json();
      return {
        ok: Boolean(data.ok),
        message: data.message || (resp.ok ? 'Connected.' : 'Connection failed.'),
      };
    } catch {
      return { ok: false, message: 'Could not reach server AI endpoint.' };
    }
  };

  const pendingItems = dbState.items.filter((i) => i.status === 'PENDING');
  const noteItems = dbState.items.filter((i) => i.type === 'NOTE');

  return (
    <div className="min-h-screen bg-[#0E1412] text-[#F2F1EC]">
      {activeTab === 'HOME' && (
        <HomeView
          settings={dbState.settings}
          messages={messages}
          activeProposal={activeProposal}
          pendingItems={pendingItems}
          isProcessing={isProcessing}
          isSpeaking={isSpeaking}
          activeAlert={activeAlert}
          onSendInput={handleUniversalInput}
          onAcceptProposal={(prop, customTime) => acceptProposal(prop, customTime, false)}
          onRejectProposal={() => rejectProposal(false)}
          onCompleteItem={handleCompleteItem}
          onStopSpeaking={stopSpeaking}
          onOpenChatContact={(contactName, hint) => setChatChooser({ contactName, hint })}
          onSnoozeAlert={(alert, mins) => {
            if (alert.itemId) {
              const nextDue = Date.now() + mins * 60000;
              setDbState((prev) => ({
                ...prev,
                items: prev.items.map((i) =>
                  i.id === alert.itemId ? { ...i, dueAt: nextDue } : i
                ),
              }));
            } else {
              setTestWakeAt(Date.now() + mins * 60000);
            }
            setActiveAlert(null);
            showToast(`Snoozed for ${mins} minutes`);
          }}
          onDismissAlert={() => setActiveAlert(null)}
        />
      )}

      {activeTab === 'PLAN' && (
        <PlanView
          state={dbState}
          onToggleChecklist={handleToggleChecklist}
          onSetTodayMessages={handleSetTodayMessages}
          onUpdateDayNote={handleUpdateDayNote}
          onCompleteItem={handleCompleteItem}
          onReopenItem={handleReopenItem}
          onUpdateGoalProgress={handleUpdateGoalProgress}
          onUpdateWeeklyOverride={handleUpdateWeeklyOverride}
          onUpdateMonthlyRecord={handleUpdateMonthlyRecord}
          onTriggerBriefing={(title) => triggerCoachBriefing(title, false)}
          onOpenChatContact={(contactName, hint) => setChatChooser({ contactName, hint })}
          onCopyText={handleCopyText}
        />
      )}

      {activeTab === 'NOTES' && (
        <NotesView
          notes={noteItems}
          onCreateNote={handleCreateNote}
          onReadAloud={(note) => speakText(`${note.title}. ${note.details}`)}
          onAttachReminder={handleAttachReminderToNote}
          onDeleteNote={handleDeleteItem}
        />
      )}

      {activeTab === 'SETTINGS' && (
        <SettingsView
          state={dbState}
          onUpdateSettings={(patch: Partial<UserSettings>) => {
            setDbState((prev) => ({ ...prev, settings: { ...prev.settings, ...patch } }));
            showToast('Settings saved');
          }}
          onUpdateGoals={(patch: Partial<Goal>) => {
            setDbState((prev) => ({ ...prev, goals: { ...prev.goals, ...patch } }));
          }}
          onTestWakeUpInOneMinute={() => {
            setTestWakeAt(Date.now() + 60000);
            showToast('Test wake-up alarm scheduled for 1 minute from now');
          }}
          onTriggerBriefingNow={(title) => triggerCoachBriefing(title, false)}
          onExportBackup={handleExportBackup}
          onImportBackupJson={handleImportBackupJson}
          onAddFact={(text) => {
            setDbState((prev) => {
              const nextId = prev.facts.reduce((m, f) => Math.max(m, f.id), 0) + 1;
              return {
                ...prev,
                facts: [{ id: nextId, text, category: 'MEMORY', createdAt: Date.now() }, ...prev.facts],
              };
            });
            showToast('Memory fact saved');
          }}
          onDeleteFact={(factId) => {
            setDbState((prev) => ({
              ...prev,
              facts: prev.facts.filter((f) => f.id !== factId),
            }));
            showToast('Fact removed');
          }}
          onClearFacts={() => {
            setDbState((prev) => ({ ...prev, facts: [] }));
            showToast('All memory facts cleared');
          }}
          onClearChatHistory={() => {
            setDbState((prev) => ({ ...prev, conversationTurns: [] }));
            setMessages([
              {
                id: `cleared-${Date.now()}`,
                sender: 'Assistant',
                text: 'Conversation history cleared. Ready.',
                executedActions: [],
                timestamp: Date.now(),
              },
            ]);
            showToast('Chat history cleared');
          }}
          onTestAiConnection={handleTestAiConnection}
          onToggleReminderActive={(id) => {
            setDbState((prev) => ({
              ...prev,
              reminders: prev.reminders.map((r) =>
                r.id === id ? { ...r, isActive: !r.isActive } : r
              ),
            }));
          }}
          onToggleReminderVoice={(id) => {
            setDbState((prev) => ({
              ...prev,
              reminders: prev.reminders.map((r) =>
                r.id === id ? { ...r, voiceEnabled: !r.voiceEnabled } : r
              ),
            }));
          }}
          onUpdateReminderTime={(id, timeString) => {
            setDbState((prev) => ({
              ...prev,
              reminders: prev.reminders.map((r) => (r.id === id ? { ...r, timeString } : r)),
            }));
            showToast(`Time updated to ${timeString}`);
          }}
          onAddCustomReminder={(title, timeString, isRepeating) => {
            setDbState((prev) => ({
              ...prev,
              reminders: [
                ...prev.reminders,
                {
                  id: `custom-${Date.now()}`,
                  title,
                  timeString,
                  isRepeating,
                  daysOfWeek: 'ALL',
                  voiceEnabled: true,
                  isActive: true,
                  isBuiltIn: false,
                },
              ],
            }));
            showToast(`Custom briefing added for ${timeString}`);
          }}
          onDeleteCustomReminder={(id) => {
            setDbState((prev) => ({
              ...prev,
              reminders: prev.reminders.filter((r) => r.id !== id),
            }));
            showToast('Custom briefing removed');
          }}
          onTestVoice={() =>
            speakText(
              `Hello ${dbState.settings.userName}. Proactive voice reminders are active.`,
              false
            )
          }
          onResetTargetsDefault={() => {
            const def = createDefaultState();
            setDbState((prev) => ({ ...prev, goals: def.goals }));
            showToast('Studio targets reset to defaults');
          }}
        />
      )}

      {/* Person Reminder Messaging App Chooser Modal (never sends autonomously) */}
      {chatChooser && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
          <div className="w-full max-w-[400px] rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4 space-y-3">
            <div className="flex items-center gap-2 text-[#2BA5A0]">
              <MessageSquare className="w-4 h-4" />
              <h3 className="text-[15px] font-bold text-[#F2F1EC]">
                Open Chat with {chatChooser.contactName}
              </h3>
            </div>
            <p className="text-[12px] text-[#8E9E99]">
              Choose an app to message {chatChooser.contactName}. Messages are never sent
              autonomously.
            </p>
            <div className="space-y-2">
              <a
                href={`https://wa.me/?text=${encodeURIComponent(
                  `Hi ${chatChooser.contactName}, following up regarding: ${chatChooser.hint}`
                )}`}
                target="_blank"
                rel="noopener noreferrer"
                onClick={() => setChatChooser(null)}
                className="w-full rounded-[8px] bg-[#2BA5A0] py-2.5 px-3 text-[13px] font-semibold text-[#0E1412] flex items-center justify-center"
              >
                Open WhatsApp
              </a>
              <a
                href={`sms:?body=${encodeURIComponent(
                  `Hi ${chatChooser.contactName}, following up regarding: ${chatChooser.hint}`
                )}`}
                onClick={() => setChatChooser(null)}
                className="w-full rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-2.5 px-3 text-[13px] font-medium text-[#F2F1EC] flex items-center justify-center hover:border-[#2BA5A0]"
              >
                Open Default SMS App
              </a>
              <button
                type="button"
                onClick={() => {
                  handleCopyText(
                    `Hi ${chatChooser.contactName}, following up regarding: ${chatChooser.hint}`,
                    'Draft message copied to clipboard'
                  );
                  setChatChooser(null);
                }}
                className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] py-2 px-3 text-[12px] text-[#8E9E99] hover:text-[#F2F1EC] flex items-center justify-center gap-1.5 cursor-pointer"
              >
                <Copy className="w-3.5 h-3.5" />
                Copy Draft Message
              </button>
            </div>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={() => setChatChooser(null)}
                className="text-[12px] text-[#8E9E99] hover:text-[#F2F1EC] cursor-pointer"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Toast Notification */}
      {toast && (
        <div className="fixed bottom-20 left-1/2 -translate-x-1/2 z-50 rounded-[8px] border border-[#2BA5A0] bg-[#161F1C] px-4 py-2 text-[12px] font-semibold text-[#F2F1EC] shadow-lg">
          {toast}
        </div>
      )}

      {/* Four-Tab Bottom Navigation */}
      <nav className="fixed bottom-0 left-0 right-0 z-40 h-16 bg-[#161F1C] border-t border-[#24332F]">
        <div className="max-w-[680px] mx-auto h-full grid grid-cols-4">
          {(
            [
              { id: 'HOME', label: 'Home', Icon: Home },
              { id: 'PLAN', label: 'Plan', Icon: CalendarCheck },
              { id: 'NOTES', label: 'Notes', Icon: FileText },
              { id: 'SETTINGS', label: 'Settings', Icon: Settings },
            ] as const
          ).map(({ id, label, Icon }) => {
            const selected = activeTab === id;
            return (
              <button
                key={id}
                type="button"
                onClick={() => setActiveTab(id)}
                className={`h-full flex flex-col items-center justify-center gap-1 border-t-2 transition-colors cursor-pointer ${
                  selected
                    ? 'border-[#2BA5A0] text-[#2BA5A0] font-bold'
                    : 'border-transparent text-[#8E9E99] font-medium hover:text-[#F2F1EC]'
                }`}
              >
                <Icon className="w-4 h-4" />
                <span className="text-[11px]">{label}</span>
              </button>
            );
          })}
        </div>
      </nav>
    </div>
  );
}
