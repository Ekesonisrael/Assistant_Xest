import {
  AssistantDatabaseState,
  ChecklistItem,
  DailyLog,
  Item,
  MonthlyRecord,
  ReminderSchedule,
} from '../types';
import { formatTodayDateString } from './plannerEngine';

const STORAGE_KEY = 'xestlab_assistant_state_v2';

export const PIPELINE_DEFINITIONS: Record<string, { label: string; items: string[] }> = {
  setupOnce: {
    label: 'Stage 1: Setup Once',
    items: [
      'Website tiers written: landing page, business site, e-commerce',
      'Monthly plan written: maintenance and hosting',
      'One-page agreement: 50-70% deposit, balance before handover',
      'Quote template made: middle tier highlighted, valid 7 days',
      'Pricing page on the site, matching PDF',
      'Intake form built: brand assets, references, domain access',
      'Client folder template: drafts, assets, agreement, receipts',
      'Pre-recorded demo video of past projects',
      'Lead sheet initialized: name, business, channel, status',
      'Case study or proof of work posted',
    ],
  },
  everyDay: {
    label: 'Stage 2: Every Day Execution',
    items: [
      '10 personalized messages sent to business owners',
      'Follow-ups due sent',
      'One content post published',
      'Lead sheet updated with new contacts',
    ],
  },
  followUpCadence: {
    label: 'Stage 3: Follow-Up Cadence',
    items: [
      'Day 2 follow-up: quick check-in on quote',
      'Day 4 follow-up: value add or past work sample',
      'Day 7 follow-up: final check before quote expiry',
      'Day 14 follow-up: gentle reconnect',
    ],
  },
  salesConversation: {
    label: 'Stage 4: Sales Conversation',
    items: [
      'Discovery conversation: diagnose pain before quoting',
      'Present middle tier recommendation',
      'Send agreement and invoice for deposit',
      'Confirm receipt of deposit before scheduling start',
      'Send intake form link immediately upon payment',
    ],
  },
  onboarding: {
    label: 'Stage 5: Onboarding & Delivery',
    items: [
      'Kickoff call within 48 hours of deposit',
      'Receive brand assets and content',
      'Domain and hosting credentials verified',
      'Deliver first prototype within 5 working days',
      'Collect structured feedback round 1',
      'Collect structured feedback round 2',
    ],
  },
  afterDelivery: {
    label: 'Stage 6: After Delivery & Retainers',
    items: [
      'Handover training video recorded',
      'Collect final balance payment before DNS live',
      'Request Google review or testimonial',
      'Offer monthly maintenance retainer plan',
      'Check in 30 days post-launch',
    ],
  },
};

const MONTH_LABELS = [
  'Nov 2026',
  'Dec 2026',
  'Jan 2027',
  'Feb 2027',
  'Mar 2027',
  'Apr 2027',
  'May 2027',
  'Jun 2027',
  'Jul 2027',
  'Aug 2027',
  'Sep 2027',
  'Oct 2027',
];

const DEFAULT_MONTH_TARGETS = [
  400000, 500000, 600000, // Q1 = 1,500,000
  700000, 800000, 900000, // Q2 = 2,400,000
  1000000, 1050000, 1150000, // Q3 = 3,200,000
  1150000, 1150000, 1200000, // Q4 = 3,500,000
];

function createDefaultChecklist(): ChecklistItem[] {
  return [
    {
      id: 'messages10',
      title: '10 personalised WhatsApp messages sent, each naming one specific thing on their page or site',
      isCountType: true,
      defaultTargetCount: 10,
      orderIndex: 1,
    },
    {
      id: 'followups',
      title: 'Every follow-up due today sent',
      isCountType: false,
      defaultTargetCount: 0,
      orderIndex: 2,
    },
    {
      id: 'leadsheet',
      title: 'Lead sheet updated, next action set for each lead',
      isCountType: false,
      defaultTargetCount: 0,
      orderIndex: 3,
    },
    {
      id: 'content',
      title: 'One content piece posted: process, mistake and fix, or package explained',
      isCountType: false,
      defaultTargetCount: 0,
      orderIndex: 4,
    },
    {
      id: 'study',
      title: 'One study block done',
      isCountType: false,
      defaultTargetCount: 0,
      orderIndex: 5,
    },
  ];
}

