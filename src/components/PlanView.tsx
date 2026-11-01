import React, { useState } from 'react';
import { ChevronLeft, ChevronRight, Copy, Volume2, MessageSquare } from 'lucide-react';
import {
  AssistantDatabaseState,
  Item,
  MonthlyRecord,
  WeeklyOverrideRecord,
  computeDailyRequired,
} from '../types';
import {
  PIPELINE_DEFINITIONS,
  computeCurrentStreak,
  computeWeeklySummary,
  getCurrentWeekNumber,
  getWeekBounds,
} from '../engine/storage';
import { formatTodayDateString } from '../engine/plannerEngine';

type PlanSubTab = 'TODAY' | 'WEEK' | 'MONTH' | 'YEAR' | 'PIPELINE';

interface PlanViewProps {
  state: AssistantDatabaseState;
  onToggleChecklist: (itemId: string, done: boolean, count?: number) => void;
  onSetTodayMessages: (count: number) => void;
  onUpdateDayNote: (date: string, note: string) => void;
  onCompleteItem: (item: Item) => void;
  onReopenItem: (item: Item) => void;
  onUpdateGoalProgress: (item: Item, newProgress: number) => void;
  onUpdateWeeklyOverride: (weekIndex: number, patch: Partial<WeeklyOverrideRecord>) => void;
  onUpdateMonthlyRecord: (monthIndex: number, patch: Partial<MonthlyRecord>) => void;
  onTriggerBriefing: (reminderTitle: string) => void;
  onOpenChatContact: (contactName: string, messageHint: string) => void;
  onCopyText: (text: string, label: string) => void;
}

