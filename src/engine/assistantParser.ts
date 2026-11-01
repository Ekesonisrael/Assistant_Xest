export type ParsedIntent =
  | { kind: 'PersonReminder'; contactName: string; time: string; details: string }
  | { kind: 'Reminder'; title: string; time: string; isTomorrow: boolean }
  | { kind: 'Note'; text: string }
  | { kind: 'RememberFact'; fact: string }
  | { kind: 'Task'; title: string; durationMinutes: number }
  | { kind: 'Goal'; title: string; amount: number; unit: string; daysRemaining: number }
  | { kind: 'Done'; targetName: string }
  | { kind: 'Question'; question: string }
  | { kind: 'FallbackNote'; text: string };

const TIME_REGEX = /(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/i;

export function normalizeTime(raw: string): string {
  const clean = raw.trim().toLowerCase();
  const isPm = clean.includes('pm');
  const isAm = clean.includes('am');
  const digits = clean.replace(/[^0-9:]/g, '');
  const parts = digits.split(':');
  let hour = parseInt(parts[0] || '12', 10);
  if (Number.isNaN(hour)) hour = 12;
  let minute = parseInt(parts[1] || '0', 10);
  if (Number.isNaN(minute)) minute = 0;

  if (isPm && hour < 12) hour += 12;
  if (isAm && hour === 12) hour = 0;

  const clampedHour = Math.max(0, Math.min(23, hour));
  const clampedMin = Math.max(0, Math.min(59, minute));
  return `${String(clampedHour).padStart(2, '0')}:${String(clampedMin).padStart(2, '0')}`;
}

function parseDaysLeft(word: string): number {
  const w = word.toLowerCase();
  const asNum = parseInt(w, 10);
  if (!Number.isNaN(asNum)) {
    return Math.max(1, asNum);
  }
  const daysOfWeek = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
  const targetDay = daysOfWeek.findIndex((d) => d.startsWith(w));
  if (targetDay !== -1) {
    const currentDay = new Date().getDay();
    let diff = targetDay - currentDay;
    if (diff <= 0) diff += 7;
    return diff;
  }
  return 7;
}

export function parseOfflineIntent(raw: string): ParsedIntent {
  const trimmed = raw.trim();
  const lower = trimmed.toLowerCase();

  // 1. "remember that <fact>" or "remember: <fact>"
  if (lower.startsWith('remember that ') || lower.startsWith('remember: ')) {
    const afterRemember = trimmed.slice('remember'.length).replace(/^[\s:]*(that\s*)?/i, '').trim();
    if (afterRemember) {
      return { kind: 'RememberFact', fact: afterRemember };
    }
  }

  // 2. "remind me to message <name> at <time>" or "message <name> at <time>"
  const personReminderRegex =
    /(?:remind me to\s+)?(?:message|call|text|whatsapp|dm)\s+([a-zA-Z]+)\s+(?:at\s+)?(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)/i;
  const personMatch = trimmed.match(personReminderRegex);
  if (personMatch) {
    const rawName = personMatch[1];
    const contactName = rawName.charAt(0).toUpperCase() + rawName.slice(1);
    const standardTime = normalizeTime(personMatch[2]);
    return {
      kind: 'PersonReminder',
      contactName,
      time: standardTime,
      details: trimmed,
    };
  }

  // 3. "remind me to <action> at/tomorrow <time>"
  if (lower.startsWith('remind me to ') || lower.startsWith('remind me ')) {
    const body = trimmed.slice('remind me'.length).replace(/^[\s:]*(to\s*)?/i, '').trim();
    const isTomorrow = lower.includes('tomorrow');
    const timeMatch = body.match(TIME_REGEX);
    const time = timeMatch ? normalizeTime(timeMatch[0]) : '12:00';
    const title = body.replace(/\b(at|by|for|tomorrow)\b.*$/i, '').trim() || 'Reminder';
    return {
      kind: 'Reminder',
      title,
      time,
      isTomorrow,
    };
  }

  // 4. "done <item>" / "finished <item>" / "complete <item>"
  if (lower.startsWith('done ') || lower.startsWith('finished ') || lower.startsWith('complete ')) {
    const firstSpace = trimmed.indexOf(' ');
    const targetName = firstSpace !== -1 ? trimmed.slice(firstSpace + 1).trim() : trimmed;
    return { kind: 'Done', targetName };
  }

  // 5. "note: <text>" / "note that <text>" / "take a note: <text>"
  if (lower.startsWith('note:') || lower.startsWith('note that ') || lower.startsWith('take a note:')) {
    const noteText = trimmed.replace(/^(note:\s*|note that\s*|take a note:\s*)/i, '').trim();
    return { kind: 'Note', text: noteText || trimmed };
  }

  // 6. "add task <text>" / "task: <text>" / "new task <text>"
  if (lower.startsWith('add task ') || lower.startsWith('task:') || lower.startsWith('new task ')) {
    const taskTitle = trimmed.replace(/^(add task\s*|task:\s*|new task\s*)/i, '').trim();
    return { kind: 'Task', title: taskTitle || trimmed, durationMinutes: 30 };
  }

  // 7. Goal pattern: e.g. "320 pages by friday" or "read 320 pages in 5 days"
  const goalRegex = /(\d+(?:\.\d+)?)\s+([a-zA-Z]+)\s+(?:by|in)\s+([a-zA-Z0-9]+)/i;
  const goalMatch = trimmed.match(goalRegex);
  if (goalMatch) {
    const amount = parseFloat(goalMatch[1]) || 0;
    const unit = goalMatch[2];
    const daysRemaining = parseDaysLeft(goalMatch[3]);
    return {
      kind: 'Goal',
      title: trimmed,
      amount,
      unit,
      daysRemaining,
    };
  }

  // 8. Questions
  if (
    lower.startsWith('what ') ||
    lower.startsWith('how ') ||
    lower.startsWith('when ') ||
    lower.startsWith('who ') ||
    lower.startsWith('why ') ||
    lower.endsWith('?')
  ) {
    return { kind: 'Question', question: trimmed };
  }

  // 9. Fallback: Save as a note and explain so
  return { kind: 'FallbackNote', text: trimmed };
}