function createDefaultReminders(): ReminderSchedule[] {
  return [
    {
      id: 'morning',
      title: 'Morning briefing',
      timeString: '07:30',
      isRepeating: true,
      daysOfWeek: 'ALL',
      voiceEnabled: true,
      isActive: true,
      isBuiltIn: true,
    },
    {
      id: 'midday',
      title: 'Midday nudge',
      timeString: '12:30',
      isRepeating: true,
      daysOfWeek: 'ALL',
      voiceEnabled: true,
      isActive: true,
      isBuiltIn: true,
    },
    {
      id: 'evening',
      title: 'Evening check-in',
      timeString: '19:30',
      isRepeating: true,
      daysOfWeek: 'ALL',
      voiceEnabled: true,
      isActive: true,
      isBuiltIn: true,
    },
    {
      id: 'sunday',
      title: 'Sunday review',
      timeString: '18:00',
      isRepeating: true,
      daysOfWeek: 'SUN',
      voiceEnabled: true,
      isActive: true,
      isBuiltIn: true,
    },
  ];
}

function createDefaultMonthlyRecords(): MonthlyRecord[] {
  return MONTH_LABELS.map((label, idx) => ({
    monthIndex: idx,
    label,
    revenueTarget: DEFAULT_MONTH_TARGETS[idx],
    revenueReceived: 0,
    websitesClosed: 0,
    activeRetainers: 0,
    retainersLost: 0,
    openQuotes: 0,
  }));
}

function createDefaultItems(): Item[] {
  const now = Date.now();
  const baseItems: Item[] = [
    {
      id: 1,
      type: 'TASK',
      title: 'Send 10 outreach messages',
      details: 'Personalized messages to prospective corporate and studio clients.',
      dueAt: now + 90 * 60 * 1000,
      durationMinutes: 45,
      priority: 'HIGH',
      status: 'PENDING',
      repeatRule: null,
      targetAmount: null,
      unit: null,
      progress: 0,
      contactName: null,
      sourceText: 'seed',
      createdAt: now - 3600000,
    },
    {
      id: 2,
      type: 'GOAL',
      title: 'Read 320 pages by Friday',
      details: 'Creative studio direction and brand positioning.',
      dueAt: now + 5 * 86400000,
      durationMinutes: 30,
      priority: 'NORMAL',
      status: 'PENDING',
      repeatRule: null,
      targetAmount: 320,
      unit: 'pages',
      progress: 40,
      contactName: null,
      sourceText: 'seed',
      createdAt: now - 3500000,
    },
    {
      id: 3,
      type: 'REMINDER',
      title: 'Message Tola about website proposal',
      details: 'Follow up on business tier quote sent earlier.',
      dueAt: now + 3 * 3600000,
      durationMinutes: 15,
      priority: 'NORMAL',
      status: 'PENDING',
      repeatRule: null,
      targetAmount: null,
      unit: null,
      progress: 0,
      contactName: 'Tola',
      sourceText: 'remind me to message Tola',
      createdAt: now - 3400000,
    },
    {
      id: 4,
      type: 'NOTE',
      title: 'Studio lighting arrangement',
      details: 'Use 3-point softbox setup for afternoon client showcase sessions.',
      dueAt: null,
      durationMinutes: 0,
      priority: 'NORMAL',
      status: 'PENDING',
      repeatRule: null,
      targetAmount: null,
      unit: null,
      progress: 0,
      contactName: null,
      sourceText: 'seed',
      createdAt: now - 3300000,
    },
  ];

  let nextId = 5;
  Object.entries(PIPELINE_DEFINITIONS).forEach(([stageKey, def]) => {
    def.items.forEach((stepTitle) => {
      baseItems.push({
        id: nextId++,
        type: 'TASK',
        title: stepTitle,
        details: `Stage: ${stageKey}`,
        dueAt: null,
        durationMinutes: 30,
        priority: 'NORMAL',
        status: 'PENDING',
        repeatRule: null,
        targetAmount: null,
        unit: null,
        progress: 0,
        contactName: null,
        sourceText: `web_pipeline_${stageKey}`,
        createdAt: now,
      });
    });
  });

  return baseItems;
}

