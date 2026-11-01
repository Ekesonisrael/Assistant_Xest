import {
  AssistantDatabaseState,
  Item,
  PlannerProposal,
  RecommendationType,
} from '../types';

function parseMinutes(timeStr: string, defaultVal: number): number {
  const parts = timeStr.split(':');
  const h = parseInt(parts[0] || '', 10);
  if (Number.isNaN(h)) return defaultVal;
  const m = parseInt(parts[1] || '0', 10);
  return h * 60 + (Number.isNaN(m) ? 0 : m);
}

function findNextSlot(currentMin: number, busyMinutes: number[]): number {
  let candidate = currentMin + 45;
  const sortedBusy = [...busyMinutes].sort((a, b) => a - b);
  for (const busy of sortedBusy) {
    if (candidate >= busy - 20 && candidate <= busy + 20) {
      candidate = busy + 30;
    }
  }
  return Math.min(candidate, 21 * 60);
}

export function formatTodayDateString(date: Date = new Date()): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function proposePlanForItem(
  state: AssistantDatabaseState,
  candidateItem: Item
): PlannerProposal {
  const wakeUpTime = state.settings.wakeUpTime || '06:30';
  const quietStart = state.settings.quietHoursStart || '22:00';

  const now = new Date();
  const currentMinutesOfDay = now.getHours() * 60 + now.getMinutes();
  const quietMinutes = parseMinutes(quietStart, 22 * 60);
  const wakeMinutes = parseMinutes(wakeUpTime, 6 * 60 + 30);

  const todayStr = formatTodayDateString(now);
  const todayLogsByItem = new Map(
    state.dailyLogs.filter((l) => l.date === todayStr).map((l) => [l.itemId, l])
  );
  const unfinishedChecklistCount = state.checklistItems.filter(
    (ci) => todayLogsByItem.get(ci.id)?.isDone !== true
  ).length;

  const activeReminders = state.reminders.filter((r) => r.isActive);
  const pendingItems = state.items.filter((i) => i.status === 'PENDING' && i.type !== 'NOTE');

  // If candidate already has an explicit dueAt from user (e.g. "at 16:00"), respect it in the proposal
  if (candidateItem.dueAt && candidateItem.dueAt > Date.now()) {
    const dueDate = new Date(candidateItem.dueAt);
    const hh = String(dueDate.getHours()).padStart(2, '0');
    const mm = String(dueDate.getMinutes()).padStart(2, '0');
    const timeLabel = `${hh}:${mm}`;
    const isSameDay = formatTodayDateString(dueDate) === todayStr;
    const rec = isSameDay ? RecommendationType.LATER_TODAY : RecommendationType.TOMORROW;
    return {
      item: candidateItem,
      recommendation: rec,
      suggestedTime: isSameDay ? timeLabel : `Tomorrow ${timeLabel}`,
      suggestedDueAtMillis: candidateItem.dueAt,
      rationale: isSameDay
        ? `Scheduled for ${timeLabel} today alongside ${unfinishedChecklistCount} remaining daily checklist items.`
        : `Scheduled for ${timeLabel} outside today's active blocks so today's focus stays clear.`,
      summarySpeech: `I have prepared ${candidateItem.title} for ${timeLabel}. Should I lock that in?`,
    };
  }

  // Near or inside quiet hours -> Tomorrow 09:00
  if (
    state.settings.quietHoursEnabled &&
    (currentMinutesOfDay >= quietMinutes - 45 || currentMinutesOfDay < wakeMinutes)
  ) {
    const tomorrow = new Date(now);
    tomorrow.setDate(tomorrow.getDate() + 1);
    tomorrow.setHours(9, 0, 0, 0);
    return {
      item: { ...candidateItem, dueAt: tomorrow.getTime() },
      recommendation: RecommendationType.TOMORROW,
      suggestedTime: 'Tomorrow 09:00',
      suggestedDueAtMillis: tomorrow.getTime(),
      rationale:
        'It is near your quiet hours. Scheduling for tomorrow morning at 09:00 will keep your focus fresh.',
      summarySpeech:
        'I recommend tomorrow at 09:00. Quiet hours are active or starting soon. Should I schedule this?',
    };
  }

  const urgentPending = pendingItems.filter(
    (i) => i.priority === 'HIGH' || i.priority === 'URGENT'
  ).length;
  const durationMins = candidateItem.durationMinutes > 0 ? candidateItem.durationMinutes : 30;

  if (urgentPending > 3 && unfinishedChecklistCount > 2) {
    const laterSlot = findNextSlot(
      currentMinutesOfDay,
      activeReminders.map((r) => parseMinutes(r.timeString, 12 * 60))
    );
    const laterHour = Math.floor(laterSlot / 60);
    const laterMinute = laterSlot % 60;
    const laterTimeStr = `${String(laterHour).padStart(2, '0')}:${String(laterMinute).padStart(2, '0')}`;
    const laterDate = new Date(now);
    laterDate.setHours(laterHour, laterMinute, 0, 0);

    return {
      item: { ...candidateItem, dueAt: laterDate.getTime() },
      recommendation: RecommendationType.LATER_TODAY,
      suggestedTime: laterTimeStr,
      suggestedDueAtMillis: laterDate.getTime(),
      rationale: `You have ${unfinishedChecklistCount} unfinished core items and ${urgentPending} urgent tasks queued right now.`,
      summarySpeech: `I recommend later today at ${laterTimeStr}. You have core items to finish first. Save for ${laterTimeStr}?`,
    };
  }

  if (
    candidateItem.priority === 'HIGH' ||
    durationMins <= 15 ||
    (unfinishedChecklistCount <= 1 && urgentPending === 0)
  ) {
    const dueNow = Date.now() + durationMins * 60 * 1000;
    return {
      item: { ...candidateItem, dueAt: dueNow },
      recommendation: RecommendationType.NOW,
      suggestedTime: 'Now',
      suggestedDueAtMillis: dueNow,
      rationale: 'Your immediate schedule is clear before your next reminder block.',
      summarySpeech:
        'I recommend doing this now. You have clear focus time. Want me to mark it active?',
    };
  }

  const slot = findNextSlot(
    currentMinutesOfDay,
    activeReminders.map((r) => parseMinutes(r.timeString, 12 * 60))
  );
  const slotHour = Math.floor(slot / 60);
  const slotMinute = slot % 60;
  const slotTimeStr = `${String(slotHour).padStart(2, '0')}:${String(slotMinute).padStart(2, '0')}`;
  const slotDate = new Date(now);
  slotDate.setHours(slotHour, slotMinute, 0, 0);

  return {
    item: { ...candidateItem, dueAt: slotDate.getTime() },
    recommendation: RecommendationType.LATER_TODAY,
    suggestedTime: slotTimeStr,
    suggestedDueAtMillis: slotDate.getTime(),
    rationale: `There is an open ${durationMins}-minute slot at ${slotTimeStr} between scheduled check-ins.`,
    summarySpeech: `I recommend later today at ${slotTimeStr}. You have an open block then. Should I lock that in?`,
  };
}
