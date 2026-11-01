import React, { useEffect, useRef, useState } from 'react';
import {
  AssistantDatabaseState,
  Fact,
  Goal,
  ReminderSchedule,
  UserSettings,
} from '../types';

interface SettingsViewProps {
  state: AssistantDatabaseState;
  onUpdateSettings: (patch: Partial<UserSettings>) => void;
  onUpdateGoals: (patch: Partial<Goal>) => void;
  onTestWakeUpInOneMinute: () => void;
  onTriggerBriefingNow: (title: string) => void;
  onExportBackup: () => void;
  onImportBackupJson: (jsonStr: string) => boolean;
  onAddFact: (text: string) => void;
  onDeleteFact: (factId: number) => void;
  onClearFacts: () => void;
  onClearChatHistory: () => void;
  onTestAiConnection: () => Promise<{ ok: boolean; message: string }>;
  onToggleReminderActive: (id: string) => void;
  onToggleReminderVoice: (id: string) => void;
  onUpdateReminderTime: (id: string, timeString: string) => void;
  onAddCustomReminder: (title: string, timeString: string, isRepeating: boolean) => void;
  onDeleteCustomReminder: (id: string) => void;
  onTestVoice: () => void;
  onResetTargetsDefault: () => void;
}

export const SettingsView: React.FC<SettingsViewProps> = ({
  state,
  onUpdateSettings,
  onUpdateGoals,
  onTestWakeUpInOneMinute,
  onTriggerBriefingNow,
  onExportBackup,
  onImportBackupJson,
  onAddFact,
  onDeleteFact,
  onClearFacts,
  onClearChatHistory,
  onTestAiConnection,
  onToggleReminderActive,
  onToggleReminderVoice,
  onUpdateReminderTime,
  onAddCustomReminder,
  onDeleteCustomReminder,
  onTestVoice,
  onResetTargetsDefault,
}) => {
  const { settings, goals, reminders, facts } = state;

  const [wakeUpInput, setWakeUpInput] = useState(settings.wakeUpTime);
  const [nameInput, setNameInput] = useState(settings.userName);
  const [quietStart, setQuietStart] = useState(settings.quietHoursStart);
  const [quietEnd, setQuietEnd] = useState(settings.quietHoursEnd);

  const [isTestingAi, setIsTestingAi] = useState(false);
  const [aiTestResult, setAiTestResult] = useState<{ ok: boolean; message: string } | null>(null);

  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);

  // Modals & inline confirmations
  const [showMemoryModal, setShowMemoryModal] = useState(false);
  const [newFactInput, setNewFactInput] = useState('');

  const [showImportModal, setShowImportModal] = useState(false);
  const [importText, setImportText] = useState('');
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const [showAddReminderModal, setShowAddReminderModal] = useState(false);
  const [newReminderTitle, setNewReminderTitle] = useState('');
  const [newReminderTime, setNewReminderTime] = useState('15:00');
  const [newReminderRepeat, setNewReminderRepeat] = useState(true);

  const [showSetupGuide, setShowSetupGuide] = useState(false);
  const [confirmClearChat, setConfirmClearChat] = useState(false);
  const [confirmResetTargets, setConfirmResetTargets] = useState(false);

  const [notifPermission, setNotifPermission] = useState<string>(() =>
    typeof Notification !== 'undefined' ? Notification.permission : 'unsupported'
  );

  useEffect(() => {
    setWakeUpInput(settings.wakeUpTime);
    setNameInput(settings.userName);
    setQuietStart(settings.quietHoursStart);
    setQuietEnd(settings.quietHoursEnd);
  }, [
    settings.wakeUpTime,
    settings.userName,
    settings.quietHoursStart,
    settings.quietHoursEnd,
  ]);

  useEffect(() => {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    const loadVoices = () => {
      setVoices(window.speechSynthesis.getVoices());
    };
    loadVoices();
    window.speechSynthesis.onvoiceschanged = loadVoices;
  }, []);

  const handleRequestNotifications = async () => {
    if (typeof Notification === 'undefined') return;
    try {
      const res = await Notification.requestPermission();
      setNotifPermission(res);
    } catch {
      // Ignore iframe restrictions
    }
  };

  const handleFileUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (evt) => {
      const content = String(evt.target?.result || '');
      if (onImportBackupJson(content)) {
        setShowImportModal(false);
        setImportText('');
      }
      if (fileInputRef.current) fileInputRef.current.value = '';
    };
    reader.readAsText(file);
  };

  if (showSetupGuide) {
    const hasSpeechRecognition = Boolean(
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
    );
    const hasSpeechSynthesis = typeof window !== 'undefined' && 'speechSynthesis' in window;

    return (
      <div className="max-w-[680px] mx-auto px-4 pt-4 pb-24 space-y-3.5">
        <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4">
          <h2 className="text-[18px] font-bold text-[#F2F1EC]">First-Run Setup & Permissions</h2>
          <p className="text-[13px] text-[#8E9E99] mt-1">
            Verify notifications, microphone voice input, and spoken briefings for proactive
            reminders.
          </p>
        </div>

        <div
          className={`rounded-[8px] border bg-[#161F1C] p-3.5 ${
            notifPermission === 'granted' ? 'border-[#2BA5A0]' : 'border-[#24332F]'
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="text-[14px] font-semibold text-[#F2F1EC]">1. Browser Notifications</span>
            <span
              className={`text-[12px] font-bold ${
                notifPermission === 'granted' ? 'text-[#2BA5A0]' : 'text-[#E05349]'
              }`}
            >
              {notifPermission === 'granted' ? 'Allowed' : notifPermission}
            </span>
          </div>
          <p className="text-[12px] text-[#8E9E99] mt-1">
            Displays scheduled morning, midday, evening, and person reminder alerts.
          </p>
          {notifPermission !== 'granted' && notifPermission !== 'unsupported' && (
            <button
              type="button"
              onClick={handleRequestNotifications}
              className="mt-2.5 rounded-[8px] bg-[#2BA5A0] px-3.5 py-1.5 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
            >
              Grant Notification Permission
            </button>
          )}
        </div>

        <div
          className={`rounded-[8px] border bg-[#161F1C] p-3.5 ${
            hasSpeechRecognition ? 'border-[#2BA5A0]' : 'border-[#24332F]'
          }`}
        >
          <div className="flex items-center justify-between">
            <span className="text-[14px] font-semibold text-[#F2F1EC]">
              2. Voice Capture & Speech Engine
            </span>
            <span className="text-[12px] font-bold text-[#2BA5A0]">
              {hasSpeechSynthesis ? 'Ready' : 'Text Mode'}
            </span>
          </div>
          <p className="text-[12px] text-[#8E9E99] mt-1">
            Voice input: {hasSpeechRecognition ? 'Supported' : 'Use text input in this browser'} ·
            Spoken TTS playback: {hasSpeechSynthesis ? 'Active' : 'Unavailable'}
          </p>
          <button
            type="button"
            onClick={onTestVoice}
            className="mt-2.5 rounded-[8px] bg-[#1E2A27] border border-[#24332F] px-3.5 py-1.5 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
          >
            Test Spoken Voice Now
          </button>
        </div>

        <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5">
          <div className="text-[14px] font-semibold text-[#F2F1EC]">
            3. Test Alarm & Briefing in 1 Minute
          </div>
          <p className="text-[12px] text-[#8E9E99] mt-1">
            Schedules a live test wake-up briefing 60 seconds from now to verify visual and spoken
            alerts.
          </p>
          <button
            type="button"
            onClick={onTestWakeUpInOneMinute}
            className="mt-2.5 w-full rounded-[8px] bg-[#1E2A27] border border-[#24332F] py-2 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
          >
            Test reminder in 1 minute
          </button>
        </div>

        <button
          type="button"
          onClick={() => {
            onUpdateSettings({ isFirstRunComplete: true });
            setShowSetupGuide(false);
          }}
          className="w-full rounded-[8px] bg-[#2BA5A0] py-3 text-[13px] font-bold text-[#0E1412] cursor-pointer"
        >
          Complete Setup and Continue
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-[680px] mx-auto px-4 pt-4 pb-24 space-y-3.5">
      {/* 1. Wake-up Alarm & Briefing */}
      <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-2.5">
        <div>
          <h3 className="text-[15px] font-semibold text-[#F2F1EC]">Wake-up Alarm & Briefing</h3>
          <p className="text-[12px] text-[#8E9E99]">
            Triggers an alert banner and speaks the day&apos;s plan aloud at your wake time.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <input
            type="time"
            value={wakeUpInput}
            onChange={(e) => setWakeUpInput(e.target.value)}
            className="flex-1 rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC]"
          />
          <button
            type="button"
            onClick={() => onUpdateSettings({ wakeUpTime: wakeUpInput })}
            className="rounded-[8px] bg-[#2BA5A0] px-4 py-2 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
          >
            Set
          </button>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <button
            type="button"
            onClick={onTestWakeUpInOneMinute}
            className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-2 px-3 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
          >
            Test wake-up in 1 min
          </button>
          <button
            type="button"
            onClick={() => onTriggerBriefingNow('Morning briefing')}
            className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-2 px-3 text-[12px] font-medium text-[#2BA5A0] hover:border-[#2BA5A0] cursor-pointer"
          >
            Speak Briefing Now
          </button>
        </div>
      </div>

      {/* 2. Data Safety, Memory & Backup */}
      <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-2.5">
        <div>
          <h3 className="text-[15px] font-semibold text-[#F2F1EC]">
            Data Safety, Memory & Backup
          </h3>
          <p className="text-[12px] text-[#8E9E99]">
            Sanitized JSON backup and idempotent import compatible with Web Goal Tracker exports.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <button
            type="button"
            onClick={onExportBackup}
            className="rounded-[8px] bg-[#2BA5A0] py-2 px-3 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
          >
            Back up now (JSON)
          </button>
          <button
            type="button"
            onClick={() => setShowImportModal(true)}
            className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-2 px-3 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
          >
            Restore / Import
          </button>
        </div>
        <div className="grid grid-cols-2 gap-2">
          <button
            type="button"
            onClick={() => setShowMemoryModal(true)}
            className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-2 px-3 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
          >
            Manage Memory ({facts.length})
          </button>
          {!confirmClearChat ? (
            <button
              type="button"
              onClick={() => setConfirmClearChat(true)}
              className="rounded-[8px] border border-[#24332F] bg-[#0E1412] py-2 px-3 text-[12px] font-medium text-[#E05349] hover:border-[#E05349] cursor-pointer"
            >
              Clear Chat History
            </button>
          ) : (
            <button
              type="button"
              onClick={() => {
                onClearChatHistory();
                setConfirmClearChat(false);
              }}
              className="rounded-[8px] bg-[#E05349] py-2 px-3 text-[12px] font-bold text-white cursor-pointer"
            >
              Confirm Clear Chat
            </button>
          )}
        </div>
      </div>

      {/* 3. AI Layer (Server-Side Gemini Coach) */}
      <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-2.5">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-[15px] font-semibold text-[#F2F1EC]">AI Layer (Gemini)</h3>
            <p className="text-[12px] text-[#8E9E99]">
              Online NLU & coach powered server-side. Falls back automatically to offline parser.
            </p>
          </div>
          <input
            type="checkbox"
            checked={settings.isAiEnabled}
            onChange={(e) => onUpdateSettings({ isAiEnabled: e.target.checked })}
            className="h-4 w-4 accent-[#2BA5A0]"
          />
        </div>

        <div className="flex items-center gap-2">
          <select
            value={settings.geminiModel}
            onChange={(e) => onUpdateSettings({ geminiModel: e.target.value })}
            className="flex-1 rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC]"
          >
            <option value="gemini-3.8-flash">gemini-3.8-flash (Recommended)</option>
            <option value="gemini-3.1-flash-lite">gemini-3.1-flash-lite (Fast)</option>
          </select>
          <button
            type="button"
            disabled={isTestingAi}
            onClick={async () => {
              setIsTestingAi(true);
              setAiTestResult(null);
              const res = await onTestAiConnection();
              setAiTestResult(res);
              setIsTestingAi(false);
            }}
            className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] px-3.5 py-2 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] disabled:opacity-50 cursor-pointer"
          >
            {isTestingAi ? 'Testing...' : 'Test AI'}
          </button>
        </div>

        {aiTestResult && (
          <div
            className={`text-[12px] font-medium ${
              aiTestResult.ok ? 'text-[#2BA5A0]' : 'text-[#E05349]'
            }`}
          >
            {aiTestResult.message}
          </div>
        )}
      </div>

      {/* 4. Voice Playback Settings */}
      <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-2.5">
        <div className="flex items-center justify-between">
          <h3 className="text-[15px] font-semibold text-[#F2F1EC]">Voice Playback (TTS)</h3>
          <button
            type="button"
            onClick={onTestVoice}
            className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] px-3 py-1 text-[11px] text-[#2BA5A0] hover:border-[#2BA5A0] cursor-pointer"
          >
            Test Voice
          </button>
        </div>
        {voices.length > 0 && (
          <div>
            <label className="block text-[11px] text-[#8E9E99] mb-1">System Voice</label>
            <select
              value={settings.voiceUri}
              onChange={(e) => onUpdateSettings({ voiceUri: e.target.value })}
              className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[12px] text-[#F2F1EC]"
            >
              <option value="">Default System Voice</option>
              {voices.map((v) => (
                <option key={v.voiceURI} value={v.voiceURI}>
                  {v.name} ({v.lang})
                </option>
              ))}
            </select>
          </div>
        )}
        <div>
          <div className="flex justify-between text-[12px] text-[#8E9E99] mb-1">
            <span>Speech Speed</span>
            <span className="font-semibold text-[#F2F1EC]">{settings.voiceSpeed.toFixed(1)}x</span>
          </div>
          <input
            type="range"
            min={0.7}
            max={1.5}
            step={0.1}
            value={settings.voiceSpeed}
            onChange={(e) => onUpdateSettings({ voiceSpeed: parseFloat(e.target.value) })}
            className="w-full accent-[#2BA5A0]"
          />
        </div>
      </div>

      {/* 5. Scheduled Accountability Briefings */}
      <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-2.5">
        <div className="flex items-center justify-between">
          <h3 className="text-[15px] font-semibold text-[#F2F1EC]">Scheduled Briefings</h3>
          <button
            type="button"
            onClick={() => setShowAddReminderModal(true)}
            className="rounded-[8px] bg-[#2BA5A0] px-3 py-1.5 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
          >
            + Add
          </button>
        </div>

        <div className="space-y-2">
          {reminders.map((rem: ReminderSchedule) => (
            <div
              key={rem.id}
              className="rounded-[8px] border border-[#24332F] bg-[#0E1412] p-2.5 flex flex-wrap items-center justify-between gap-2"
            >
              <div className="min-w-[140px]">
                <div className="text-[13px] font-medium text-[#F2F1EC]">{rem.title}</div>
                <div className="text-[11px] text-[#8E9E99]">
                  {rem.daysOfWeek === 'ALL' ? 'Every day' : rem.daysOfWeek}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="time"
                  value={rem.timeString}
                  onChange={(e) => onUpdateReminderTime(rem.id, e.target.value)}
                  className="rounded-[8px] border border-[#24332F] bg-[#161F1C] px-2 py-1 text-[12px] text-[#F2F1EC]"
                />
                <button
                  type="button"
                  onClick={() => onToggleReminderVoice(rem.id)}
                  className={`rounded-[8px] border px-2 py-1 text-[11px] cursor-pointer ${
                    rem.voiceEnabled
                      ? 'border-[#2BA5A0] text-[#2BA5A0]'
                      : 'border-[#24332F] text-[#8E9E99]'
                  }`}
                >
                  {rem.voiceEnabled ? 'Voice On' : 'Silent'}
                </button>
                <input
                  type="checkbox"
                  checked={rem.isActive}
                  onChange={() => onToggleReminderActive(rem.id)}
                  className="h-4 w-4 accent-[#2BA5A0]"
                />
                {!rem.isBuiltIn && (
                  <button
                    type="button"
                    onClick={() => onDeleteCustomReminder(rem.id)}
                    className="text-[11px] font-bold text-[#E05349] px-1 cursor-pointer"
                  >
                    Remove
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* 6. Profile, Quiet Hours & Studio Targets */}
      <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 space-y-3">
        <h3 className="text-[15px] font-semibold text-[#F2F1EC]">
          Profile, Quiet Hours & Studio Targets
        </h3>
        <div>
          <label className="block text-[11px] text-[#8E9E99] mb-1">
            Your Name (spoken by assistant)
          </label>
          <input
            type="text"
            value={nameInput}
            onChange={(e) => setNameInput(e.target.value)}
            className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC]"
          />
        </div>

        <div className="flex items-center justify-between">
          <span className="text-[13px] text-[#F2F1EC]">
            Quiet Hours Active (suppresses automatic voice)
          </span>
          <input
            type="checkbox"
            checked={settings.quietHoursEnabled}
            onChange={(e) => onUpdateSettings({ quietHoursEnabled: e.target.checked })}
            className="h-4 w-4 accent-[#2BA5A0]"
          />
        </div>

        {settings.quietHoursEnabled && (
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="block text-[11px] text-[#8E9E99] mb-1">Quiet Start</label>
              <input
                type="time"
                value={quietStart}
                onChange={(e) => setQuietStart(e.target.value)}
                className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-1.5 text-[13px] text-[#F2F1EC]"
              />
            </div>
            <div>
              <label className="block text-[11px] text-[#8E9E99] mb-1">Quiet End</label>
              <input
                type="time"
                value={quietEnd}
                onChange={(e) => setQuietEnd(e.target.value)}
                className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-1.5 text-[13px] text-[#F2F1EC]"
              />
            </div>
          </div>
        )}

        <div className="grid grid-cols-2 gap-2 pt-1 border-t border-[#24332F]">
          <div>
            <label className="block text-[11px] text-[#8E9E99] mb-1">Annual Goal (₦)</label>
            <input
              type="number"
              value={goals.yearlyGoal}
              onChange={(e) => onUpdateGoals({ yearlyGoal: Number(e.target.value) || 0 })}
              className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-2.5 py-1.5 text-[13px] text-[#F2F1EC]"
            />
          </div>
          <div>
            <label className="block text-[11px] text-[#8E9E99] mb-1">Weekly Messages Target</label>
            <input
              type="number"
              value={goals.weeklyMessagesTarget}
              onChange={(e) =>
                onUpdateGoals({ weeklyMessagesTarget: parseInt(e.target.value, 10) || 0 })
              }
              className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-2.5 py-1.5 text-[13px] text-[#F2F1EC]"
            />
          </div>
        </div>

        <div className="flex gap-2">
          <button
            type="button"
            onClick={() =>
              onUpdateSettings({
                userName: nameInput.trim() || 'Studio Lead',
                quietHoursStart: quietStart,
                quietHoursEnd: quietEnd,
              })
            }
            className="flex-1 rounded-[8px] bg-[#2BA5A0] py-2 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
          >
            Save Profile & Hours
          </button>
          {!confirmResetTargets ? (
            <button
              type="button"
              onClick={() => setConfirmResetTargets(true)}
              className="rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[12px] text-[#8E9E99] hover:text-[#F2F1EC] cursor-pointer"
            >
              Reset Targets
            </button>
          ) : (
            <button
              type="button"
              onClick={() => {
                onResetTargetsDefault();
                setConfirmResetTargets(false);
              }}
              className="rounded-[8px] bg-[#E05349] px-3 py-2 text-[12px] font-semibold text-white cursor-pointer"
            >
              Confirm Reset
            </button>
          )}
        </div>
      </div>

      {/* 7. Setup Guide Button */}
      <button
        type="button"
        onClick={() => setShowSetupGuide(true)}
        className="w-full rounded-[8px] border border-[#2BA5A0] bg-[#1E2A27] py-2.5 text-[13px] font-semibold text-[#2BA5A0] hover:bg-[#2BA5A0] hover:text-[#0E1412] transition-colors cursor-pointer"
      >
        Open Setup & Permissions Check
      </button>

      {/* Memory (Facts) Modal */}
      {showMemoryModal && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
          <div className="w-full max-w-[460px] rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4 space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-[16px] font-bold text-[#F2F1EC]">Assistant Memory (Facts)</h3>
              {facts.length > 0 && (
                <button
                  type="button"
                  onClick={onClearFacts}
                  className="text-[11px] font-semibold text-[#E05349] cursor-pointer"
                >
                  Clear all
                </button>
              )}
            </div>
            <p className="text-[12px] text-[#8E9E99]">
              Persistent facts from &ldquo;remember that...&rdquo; injected into AI context.
            </p>
            <div className="flex gap-2">
              <input
                type="text"
                value={newFactInput}
                onChange={(e) => setNewFactInput(e.target.value)}
                placeholder="Add fact manually..."
                className="flex-1 rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-1.5 text-[13px] text-[#F2F1EC]"
              />
              <button
                type="button"
                onClick={() => {
                  if (newFactInput.trim()) {
                    onAddFact(newFactInput.trim());
                    setNewFactInput('');
                  }
                }}
                className="rounded-[8px] bg-[#2BA5A0] px-3.5 py-1.5 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
              >
                Add
              </button>
            </div>
            <div className="max-h-[260px] overflow-y-auto space-y-2">
              {facts.length === 0 ? (
                <div className="py-6 text-center text-[12px] text-[#8E9E99]">
                  No facts remembered yet.
                </div>
              ) : (
                facts.map((fact: Fact) => (
                  <div
                    key={fact.id}
                    className="rounded-[8px] border border-[#24332F] bg-[#0E1412] p-2.5 flex items-center justify-between gap-2"
                  >
                    <div>
                      <div className="text-[12px] text-[#F2F1EC]">{fact.text}</div>
                      <div className="text-[10px] text-[#2BA5A0]">Category: {fact.category}</div>
                    </div>
                    <button
                      type="button"
                      onClick={() => onDeleteFact(fact.id)}
                      className="text-[12px] font-bold text-[#E05349] px-1.5 cursor-pointer"
                    >
                      Remove
                    </button>
                  </div>
                ))
              )}
            </div>
            <div className="flex justify-end">
              <button
                type="button"
                onClick={() => setShowMemoryModal(false)}
                className="rounded-[8px] bg-[#2BA5A0] px-4 py-1.5 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Restore / Import JSON Modal */}
      {showImportModal && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
          <div className="w-full max-w-[460px] rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4 space-y-3">
            <h3 className="text-[16px] font-bold text-[#F2F1EC]">Restore / Import JSON</h3>
            <p className="text-[12px] text-[#8E9E99]">
              Upload a JSON backup file or paste JSON exported from Web Goal Tracker or Assistant.
              Merged idempotently without duplicates.
            </p>
            <div>
              <input
                ref={fileInputRef}
                type="file"
                accept=".json,application/json"
                onChange={handleFileUpload}
                className="hidden"
              />
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="w-full rounded-[8px] border border-[#2BA5A0] bg-[#1E2A27] py-2 text-[12px] font-semibold text-[#2BA5A0] cursor-pointer"
              >
                Choose .json Backup File
              </button>
            </div>
            <textarea
              rows={5}
              value={importText}
              onChange={(e) => setImportText(e.target.value)}
              placeholder='{"settings": ..., "pipeline": ...}'
              className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] p-2.5 text-[12px] text-[#F2F1EC] font-mono"
            />
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setShowImportModal(false)}
                className="rounded-[8px] border border-[#24332F] px-3.5 py-1.5 text-[12px] text-[#8E9E99] cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={!importText.trim()}
                onClick={() => {
                  if (onImportBackupJson(importText)) {
                    setShowImportModal(false);
                    setImportText('');
                  }
                }}
                className="rounded-[8px] bg-[#2BA5A0] px-4 py-1.5 text-[12px] font-semibold text-[#0E1412] disabled:opacity-40 cursor-pointer"
              >
                Import JSON
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Add Custom Briefing Modal */}
      {showAddReminderModal && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
          <div className="w-full max-w-[400px] rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4 space-y-3">
            <h3 className="text-[16px] font-bold text-[#F2F1EC]">Add Custom Briefing</h3>
            <div>
              <label className="block text-[11px] text-[#8E9E99] mb-1">Title</label>
              <input
                type="text"
                value={newReminderTitle}
                onChange={(e) => setNewReminderTitle(e.target.value)}
                placeholder="Afternoon follow-up block"
                className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC]"
              />
            </div>
            <div>
              <label className="block text-[11px] text-[#8E9E99] mb-1">Time (HH:mm)</label>
              <input
                type="time"
                value={newReminderTime}
                onChange={(e) => setNewReminderTime(e.target.value)}
                className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC]"
              />
            </div>
            <label className="flex items-center justify-between text-[13px] text-[#F2F1EC]">
              <span>Repeat daily</span>
              <input
                type="checkbox"
                checked={newReminderRepeat}
                onChange={(e) => setNewReminderRepeat(e.target.checked)}
                className="h-4 w-4 accent-[#2BA5A0]"
              />
            </label>
            <div className="flex justify-end gap-2 pt-1">
              <button
                type="button"
                onClick={() => setShowAddReminderModal(false)}
                className="rounded-[8px] border border-[#24332F] px-3.5 py-1.5 text-[12px] text-[#8E9E99] cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => {
                  if (newReminderTitle.trim() && newReminderTime) {
                    onAddCustomReminder(newReminderTitle.trim(), newReminderTime, newReminderRepeat);
                    setNewReminderTitle('');
                    setShowAddReminderModal(false);
                  }
                }}
                className="rounded-[8px] bg-[#2BA5A0] px-4 py-1.5 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
              >
                Add
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