export function createDefaultState(): AssistantDatabaseState {
  return {
    goals: {
      id: 1,
      yearlyGoal: 10000000,
      startDate: '2026-11-01',
      q1Target: 1500000,
      q2Target: 2400000,
      q3Target: 3200000,
      q4Target: 3500000,
      weeklyMessagesTarget: 50,
      weeklyConversationsTarget: 5,
      weeklyQuotesTarget: 3,
    },
    checklistItems: createDefaultChecklist(),
    dailyLogs: [],
    dayNotes: {},
    weeklyOverrides: {},
    monthlyRecords: createDefaultMonthlyRecords(),
    reminders: createDefaultReminders(),
    settings: {
      id: 1,
      userName: 'Studio Lead',
      wakeUpTime: '06:30',
      quietHoursStart: '22:00',
      quietHoursEnd: '07:00',
      quietHoursEnabled: true,
      voiceSpeed: 1.0,
      voiceUri: '',
      isAiEnabled: true,
      geminiModel: 'gemini-3.8-flash',
      isFirstRunComplete: true,
    },
    items: createDefaultItems(),
    facts: [
      {
        id: 1,
        text: 'Prefers morning focus work before client outreach messages.',
        category: 'PREFERENCE',
        createdAt: Date.now(),
      },
    ],
    conversationTurns: [],
  };
}

export function loadDatabaseState(): AssistantDatabaseState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return createDefaultState();
    const parsed = JSON.parse(raw);
    const defaults = createDefaultState();
    return {
      ...defaults,
      ...parsed,
      goals: { ...defaults.goals, ...(parsed.goals || {}) },
      settings: {
        ...defaults.settings,
        ...(parsed.settings || {}),
        geminiModel:
          parsed.settings?.geminiModel === 'gemini-3.1-flash-lite'
            ? 'gemini-3.1-flash-lite'
            : 'gemini-3.8-flash',
      },
      monthlyRecords:
        Array.isArray(parsed.monthlyRecords) && parsed.monthlyRecords.length === 12
          ? parsed.monthlyRecords
          : defaults.monthlyRecords,
    };
  } catch {
    return createDefaultState();
  }
}

export function saveDatabaseState(state: AssistantDatabaseState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // Ignore quota errors
  }
}

export interface WeeklySummaryMetrics {
  weekIndex: number;
  weekStart: string;
  weekEnd: string;
  messagesCount: number;
  messagesTarget: number;
  conversationsCount: number;
  conversationsTarget: number;
  quotesCount: number;
  quotesTarget: number;
  contentCount: number;
  studyCount: number;
}

export function getCurrentWeekNumber(startDateStr: string): number {
  const start = new Date(`${startDateStr}T00:00:00`);
  const now = new Date();
  const diffDays = Math.floor((now.getTime() - start.getTime()) / 86400000);
  if (diffDays < 0) return 0;
  return Math.min(51, Math.floor(diffDays / 7));
}

export function getWeekBounds(startDateStr: string, weekIndex: number): { start: string; end: string; rangeLabel: string } {
  const base = new Date(`${startDateStr}T00:00:00`);
  const startDate = new Date(base.getTime() + weekIndex * 7 * 86400000);
  const endDate = new Date(startDate.getTime() + 6 * 86400000);
  const fmt = (d: Date) =>
    d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
  return {
    start: formatTodayDateString(startDate),
    end: formatTodayDateString(endDate),
    rangeLabel: `${fmt(startDate)} - ${fmt(endDate)}`,
  };
}

export function computeWeeklySummary(
  state: AssistantDatabaseState,
  weekIndex: number
): WeeklySummaryMetrics {
  const bounds = getWeekBounds(state.goals.startDate, weekIndex);
  const logsInWeek = state.dailyLogs.filter(
    (l) => l.date >= bounds.start && l.date <= bounds.end && l.isDone
  );

  let autoMessages = 0;
  let autoFollowUps = 0;
  let autoLeadSheet = 0;
  let autoContent = 0;
  let autoStudy = 0;

  for (const log of logsInWeek) {
    if (log.itemId === 'messages10') {
      autoMessages += log.countValue > 0 ? log.countValue : 10;
    } else if (log.itemId === 'followups') {
      autoFollowUps += 1;
    } else if (log.itemId === 'leadsheet') {
      autoLeadSheet += 1;
    } else if (log.itemId === 'content') {
      autoContent += 1;
    } else if (log.itemId === 'study') {
      autoStudy += 1;
    }
  }

  const override = state.weeklyOverrides[weekIndex] || {};
  return {
    weekIndex,
    weekStart: bounds.start,
    weekEnd: bounds.end,
    messagesCount: override.messages !== undefined ? override.messages : autoMessages,
    messagesTarget: state.goals.weeklyMessagesTarget,
    conversationsCount:
      override.conversations !== undefined ? override.conversations : autoFollowUps,
    conversationsTarget: state.goals.weeklyConversationsTarget,
    quotesCount:
      override.quotes !== undefined ? override.quotes : Math.floor(autoLeadSheet / 2),
    quotesTarget: state.goals.weeklyQuotesTarget,
    contentCount: override.content !== undefined ? override.content : autoContent,
    studyCount: override.study !== undefined ? override.study : autoStudy,
  };
}

