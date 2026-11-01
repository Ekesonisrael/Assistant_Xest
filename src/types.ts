export type ItemType = 'TASK' | 'GOAL' | 'NOTE' | 'REMINDER' | 'HABIT';
export type ItemPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
export type ItemStatus = 'PENDING' | 'COMPLETED' | 'DROPPED';

export interface Goal {
  id: number;
  yearlyGoal: number;
  startDate: string; // yyyy-MM-dd
  q1Target: number;
  q2Target: number;
  q3Target: number;
  q4Target: number;
  weeklyMessagesTarget: number;
  weeklyConversationsTarget: number;
  weeklyQuotesTarget: number;
}

export interface ChecklistItem {
  id: string;
  title: string;
  isCountType: boolean;
  defaultTargetCount: number;
  orderIndex: number;
}

export interface DailyLog {
  id: number;
  date: string; // yyyy-MM-dd
  itemId: string;
  isDone: boolean;
  countValue: number;
  reasonNotDone?: string | null;
  timestamp: number;
}

export interface ReminderSchedule {
  id: string;
  title: string;
  timeString: string; // HH:mm
  isRepeating: boolean;
  daysOfWeek: string; // "ALL" or "SUN" etc.
  voiceEnabled: boolean;
  isActive: boolean;
  isBuiltIn: boolean;
}

export interface UserSettings {
  id: number;
  userName: string;
  wakeUpTime: string; // HH:mm
  quietHoursStart: string; // HH:mm
  quietHoursEnd: string; // HH:mm
  quietHoursEnabled: boolean;
  voiceSpeed: number;
  voiceUri: string;
  isAiEnabled: boolean;
  geminiModel: string;
  isFirstRunComplete: boolean;
}

export interface Item {
  id: number;
  type: ItemType;
  title: string;
  details: string;
  dueAt: number | null;
  durationMinutes: number;
  priority: ItemPriority;
  status: ItemStatus;
  repeatRule: string | null;
  targetAmount: number | null;
  unit: string | null;
  progress: number;
  contactName: string | null;
  sourceText: string;
  createdAt: number;
}

export interface Fact {
  id: number;
  text: string;
  category: string;
  createdAt: number;
}

export interface ConversationTurn {
  id: number;
  sender: 'USER' | 'ASSISTANT';
  text: string;
  timestamp: number;
}

export enum RecommendationType {
  NOW = 'NOW',
  LATER_TODAY = 'LATER_TODAY',
  TOMORROW = 'TOMORROW',
  DROP = 'DROP',
}

export interface PlannerProposal {
  item: Item;
  recommendation: RecommendationType;
  suggestedTime: string | null;
  suggestedDueAtMillis: number | null;
  rationale: string;
  summarySpeech: string;
}

export interface ChatMessage {
  id: string;
  sender: 'You' | 'Assistant';
  text: string;
  executedActions: string[];
  proposal?: PlannerProposal | null;
  timestamp: number;
}

export interface AssistantAction {
  name: string;
  itemId?: string;
  type?: string;
  title?: string;
  count?: number;
  reason?: string;
  minutes?: number;
  time?: string;
  contactName?: string;
  targetAmount?: number;
  unit?: string;
  details?: string;
  status?: string;
}

export interface AssistantResponse {
  reply: string;
  actions: AssistantAction[];
}

export interface WeeklyOverrideRecord {
  messages?: number;
  conversations?: number;
  quotes?: number;
  content?: number;
  study?: number;
}

export interface MonthlyRecord {
  monthIndex: number; // 0..11 (Nov 2026 .. Oct 2027)
  label: string;
  revenueTarget: number;
  revenueReceived: number;
  websitesClosed: number;
  activeRetainers: number;
  retainersLost: number;
  openQuotes: number;
}

export interface ActiveAlertBanner {
  id: string;
  kind: 'WAKEUP' | 'BRIEFING' | 'PERSON_REMINDER' | 'REMINDER';
  title: string;
  message: string;
  contactName?: string | null;
  itemId?: number;
  triggeredAt: number;
}

export interface AssistantDatabaseState {
  goals: Goal;
  checklistItems: ChecklistItem[];
  dailyLogs: DailyLog[];
  dayNotes: Record<string, string>;
  weeklyOverrides: Record<number, WeeklyOverrideRecord>;
  monthlyRecords: MonthlyRecord[];
  reminders: ReminderSchedule[];
  settings: UserSettings;
  items: Item[];
  facts: Fact[];
  conversationTurns: ConversationTurn[];
}

export function computeDailyRequired(item: Item, currentTime: number = Date.now()): number | null {
  if (item.targetAmount == null || item.dueAt == null || item.dueAt <= currentTime) {
    return null;
  }
  const remaining = Math.max(0, item.targetAmount - item.progress);
  const diffDays = Math.max(1, Math.floor((item.dueAt - currentTime) / (1000 * 60 * 60 * 24)));
  return remaining / diffDays;
}