export const PlanView: React.FC<PlanViewProps> = ({
  state,
  onToggleChecklist,
  onSetTodayMessages,
  onUpdateDayNote,
  onCompleteItem,
  onReopenItem,
  onUpdateGoalProgress,
  onUpdateWeeklyOverride,
  onUpdateMonthlyRecord,
  onTriggerBriefing,
  onOpenChatContact,
  onCopyText,
}) => {
  const [subTab, setSubTab] = useState<PlanSubTab>('TODAY');
  const currentWeekIdx = getCurrentWeekNumber(state.goals.startDate);
  const [selectedWeek, setSelectedWeek] = useState<number>(currentWeekIdx);

  const initialMonthIdx = (() => {
    const start = new Date(`${state.goals.startDate}T00:00:00`);
    const now = new Date();
    const diff = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth());
    return diff >= 0 && diff < 12 ? diff : 0;
  })();
  const [selectedMonth, setSelectedMonth] = useState<number>(initialMonthIdx);

  const todayStr = formatTodayDateString();
  const todayLogsMap = new Map(
    state.dailyLogs.filter((l) => l.date === todayStr).map((l) => [l.itemId, l])
  );
  const todayMsgCount = todayLogsMap.get('messages10')?.countValue || 0;
  const doneChecklistCount = state.checklistItems.filter(
    (ci) => todayLogsMap.get(ci.id)?.isDone === true
  ).length;
  const streak = computeCurrentStreak(state);

  const pendingTasks = state.items.filter(
    (it) =>
      it.type !== 'NOTE' &&
      it.status === 'PENDING' &&
      !it.sourceText.startsWith('web_pipeline_')
  );

  const pipelineItems = state.items.filter(
    (it) => it.sourceText.startsWith('web_pipeline_') || it.details.startsWith('Stage:')
  );

  const weekSummary = computeWeeklySummary(state, selectedWeek);
  const weekBounds = getWeekBounds(state.goals.startDate, selectedWeek);

  const curMonthRecord = state.monthlyRecords[selectedMonth] || state.monthlyRecords[0];

  const totalAnnualReceived = state.monthlyRecords.reduce(
    (acc, m) => acc + (m.revenueReceived || 0),
    0
  );
  const annualPct = Math.min(
    100,
    Math.round((totalAnnualReceived / Math.max(1, state.goals.yearlyGoal)) * 100)
  );

  const buildWeeklyPlainText = () => {
    return [
      `Weekly Review - Week ${selectedWeek + 1} (${weekBounds.rangeLabel})`,
      `Outreach messages: ${weekSummary.messagesCount} / ${weekSummary.messagesTarget}`,
      `Conversations held: ${weekSummary.conversationsCount} / ${weekSummary.conversationsTarget}`,
      `Quotes sent: ${weekSummary.quotesCount} / ${weekSummary.quotesTarget}`,
      `Content pieces published: ${weekSummary.contentCount}`,
      `Study blocks completed: ${weekSummary.studyCount}`,
    ].join('\n');
  };

  const buildMonthlyPlainText = () => {
    return [
      `Monthly Review - ${curMonthRecord.label}`,
      `Revenue received: ₦${curMonthRecord.revenueReceived.toLocaleString()} / ₦${curMonthRecord.revenueTarget.toLocaleString()}`,
      `Websites closed: ${curMonthRecord.websitesClosed}`,
      `Active retainers: ${curMonthRecord.activeRetainers}`,
      `Retainers lost: ${curMonthRecord.retainersLost}`,
      `Open quotes: ${curMonthRecord.openQuotes}`,
    ].join('\n');
  };

  return (
    <div className="max-w-[680px] mx-auto px-4 pt-4 pb-24">
      <div className="mb-3">
        <h2 className="text-[20px] font-bold text-[#F2F1EC]">Execution Plan</h2>
        <p className="text-[12px] text-[#8E9E99]">
          Daily schedule, proactive alarms, studio revenue targets, and milestone checklists.
        </p>
      </div>

      {/* 8px Rounded Sub-Tab Bar (Zero Pills) */}
      <div className="grid grid-cols-5 gap-1.5 mb-4">
        {(
          [
            { id: 'TODAY', label: 'Today' },
            { id: 'WEEK', label: 'Week' },
            { id: 'MONTH', label: 'Month' },
            { id: 'YEAR', label: 'Year' },
            { id: 'PIPELINE', label: 'Pipeline' },
          ] as { id: PlanSubTab; label: string }[]
        ).map((tab) => {
          const active = subTab === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              onClick={() => setSubTab(tab.id)}
              className={`rounded-[8px] border py-2 text-[11px] transition-colors cursor-pointer ${
                active
                  ? 'bg-[#2BA5A0] border-[#2BA5A0] text-[#0E1412] font-bold'
                  : 'bg-[#161F1C] border-[#24332F] text-[#F2F1EC] font-medium hover:border-[#2BA5A0]/50'
              }`}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* TODAY VIEW */}
      {subTab === 'TODAY' && (
        <div className="space-y-3.5">
          {/* Wake-up & Quiet Hours Card */}
          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 flex items-center justify-between">
            <div>
              <div className="text-[11px] font-bold text-[#2BA5A0] uppercase tracking-wider">
                Wake-up Alarm
              </div>
              <div className="text-[15px] font-semibold text-[#F2F1EC]">
                {state.settings.wakeUpTime}
              </div>
            </div>
            <div className="text-center">
              <div className="text-[11px] text-[#8E9E99] uppercase tracking-wider">Streak</div>
              <div className="text-[15px] font-semibold text-[#2BA5A0]">{streak} day streak</div>
            </div>
            <div className="text-right">
              <div className="text-[11px] text-[#8E9E99] uppercase tracking-wider">Quiet Hours</div>
              <div className="text-[15px] font-semibold text-[#F2F1EC]">
                {state.settings.quietHoursEnabled
                  ? `${state.settings.quietHoursStart} - ${state.settings.quietHoursEnd}`
                  : 'Off'}
              </div>
            </div>
          </div>

          {/* Outreach Messages Counter */}
          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5">
            <div className="flex items-center justify-between mb-1">
              <label className="text-[13px] font-semibold text-[#F2F1EC]">
                Outreach messages sent today
              </label>
              <span className="text-[11px] text-[#8E9E99]">
                Counts toward {state.goals.weeklyMessagesTarget}/week target
              </span>
            </div>
            <div className="flex items-center gap-2 mt-2">
              <input
                type="number"
                min={0}
                value={todayMsgCount}
                onChange={(e) => onSetTodayMessages(Math.max(0, parseInt(e.target.value, 10) || 0))}
                className="flex-1 rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[14px] font-semibold text-[#F2F1EC] focus:outline-none focus:border-[#2BA5A0]"
              />
              <button
                type="button"
                onClick={() => onSetTodayMessages(todayMsgCount + 10)}
                className="rounded-[8px] bg-[#1E2A27] border border-[#24332F] px-3.5 py-2 text-[13px] font-bold text-[#2BA5A0] hover:border-[#2BA5A0] cursor-pointer"
              >
                +10
              </button>
            </div>
          </div>

          {/* Core Daily Checklist */}
          <div className="space-y-2">
            <div className="flex items-center justify-between">
              <h3 className="text-[13px] font-bold text-[#2BA5A0]">Core Daily Checklist</h3>
              <span className="text-[12px] font-semibold text-[#8E9E99]">
                {doneChecklistCount} / {state.checklistItems.length} done
              </span>
            </div>
            {state.checklistItems.map((ci) => {
              const log = todayLogsMap.get(ci.id);
              const isDone = log?.isDone === true;
              return (
                <div
                  key={ci.id}
                  className={`rounded-[8px] border p-3 flex items-center justify-between gap-3 ${
                    isDone
                      ? 'bg-[#161F1C]/75 border-[#2BA5A0]'
                      : 'bg-[#161F1C] border-[#24332F]'
                  }`}
                >
                  <div className="flex-1">
                    <div
                      className={`text-[13px] font-medium ${
                        isDone ? 'text-[#8E9E99] line-through' : 'text-[#F2F1EC]'
                      }`}
                    >
                      {ci.title}
                    </div>
                    {log && log.countValue > 0 && (
                      <div className="text-[11px] text-[#2BA5A0] font-semibold mt-0.5">
                        {log.countValue} logged today
                      </div>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() =>
                      onToggleChecklist(ci.id, !isDone, ci.isCountType ? Math.max(10, todayMsgCount) : 0)
                    }
                    className={`rounded-[8px] px-3 py-1.5 text-[11px] font-semibold shrink-0 cursor-pointer ${
                      isDone
                        ? 'bg-[#2BA5A0] text-[#0E1412]'
                        : 'bg-[#1E2A27] border border-[#24332F] text-[#F2F1EC] hover:border-[#2BA5A0]'
                    }`}
                  >
                    {isDone ? 'Done' : 'Mark Done'}
                  </button>
                </div>
              );
            })}
          </div>

          {/* Day Focus Note */}
          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5">
            <div className="flex items-center justify-between mb-1.5">
              <span className="text-[13px] font-semibold text-[#F2F1EC]">Day Focus and Notes</span>
              <span className="text-[11px] text-[#8E9E99]">Saved automatically</span>
            </div>
            <input
              type="text"
              value={state.dayNotes[todayStr] || ''}
              onChange={(e) => onUpdateDayNote(todayStr, e.target.value)}
              placeholder="Key wins, blockers, or priorities for today"
              className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC] placeholder-[#8E9E99] focus:outline-none focus:border-[#2BA5A0]"
            />
          </div>

          {/* Scheduled Accountability Briefings */}
          <div className="space-y-2">
            <h3 className="text-[13px] font-bold text-[#2BA5A0]">
              Scheduled Accountability Briefings
            </h3>
            {state.reminders
              .filter((r) => r.isActive)
              .map((rem) => (
                <div
                  key={rem.id}
                  className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3 flex items-center justify-between gap-2"
                >
                  <div>
                    <div className="text-[13px] font-medium text-[#F2F1EC]">{rem.title}</div>
                    <div className="text-[11px] text-[#8E9E99]">
                      {rem.daysOfWeek === 'ALL' ? 'Daily' : rem.daysOfWeek} ·{' '}
                      {rem.voiceEnabled ? 'Spoken briefing' : 'Silent banner'}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-[13px] font-bold text-[#2BA5A0]">{rem.timeString}</span>
                    <button
                      type="button"
                      onClick={() => onTriggerBriefing(rem.title)}
                      title="Play spoken coach briefing now"
                      className="rounded-[8px] bg-[#1E2A27] border border-[#24332F] px-2.5 py-1 text-[11px] text-[#F2F1EC] hover:border-[#2BA5A0] flex items-center gap-1 cursor-pointer"
                    >
                      <Volume2 className="w-3 h-3 text-[#2BA5A0]" />
                      Speak
                    </button>
                  </div>
                </div>
              ))}
          </div>

          {/* Upcoming Tasks & Goals */}
          <div className="space-y-2">
            <h3 className="text-[13px] font-bold text-[#2BA5A0]">Upcoming Tasks & Goals</h3>
            {pendingTasks.length === 0 ? (
              <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4 text-[13px] text-[#8E9E99] text-center">
                All custom tasks and goals are clear. Add new items from Home.
              </div>
            ) : (
              pendingTasks.map((task) => {
                const dailyReq = computeDailyRequired(task);
                return (
                  <div
                    key={task.id}
                    className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex-1">
                        <div className="flex items-center gap-2">
                          <span className="text-[10px] font-bold uppercase text-[#2BA5A0]">
                            {task.type}
                          </span>
                          {task.dueAt && (
                            <span className="text-[11px] text-[#8E9E99]">
                              Due{' '}
                              {new Date(task.dueAt).toLocaleString([], {
                                month: 'short',
                                day: 'numeric',
                                hour: '2-digit',
                                minute: '2-digit',
                              })}
                            </span>
                          )}
                        </div>
                        <div className="text-[14px] font-semibold text-[#F2F1EC] mt-0.5">
                          {task.title}
                        </div>
                        {task.details && (
                          <div className="text-[12px] text-[#8E9E99] mt-0.5">{task.details}</div>
                        )}
                        {dailyReq != null && task.unit && (
                          <div className="text-[11px] font-bold text-[#2BA5A0] mt-1">
                            Required pace: {dailyReq.toFixed(1)} {task.unit}/day ({task.progress} /{' '}
                            {task.targetAmount} {task.unit})
                          </div>
                        )}
                      </div>
                      <div className="flex items-center gap-1.5 shrink-0">
                        {task.contactName && (
                          <button
                            type="button"
                            onClick={() => onOpenChatContact(task.contactName!, task.title)}
                            className="rounded-[8px] bg-[#1E2A27] border border-[#24332F] px-2.5 py-1.5 text-[11px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] flex items-center gap-1 cursor-pointer"
                          >
                            <MessageSquare className="w-3 h-3 text-[#2BA5A0]" />
                            Chat
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => onCompleteItem(task)}
                          className="rounded-[8px] bg-[#2BA5A0] px-3 py-1.5 text-[11px] font-semibold text-[#0E1412] hover:opacity-90 cursor-pointer"
                        >
                          Complete
                        </button>
                      </div>
                    </div>

                    {task.type === 'GOAL' && task.targetAmount != null && (
                      <div className="mt-2.5 pt-2.5 border-t border-[#24332F] flex items-center gap-2">
                        <span className="text-[11px] text-[#8E9E99]">Log progress:</span>
                        <input
                          type="number"
                          min={0}
                          value={task.progress}
                          onChange={(e) =>
                            onUpdateGoalProgress(task, parseFloat(e.target.value) || 0)
                          }
                          className="w-24 rounded-[8px] border border-[#24332F] bg-[#0E1412] px-2.5 py-1 text-[12px] text-[#F2F1EC]"
                        />
                        <span className="text-[11px] text-[#8E9E99]">
                          / {task.targetAmount} {task.unit}
                        </span>
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        </div>
      )}

      {/* WEEK VIEW */}
      {subTab === 'WEEK' && (
        <div className="space-y-3.5">
          <div className="flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={() => setSelectedWeek((w) => Math.max(0, w - 1))}
              className="w-10 h-10 rounded-[8px] border border-[#24332F] bg-[#161F1C] flex items-center justify-center text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <div className="text-center flex-1">
              <div className="text-[16px] font-bold text-[#F2F1EC]">Week {selectedWeek + 1}</div>
              <div className="text-[12px] text-[#8E9E99]">{weekBounds.rangeLabel}</div>
            </div>
            <button
              type="button"
              onClick={() => setSelectedWeek(currentWeekIdx)}
              className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] px-2.5 py-2 text-[11px] font-semibold text-[#2BA5A0] cursor-pointer"
            >
              This Week
            </button>
            <button
              type="button"
              onClick={() => setSelectedWeek((w) => Math.min(51, w + 1))}
              className="w-10 h-10 rounded-[8px] border border-[#24332F] bg-[#161F1C] flex items-center justify-center text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>

          {[
            {
              title: 'Outreach messages',
              done: weekSummary.messagesCount,
              target: weekSummary.messagesTarget,
            },
            {
              title: 'Conversations held',
              done: weekSummary.conversationsCount,
              target: weekSummary.conversationsTarget,
            },
            {
              title: 'Quotes sent',
              done: weekSummary.quotesCount,
              target: weekSummary.quotesTarget,
            },
          ].map((row) => {
            const statusWord =
              row.done > row.target
                ? 'Ahead'
                : row.done >= row.target * 0.9 && row.target > 0
                ? 'On track'
                : 'Behind';
            const isBehind = statusWord === 'Behind';
            return (
              <div
                key={row.title}
                className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 flex items-center justify-between"
              >
                <div>
                  <div className="text-[14px] font-semibold text-[#F2F1EC]">{row.title}</div>
                  <div className="text-[13px] text-[#8E9E99]">
                    {row.done} / {row.target} completed
                  </div>
                </div>
                <span
                  className={`text-[13px] font-bold ${
                    isBehind ? 'text-[#E05349]' : 'text-[#2BA5A0]'
                  }`}
                >
                  {statusWord}
                </span>
              </div>
            );
          })}

          {/* Log Numbers for This Week */}
          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5">
            <div className="text-[14px] font-semibold text-[#F2F1EC] mb-2.5">
              Log Numbers for Week {selectedWeek + 1}
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5">
              {[
                { key: 'messages', label: 'Messages', val: weekSummary.messagesCount },
                {
                  key: 'conversations',
                  label: 'Conversations',
                  val: weekSummary.conversationsCount,
                },
                { key: 'quotes', label: 'Quotes Sent', val: weekSummary.quotesCount },
                { key: 'content', label: 'Content Posts', val: weekSummary.contentCount },
                { key: 'study', label: 'Study Blocks', val: weekSummary.studyCount },
              ].map((field) => (
                <div key={field.key}>
                  <label className="block text-[11px] text-[#8E9E99] mb-1">{field.label}</label>
                  <input
                    type="number"
                    min={0}
                    value={field.val}
                    onChange={(e) =>
                      onUpdateWeeklyOverride(selectedWeek, {
                        [field.key]: Math.max(0, parseInt(e.target.value, 10) || 0),
                      })
                    }
                    className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-2.5 py-1.5 text-[13px] text-[#F2F1EC]"
                  />
                </div>
              ))}
            </div>
          </div>

          <button
            type="button"
            onClick={() => onCopyText(buildWeeklyPlainText(), 'Weekly summary copied to clipboard')}
            className="w-full rounded-[8px] bg-[#2BA5A0] py-3 text-[13px] font-bold text-[#0E1412] flex items-center justify-center gap-2 hover:opacity-90 cursor-pointer"
          >
            <Copy className="w-4 h-4" />
            Copy weekly summary
          </button>
        </div>
      )}

      {/* MONTH VIEW */}
      {subTab === 'MONTH' && (
        <div className="space-y-3.5">
          <div className="flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={() => setSelectedMonth((m) => Math.max(0, m - 1))}
              className="w-10 h-10 rounded-[8px] border border-[#24332F] bg-[#161F1C] flex items-center justify-center text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
            >
              <ChevronLeft className="w-4 h-4" />
            </button>
            <div className="text-center flex-1">
              <div className="text-[16px] font-bold text-[#F2F1EC]">{curMonthRecord.label}</div>
              <div className="text-[12px] text-[#8E9E99]">
                Target: ₦{curMonthRecord.revenueTarget.toLocaleString()}
              </div>
            </div>
            <button
              type="button"
              onClick={() => setSelectedMonth((m) => Math.min(11, m + 1))}
              className="w-10 h-10 rounded-[8px] border border-[#24332F] bg-[#161F1C] flex items-center justify-center text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
            >
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>

          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5">
            <div className="flex items-center justify-between mb-3">
              <div>
                <div className="text-[12px] text-[#8E9E99]">Revenue Received</div>
                <div className="text-[22px] font-bold text-[#F2F1EC]">
                  ₦{curMonthRecord.revenueReceived.toLocaleString()}
                </div>
              </div>
              <span
                className={`text-[12px] font-bold ${
                  curMonthRecord.revenueReceived >= curMonthRecord.revenueTarget
                    ? 'text-[#2BA5A0]'
                    : 'text-[#E05349]'
                }`}
              >
                {curMonthRecord.revenueReceived >= curMonthRecord.revenueTarget
                  ? 'Target Met'
                  : `₦${Math.max(
                      0,
                      curMonthRecord.revenueTarget - curMonthRecord.revenueReceived
                    ).toLocaleString()} remaining`}
              </span>
            </div>

            <div className="grid grid-cols-2 gap-2.5">
              {[
                {
                  key: 'revenueReceived',
                  label: 'Revenue Received (₦)',
                  val: curMonthRecord.revenueReceived,
                },
                {
                  key: 'websitesClosed',
                  label: 'Websites Closed',
                  val: curMonthRecord.websitesClosed,
                },
                {
                  key: 'activeRetainers',
                  label: 'Active Retainers',
                  val: curMonthRecord.activeRetainers,
                },
                {
                  key: 'openQuotes',
                  label: 'Open Quotes',
                  val: curMonthRecord.openQuotes,
                },
              ].map((f) => (
                <div key={f.key}>
                  <label className="block text-[11px] text-[#8E9E99] mb-1">{f.label}</label>
                  <input
                    type="number"
                    min={0}
                    value={f.val}
                    onChange={(e) =>
                      onUpdateMonthlyRecord(selectedMonth, {
                        [f.key]: Math.max(0, Number(e.target.value) || 0),
                      })
                    }
                    className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-2.5 py-1.5 text-[13px] text-[#F2F1EC]"
                  />
                </div>
              ))}
            </div>
          </div>

          {/* Quarterly Targets */}
          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-2">
            <div className="text-[14px] font-bold text-[#2BA5A0]">Quarterly Targets</div>
            {[
              { label: 'Q1 (Nov 2026 - Jan 2027)', amount: state.goals.q1Target },
              { label: 'Q2 (Feb 2027 - Apr 2027)', amount: state.goals.q2Target },
              { label: 'Q3 (May 2027 - Jul 2027)', amount: state.goals.q3Target },
              { label: 'Q4 (Aug 2027 - Oct 2027)', amount: state.goals.q4Target },
            ].map((q) => (
              <div
                key={q.label}
                className="flex items-center justify-between text-[13px] py-1 border-b border-[#24332F] last:border-0"
              >
                <span className="text-[#F2F1EC]">{q.label}</span>
                <span className="font-bold text-[#F2F1EC]">₦{q.amount.toLocaleString()}</span>
              </div>
            ))}
          </div>

          <button
            type="button"
            onClick={() =>
              onCopyText(buildMonthlyPlainText(), 'Monthly review copied to clipboard')
            }
            className="w-full rounded-[8px] bg-[#2BA5A0] py-3 text-[13px] font-bold text-[#0E1412] flex items-center justify-center gap-2 hover:opacity-90 cursor-pointer"
          >
            <Copy className="w-4 h-4" />
            Copy monthly review
          </button>
        </div>
      )}

      {/* YEAR VIEW */}
      {subTab === 'YEAR' && (
        <div className="space-y-3.5">
          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4">
            <div className="text-[12px] font-bold text-[#2BA5A0] uppercase tracking-wider">
              Annual Goal Target
            </div>
            <div className="text-[28px] font-bold text-[#F2F1EC] mt-1">
              ₦{state.goals.yearlyGoal.toLocaleString()}
            </div>
            <div className="text-[12px] text-[#8E9E99] mt-1">
              12-month studio run starting {state.goals.startDate} · Received so far: ₦
              {totalAnnualReceived.toLocaleString()} ({annualPct}%)
            </div>
            <div className="mt-3 h-2.5 w-full rounded-[8px] bg-[#0E1412] border border-[#24332F] overflow-hidden">
              <div
                className="h-full bg-[#2BA5A0] transition-all"
                style={{ width: `${annualPct}%` }}
              />
            </div>
          </div>

          <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5">
            <div className="text-[14px] font-bold text-[#2BA5A0] mb-2">12-Month Breakdown</div>
            <div className="space-y-1.5">
              {state.monthlyRecords.map((m) => (
                <div
                  key={m.monthIndex}
                  className="flex items-center justify-between text-[12px] py-1.5 border-b border-[#24332F] last:border-0"
                >
                  <span className="font-medium text-[#F2F1EC]">{m.label}</span>
                  <span className="text-[#8E9E99]">
                    ₦{m.revenueReceived.toLocaleString()} / ₦{m.revenueTarget.toLocaleString()} ·{' '}
                    {m.websitesClosed} sites · {m.activeRetainers} retainers
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* PIPELINE VIEW */}
      {subTab === 'PIPELINE' && (
        <div className="space-y-4">
          <div>
            <h3 className="text-[14px] font-bold text-[#2BA5A0]">
              Studio Sales & Delivery Pipeline
            </h3>
            <p className="text-[12px] text-[#8E9E99]">
              Systemized checklist steps for client acquisition, onboarding, and project delivery.
            </p>
          </div>

          {Object.entries(PIPELINE_DEFINITIONS).map(([stageKey, stageDef]) => {
            const itemsInStage = pipelineItems.filter(
              (it) =>
                it.sourceText === `web_pipeline_${stageKey}` ||
                it.details === `Stage: ${stageKey}`
            );
            const completedCount = itemsInStage.filter((i) => i.status === 'COMPLETED').length;

            return (
              <div
                key={stageKey}
                className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-2"
              >
                <div className="flex items-center justify-between">
                  <span className="text-[13px] font-bold text-[#2BA5A0]">{stageDef.label}</span>
                  <span className="text-[11px] font-semibold text-[#8E9E99]">
                    {completedCount} / {itemsInStage.length}
                  </span>
                </div>
                {itemsInStage.map((item) => {
                  const done = item.status === 'COMPLETED';
                  return (
                    <label
                      key={item.id}
                      className="flex items-start gap-2.5 py-1.5 border-t border-[#24332F] cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={done}
                        onChange={() => (done ? onReopenItem(item) : onCompleteItem(item))}
                        className="mt-0.5 h-4 w-4 accent-[#2BA5A0] rounded-[4px]"
                      />
                      <span
                        className={`text-[13px] ${
                          done ? 'text-[#8E9E99] line-through' : 'text-[#F2F1EC]'
                        }`}
                      >
                        {item.title}
                      </span>
                    </label>
                  );
                })}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