export function computeCurrentStreak(state: AssistantDatabaseState): number {
  let streak = 0;
  const cursor = new Date();
  for (let i = 0; i < 365; i++) {
    const dateStr = formatTodayDateString(cursor);
    const anyDone = state.dailyLogs.some((l) => l.date === dateStr && l.isDone);
    if (anyDone) {
      streak++;
    } else if (i > 0) {
      break;
    }
    cursor.setDate(cursor.getDate() - 1);
  }
  return streak;
}

export function buildAiLocalContext(state: AssistantDatabaseState): Record<string, unknown> {
  const now = new Date();
  const todayStr = formatTodayDateString(now);
  const nowTimeStr = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
  const curWeekIdx = getCurrentWeekNumber(state.goals.startDate);
  const weekSummary = computeWeeklySummary(state, curWeekIdx);
  const todayLogsMap = new Map(
    state.dailyLogs.filter((l) => l.date === todayStr).map((l) => [l.itemId, l])
  );

  return {
    user_name: state.settings.userName,
    current_date: todayStr,
    current_time: nowTimeStr,
    wake_up_time: state.settings.wakeUpTime,
    quiet_hours: `${state.settings.quietHoursStart} - ${state.settings.quietHoursEnd}`,
    goals: {
      yearly_target: state.goals.yearlyGoal,
      quarters: {
        q1: state.goals.q1Target,
        q2: state.goals.q2Target,
        q3: state.goals.q3Target,
        q4: state.goals.q4Target,
      },
    },
    this_week: {
      messages_sent: weekSummary.messagesCount,
      messages_target: weekSummary.messagesTarget,
      conversations_held: weekSummary.conversationsCount,
      conversations_target: weekSummary.conversationsTarget,
      quotes_sent: weekSummary.quotesCount,
      quotes_target: weekSummary.quotesTarget,
    },
    today_checklist: state.checklistItems.map((ci) => {
      const log = todayLogsMap.get(ci.id);
      return {
        id: ci.id,
        title: ci.title,
        is_done: log?.isDone === true,
        count: ci.isCountType ? log?.countValue ?? 0 : undefined,
        reason_not_done: log && !log.isDone ? log.reasonNotDone : undefined,
      };
    }),
    pending_items: state.items
      .filter((i) => i.status === 'PENDING' && !i.sourceText.startsWith('web_pipeline_'))
      .slice(0, 12)
      .map((pi) => ({
        id: pi.id,
        type: pi.type,
        title: pi.title,
        priority: pi.priority,
        contact: pi.contactName ?? undefined,
      })),
    remembered_facts: state.facts.slice(0, 12).map((f) => f.text),
  };
}

/**
 * Exports sanitized backup JSON (never includes secrets).
 */
export function exportSanitizedJson(state: AssistantDatabaseState): string {
  const payload = {
    exportedAt: new Date().toISOString(),
    version: 2,
    goals: state.goals,
    settings: {
      userName: state.settings.userName,
      wakeUpTime: state.settings.wakeUpTime,
      quietHoursStart: state.settings.quietHoursStart,
      quietHoursEnd: state.settings.quietHoursEnd,
      quietHoursEnabled: state.settings.quietHoursEnabled,
      voiceSpeed: state.settings.voiceSpeed,
      isAiEnabled: state.settings.isAiEnabled,
      geminiModel: state.settings.geminiModel,
    },
    checklistItems: state.checklistItems,
    logs: state.dailyLogs,
    dayNotes: state.dayNotes,
    weeklyOverrides: state.weeklyOverrides,
    monthlyRecords: state.monthlyRecords,
    reminders: state.reminders,
    items: state.items,
    facts: state.facts,
  };
  return JSON.stringify(payload, null, 2);
}

/**
 * Idempotent importer supporting BOTH Web Goal Tracker (index.html) JSON
 * AND Xestlab Assistant backup JSON.
 */
export function mergeImportedJson(
  current: AssistantDatabaseState,
  jsonString: string
): AssistantDatabaseState {
  const root = JSON.parse(jsonString);
  if (!root || typeof root !== 'object') {
    throw new Error('Invalid JSON backup root object');
  }

  let nextGoals = { ...current.goals };
  let nextSettings = { ...current.settings };
  const nextLogs: DailyLog[] = [...current.dailyLogs];
  const nextDayNotes: Record<string, string> = { ...current.dayNotes, ...(root.dayNotes || {}) };
  const nextWeeklyOverrides = { ...current.weeklyOverrides, ...(root.weeklyOverrides || {}) };
  let nextMonthly = [...current.monthlyRecords];
  const nextItems: Item[] = [...current.items];
  const nextFacts = [...current.facts];
  let nextReminders = [...current.reminders];

  // 1. Direct goals or Web Goal Tracker settings
  if (root.goals && typeof root.goals === 'object') {
    nextGoals = { ...nextGoals, ...root.goals };
  }

  if (root.settings && typeof root.settings === 'object') {
    const s = root.settings;
    if (typeof s.yearGoal === 'number') nextGoals.yearlyGoal = s.yearGoal;
    if (typeof s.startDate === 'string') nextGoals.startDate = s.startDate;
    if (s.weeklyTargets && typeof s.weeklyTargets === 'object') {
      if (typeof s.weeklyTargets.messages === 'number')
        nextGoals.weeklyMessagesTarget = s.weeklyTargets.messages;
      if (typeof s.weeklyTargets.conversations === 'number')
        nextGoals.weeklyConversationsTarget = s.weeklyTargets.conversations;
      if (typeof s.weeklyTargets.quotes === 'number')
        nextGoals.weeklyQuotesTarget = s.weeklyTargets.quotes;
    }
    if (s.quarterTargets && typeof s.quarterTargets === 'object') {
      if (typeof s.quarterTargets.q1 === 'number') nextGoals.q1Target = s.quarterTargets.q1;
      if (typeof s.quarterTargets.q2 === 'number') nextGoals.q2Target = s.quarterTargets.q2;
      if (typeof s.quarterTargets.q3 === 'number') nextGoals.q3Target = s.quarterTargets.q3;
      if (typeof s.quarterTargets.q4 === 'number') nextGoals.q4Target = s.quarterTargets.q4;
    }

    if (typeof s.userName === 'string') nextSettings.userName = s.userName;
    if (typeof s.wakeUpTime === 'string') nextSettings.wakeUpTime = s.wakeUpTime;
    if (typeof s.quietHoursStart === 'string') nextSettings.quietHoursStart = s.quietHoursStart;
    if (typeof s.quietHoursEnd === 'string') nextSettings.quietHoursEnd = s.quietHoursEnd;
    if (typeof s.quietHoursEnabled === 'boolean')
      nextSettings.quietHoursEnabled = s.quietHoursEnabled;
    if (typeof s.voiceSpeed === 'number') nextSettings.voiceSpeed = s.voiceSpeed;
  }

  let maxLogId = nextLogs.reduce((m, l) => Math.max(m, l.id), 0);
  const existingLogKeys = new Set(nextLogs.map((l) => `${l.date}_${l.itemId}`));

  // 2a. Web Goal Tracker `days` format
  if (root.days && typeof root.days === 'object') {
    for (const [dateKey, dayVal] of Object.entries(root.days)) {
      const dayData = dayVal as Record<string, unknown>;
      if (!dayData || typeof dayData !== 'object') continue;
      if (typeof dayData.note === 'string' && dayData.note.trim()) {
        nextDayNotes[dateKey] = dayData.note;
      }
      const msgs = Number(dayData.messagesSent) || 0;
      if (msgs > 0 && !existingLogKeys.has(`${dateKey}_messages10`)) {
        existingLogKeys.add(`${dateKey}_messages10`);
        nextLogs.push({
          id: ++maxLogId,
          date: dateKey,
          itemId: 'messages10',
          isDone: true,
          countValue: msgs,
          timestamp: Date.now(),
        });
      }
      if (dayData.checklist && typeof dayData.checklist === 'object') {
        const cl = dayData.checklist as Record<string, unknown>;
        for (const itemKey of ['messages10', 'followups', 'content', 'study', 'leadsheet']) {
          if (cl[itemKey] === true && !existingLogKeys.has(`${dateKey}_${itemKey}`)) {
            existingLogKeys.add(`${dateKey}_${itemKey}`);
            nextLogs.push({
              id: ++maxLogId,
              date: dateKey,
              itemId: itemKey,
              isDone: true,
              countValue: itemKey === 'messages10' ? Math.max(10, msgs) : 0,
              timestamp: Date.now(),
            });
          }
        }
      }
    }
  }

  // 2b. Assistant `logs` array format
  if (Array.isArray(root.logs)) {
    for (const rawLog of root.logs) {
      if (!rawLog || typeof rawLog.date !== 'string' || typeof rawLog.itemId !== 'string') continue;
      const key = `${rawLog.date}_${rawLog.itemId}`;
      if (!existingLogKeys.has(key)) {
        existingLogKeys.add(key);
        nextLogs.push({
          id: ++maxLogId,
          date: rawLog.date,
          itemId: rawLog.itemId,
          isDone: Boolean(rawLog.isDone),
          countValue: Number(rawLog.countValue) || 0,
          reasonNotDone: rawLog.reasonNotDone ?? null,
          timestamp: Number(rawLog.timestamp) || Date.now(),
        });
      }
    }
  }

  // 2c. Web Goal Tracker `weeks` object
  if (root.weeks && typeof root.weeks === 'object') {
    for (const [wKey, wVal] of Object.entries(root.weeks)) {
      const idx = parseInt(wKey, 10);
      const rec = wVal as Record<string, unknown>;
      if (!Number.isNaN(idx) && rec && typeof rec === 'object') {
        nextWeeklyOverrides[idx] = {
          messages: typeof rec.messages === 'number' ? rec.messages : undefined,
          conversations: typeof rec.conversations === 'number' ? rec.conversations : undefined,
          quotes: typeof rec.quotes === 'number' ? rec.quotes : undefined,
          content: typeof rec.content === 'number' ? rec.content : undefined,
          study: typeof rec.study === 'number' ? rec.study : undefined,
        };
      }
    }
  }

  let maxItemId = nextItems.reduce((m, it) => Math.max(m, it.id), 0);
  const itemByTitle = new Map(nextItems.map((it) => [it.title, it]));

  // 3. Web Goal Tracker `pipeline` boolean arrays
  if (root.pipeline && typeof root.pipeline === 'object') {
    for (const [stageKey, def] of Object.entries(PIPELINE_DEFINITIONS)) {
      const stageArr = (root.pipeline as Record<string, unknown>)[stageKey];
      if (Array.isArray(stageArr)) {
        stageArr.forEach((doneFlag, idx) => {
          const title = def.items[idx] || `Pipeline step: ${stageKey} #${idx + 1}`;
          const isCompleted = Boolean(doneFlag);
          const existing = itemByTitle.get(title);
          if (!existing) {
            const created: Item = {
              id: ++maxItemId,
              type: 'TASK',
              title,
              details: `Stage: ${stageKey}`,
              dueAt: null,
              durationMinutes: 30,
              priority: 'NORMAL',
              status: isCompleted ? 'COMPLETED' : 'PENDING',
              repeatRule: null,
              targetAmount: null,
              unit: null,
              progress: 0,
              contactName: null,
              sourceText: `web_pipeline_${stageKey}`,
              createdAt: Date.now(),
            };
            nextItems.push(created);
            itemByTitle.set(title, created);
          } else if (isCompleted && existing.status !== 'COMPLETED') {
            existing.status = 'COMPLETED';
          }
        });
      }
    }
  }

  // 4. Web Goal Tracker `months` or Assistant `monthlyRecords`
  if (Array.isArray(root.monthlyRecords) && root.monthlyRecords.length === 12) {
    nextMonthly = root.monthlyRecords;
  } else if (root.months && typeof root.months === 'object') {
    for (const [mKey, mVal] of Object.entries(root.months)) {
      const idx = parseInt(mKey, 10);
      const mData = mVal as Record<string, unknown>;
      if (!Number.isNaN(idx) && idx >= 0 && idx < 12 && mData && typeof mData === 'object') {
        nextMonthly[idx] = {
          ...nextMonthly[idx],
          revenueReceived:
            Number(mData.revenueReceived ?? mData.revenueActual ?? nextMonthly[idx].revenueReceived) || 0,
          revenueTarget: Number(mData.revenueTarget ?? nextMonthly[idx].revenueTarget) || nextMonthly[idx].revenueTarget,
          websitesClosed: Number(mData.websitesClosed ?? nextMonthly[idx].websitesClosed) || 0,
          activeRetainers: Number(mData.activeRetainers ?? nextMonthly[idx].activeRetainers) || 0,
          retainersLost: Number(mData.retainersLost ?? nextMonthly[idx].retainersLost) || 0,
          openQuotes: Number(mData.openQuotes ?? nextMonthly[idx].openQuotes) || 0,
        };
      }
      const targetRev = Number(mData?.revenueTarget) || 0;
      const actualRev = Number(mData?.revenueActual ?? mData?.revenueReceived) || 0;
      const goalTitle = `Monthly Revenue Target: ${mKey}`;
      if (targetRev > 0 && !itemByTitle.has(goalTitle)) {
        const createdGoal: Item = {
          id: ++maxItemId,
          type: 'GOAL',
          title: goalTitle,
          details: `Target: ₦${targetRev.toLocaleString()}`,
          dueAt: null,
          durationMinutes: 0,
          priority: 'NORMAL',
          status: actualRev >= targetRev ? 'COMPLETED' : 'PENDING',
          repeatRule: null,
          targetAmount: targetRev,
          unit: 'naira',
          progress: actualRev,
          contactName: null,
          sourceText: `web_month_${mKey}`,
          createdAt: Date.now(),
        };
        nextItems.push(createdGoal);
        itemByTitle.set(goalTitle, createdGoal);
      }
    }
  }

  // 5. Assistant `items` array
  if (Array.isArray(root.items)) {
    for (const rawItem of root.items) {
      if (!rawItem || typeof rawItem.title !== 'string') continue;
      if (!itemByTitle.has(rawItem.title)) {
        const created: Item = {
          id: ++maxItemId,
          type: rawItem.type || 'TASK',
          title: rawItem.title,
          details: rawItem.details || '',
          dueAt: rawItem.dueAt ?? null,
          durationMinutes: Number(rawItem.durationMinutes) || 0,
          priority: rawItem.priority || 'NORMAL',
          status: rawItem.status || 'PENDING',
          repeatRule: rawItem.repeatRule ?? null,
          targetAmount: typeof rawItem.targetAmount === 'number' ? rawItem.targetAmount : null,
          unit: rawItem.unit ?? null,
          progress: Number(rawItem.progress) || 0,
          contactName: rawItem.contactName ?? null,
          sourceText: rawItem.sourceText || '',
          createdAt: Number(rawItem.createdAt) || Date.now(),
        };
        nextItems.push(created);
        itemByTitle.set(created.title, created);
      }
    }
  }

  // 6. Assistant `facts` array
  if (Array.isArray(root.facts)) {
    let maxFactId = nextFacts.reduce((m, f) => Math.max(m, f.id), 0);
    const existingFactTexts = new Set(nextFacts.map((f) => f.text));
    for (const rawFact of root.facts) {
      if (rawFact && typeof rawFact.text === 'string' && !existingFactTexts.has(rawFact.text)) {
        existingFactTexts.add(rawFact.text);
        nextFacts.push({
          id: ++maxFactId,
          text: rawFact.text,
          category: rawFact.category || 'GENERAL',
          createdAt: Number(rawFact.createdAt) || Date.now(),
        });
      }
    }
  }

  // 7. Assistant `reminders` array
  if (Array.isArray(root.reminders) && root.reminders.length > 0) {
    nextReminders = root.reminders;
  }

  return {
    ...current,
    goals: nextGoals,
    settings: nextSettings,
    dailyLogs: nextLogs,
    dayNotes: nextDayNotes,
    weeklyOverrides: nextWeeklyOverrides,
    monthlyRecords: nextMonthly,
    reminders: nextReminders,
    items: nextItems,
    facts: nextFacts,
  };
}
