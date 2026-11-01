import React, { useEffect, useRef, useState } from 'react';
import { Mic, Square, Send, VolumeX, MessageSquare, Check, Clock } from 'lucide-react';
import {
  ActiveAlertBanner,
  ChatMessage,
  Item,
  PlannerProposal,
  UserSettings,
} from '../types';

interface HomeViewProps {
  settings: UserSettings;
  messages: ChatMessage[];
  activeProposal: PlannerProposal | null;
  pendingItems: Item[];
  isProcessing: boolean;
  isSpeaking: boolean;
  activeAlert: ActiveAlertBanner | null;
  onSendInput: (text: string, isSpoken: boolean) => void;
  onAcceptProposal: (proposal: PlannerProposal, customTime?: string) => void;
  onRejectProposal: () => void;
  onCompleteItem: (item: Item) => void;
  onStopSpeaking: () => void;
  onOpenChatContact: (contactName: string, messageHint: string) => void;
  onSnoozeAlert: (alert: ActiveAlertBanner, minutes: number) => void;
  onDismissAlert: () => void;
}

interface SpeechRecognitionInstance {
  lang: string;
  interimResults: boolean;
  continuous: boolean;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: ((event: any) => void) | null;
  onerror: ((event: any) => void) | null;
  onend: (() => void) | null;
}

export const HomeView: React.FC<HomeViewProps> = ({
  settings,
  messages,
  activeProposal,
  pendingItems,
  isProcessing,
  isSpeaking,
  activeAlert,
  onSendInput,
  onAcceptProposal,
  onRejectProposal,
  onCompleteItem,
  onStopSpeaking,
  onOpenChatContact,
  onSnoozeAlert,
  onDismissAlert,
}) => {
  const [textInput, setTextInput] = useState('');
  const [isListening, setIsListening] = useState(false);
  const [liveTranscript, setLiveTranscript] = useState('');
  const [micError, setMicError] = useState<string | null>(null);
  const [showCustomSchedule, setShowCustomSchedule] = useState(false);
  const [customProposalTime, setCustomProposalTime] = useState('16:00');

  const feedEndRef = useRef<HTMLDivElement | null>(null);
  const recognizerRef = useRef<SpeechRecognitionInstance | null>(null);

  useEffect(() => {
    feedEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages.length, activeProposal, isProcessing]);

  useEffect(() => {
    return () => {
      try {
        recognizerRef.current?.abort();
      } catch {
        // Ignore cleanup errors
      }
    };
  }, []);

  const stopRecording = () => {
    try {
      recognizerRef.current?.stop();
    } catch {
      // Ignore
    }
    recognizerRef.current = null;
    setIsListening(false);
  };

  const startRecording = () => {
    const SpeechRec =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRec) {
      setMicError('Browser voice recognition is unavailable here. Use the text prompt below.');
      return;
    }

    stopRecording();
    setMicError(null);
    setLiveTranscript('');
    setIsListening(true);

    try {
      const sr: SpeechRecognitionInstance = new SpeechRec();
      sr.lang = navigator.language || 'en-US';
      sr.interimResults = true;
      sr.continuous = false;

      let finalSpoken = '';

      sr.onresult = (event: any) => {
        let interim = '';
        for (let i = event.resultIndex; i < event.results.length; i++) {
          const transcript = event.results[i][0]?.transcript || '';
          if (event.results[i].isFinal) {
            finalSpoken += transcript;
          } else {
            interim += transcript;
          }
        }
        setLiveTranscript((finalSpoken || interim).trim());
      };

      sr.onerror = (event: any) => {
        setIsListening(false);
        if (event?.error === 'not-allowed') {
          setMicError('Microphone permission is required for voice capture.');
        } else if (event?.error === 'no-speech') {
          setMicError('No speech detected. Tap MIC to try again.');
        } else {
          setMicError('Voice input ended.');
        }
      };

      sr.onend = () => {
        setIsListening(false);
        const trimmed = finalSpoken.trim();
        if (trimmed) {
          setLiveTranscript(trimmed);
          onSendInput(trimmed, true);
        }
      };

      sr.start();
      recognizerRef.current = sr;
    } catch {
      setIsListening(false);
      setMicError('Could not start microphone recognition.');
    }
  };

  const hour = new Date().getHours();
  const timeWord = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  const greetingText = `${timeWord}, ${settings.userName}`;

  const corePending = pendingItems.filter(
    (it) => !it.sourceText.startsWith('web_pipeline_') && it.type !== 'NOTE'
  );
  const nextUpItem =
    corePending
      .filter((it) => it.dueAt != null && it.dueAt > Date.now())
      .sort((a, b) => (a.dueAt || 0) - (b.dueAt || 0))[0] || corePending[0];

  const formatTimeShort = (millis: number | null) => {
    if (!millis) return 'Next';
    const d = new Date(millis);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  };

  const handleFormSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!textInput.trim() || isProcessing) return;
    const msg = textInput;
    setTextInput('');
    onSendInput(msg, false);
  };

  return (
    <div className="flex flex-col h-[calc(100vh-64px)] max-w-[680px] mx-auto px-4 pt-4 pb-3">
      {/* 32sp System Serif Greeting */}
      <h1 className="font-serif text-[32px] leading-[38px] font-normal text-[#F2F1EC] tracking-tight mb-3">
        {greetingText}
      </h1>

      {/* Active Proactive Alert / Person Reminder Banner */}
      {activeAlert && (
        <div className="mb-3 rounded-[8px] border border-[#2BA5A0] bg-[#161F1C] p-3.5">
          <div className="flex items-center justify-between gap-2 mb-1">
            <span className="text-[11px] font-bold uppercase tracking-wider text-[#2BA5A0]">
              {activeAlert.kind.replace('_', ' ')}
            </span>
            <span className="text-[11px] text-[#8E9E99]">Active now</span>
          </div>
          <div className="text-[14px] font-semibold text-[#F2F1EC]">{activeAlert.title}</div>
          <div className="text-[13px] text-[#F2F1EC]/80 mt-0.5">{activeAlert.message}</div>
          <div className="flex flex-wrap gap-2 mt-3">
            {activeAlert.contactName && (
              <button
                type="button"
                onClick={() => onOpenChatContact(activeAlert.contactName!, activeAlert.title)}
                className="inline-flex items-center gap-1.5 rounded-[8px] bg-[#2BA5A0] px-3 py-1.5 text-[12px] font-semibold text-[#0E1412] hover:opacity-90 cursor-pointer"
              >
                <MessageSquare className="w-3.5 h-3.5" />
                Open chat
              </button>
            )}
            <button
              type="button"
              onClick={() => {
                if (activeAlert.itemId) {
                  const found = pendingItems.find((i) => i.id === activeAlert.itemId);
                  if (found) onCompleteItem(found);
                }
                onDismissAlert();
              }}
              className="inline-flex items-center gap-1.5 rounded-[8px] bg-[#1E2A27] border border-[#24332F] px-3 py-1.5 text-[12px] font-semibold text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
            >
              <Check className="w-3.5 h-3.5 text-[#2BA5A0]" />
              Done
            </button>
            <button
              type="button"
              onClick={() => onSnoozeAlert(activeAlert, 15)}
              className="inline-flex items-center gap-1.5 rounded-[8px] bg-[#1E2A27] border border-[#24332F] px-3 py-1.5 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
            >
              <Clock className="w-3.5 h-3.5 text-[#8E9E99]" />
              Snooze 15m
            </button>
          </div>
        </div>
      )}

      {/* Next Up Card */}
      {nextUpItem && (
        <div className="mb-3 rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5 flex items-center justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5">
              <span className="text-[10px] font-bold uppercase tracking-wider text-[#2BA5A0]">
                NEXT UP
              </span>
              <span className="text-[11px] text-[#8E9E99]">
                · {formatTimeShort(nextUpItem.dueAt)}
              </span>
              {nextUpItem.contactName && (
                <span className="text-[11px] text-[#2BA5A0] font-medium">
                  · {nextUpItem.contactName}
                </span>
              )}
            </div>
            <div className="text-[14px] font-semibold text-[#F2F1EC] truncate mt-0.5">
              {nextUpItem.title}
            </div>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {nextUpItem.contactName && (
              <button
                type="button"
                onClick={() => onOpenChatContact(nextUpItem.contactName!, nextUpItem.title)}
                className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] px-2.5 py-1.5 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
              >
                Open chat
              </button>
            )}
            <button
              type="button"
              onClick={() => onCompleteItem(nextUpItem)}
              className="rounded-[8px] bg-[#2BA5A0] px-3 py-1.5 text-[12px] font-semibold text-[#0E1412] hover:opacity-90 cursor-pointer"
            >
              Done
            </button>
          </div>
        </div>
      )}

      {/* Live Transcript */}
      {(isListening || liveTranscript) && (
        <div className="mb-2 rounded-[8px] border border-[#2BA5A0] bg-[#2BA5A0]/12 px-3 py-2 flex items-center justify-between gap-2">
          <div className="text-[12px]">
            <span className="font-bold text-[#2BA5A0]">
              {isListening ? 'Listening: ' : 'Heard: '}
            </span>
            <span className="text-[#F2F1EC]">{liveTranscript || 'Say anything...'}</span>
          </div>
          {!isListening && liveTranscript && (
            <button
              type="button"
              onClick={() => setLiveTranscript('')}
              className="text-[11px] text-[#8E9E99] hover:text-[#F2F1EC] cursor-pointer"
            >
              Clear
            </button>
          )}
        </div>
      )}

      {micError && (
        <div className="mb-2 text-[12px] text-[#E05349] font-medium">{micError}</div>
      )}

      {/* Stop Speech Control */}
      {isSpeaking && (
        <button
          type="button"
          onClick={onStopSpeaking}
          className="mb-2 w-full rounded-[8px] bg-[#E05349] py-2 px-3 text-[13px] font-semibold text-white flex items-center justify-center gap-2 hover:opacity-90 cursor-pointer"
        >
          <VolumeX className="w-4 h-4" />
          Stop Speech
        </button>
      )}

      {/* Conversational Feed */}
      <div className="flex-1 overflow-y-auto space-y-2.5 pr-1 min-h-0">
        {messages.map((msg) => {
          const isAssistant = msg.sender === 'Assistant';
          return (
            <div
              key={msg.id}
              className={`rounded-[8px] border p-3 ${
                isAssistant
                  ? 'bg-[#161F1C] border-[#24332F]'
                  : 'bg-[#2BA5A0]/10 border-[#2BA5A0]/35'
              }`}
            >
              <div className="flex items-center justify-between mb-1">
                <span
                  className={`text-[12px] font-bold ${
                    isAssistant ? 'text-[#2BA5A0]' : 'text-[#F2F1EC]'
                  }`}
                >
                  {msg.sender}
                </span>
                <span className="text-[10px] text-[#8E9E99]">
                  {new Date(msg.timestamp).toLocaleTimeString([], {
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </span>
              </div>
              <p className="text-[14px] text-[#F2F1EC] whitespace-pre-line leading-relaxed">
                {msg.text}
              </p>
              {msg.executedActions.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {msg.executedActions.map((act, i) => (
                    <span
                      key={i}
                      className="rounded-[8px] bg-[#2BA5A0]/15 px-2.5 py-1 text-[11px] font-semibold text-[#2BA5A0]"
                    >
                      Action: {act}
                    </span>
                  ))}
                </div>
              )}
            </div>
          );
        })}

        {/* Interactive Planner Recommendation ("Now or Later") */}
        {activeProposal && (
          <div className="rounded-[8px] border-[1.5px] border-[#2BA5A0] bg-[#161F1C] p-3.5">
            <div className="flex items-center justify-between gap-2">
              <span className="text-[13px] font-bold text-[#2BA5A0]">
                Planner Recommendation ({activeProposal.item.type})
              </span>
              <span className="text-[11px] font-semibold text-[#8E9E99]">
                Nothing saved until confirmed
              </span>
            </div>
            <div className="text-[14px] font-semibold text-[#F2F1EC] mt-1">
              {activeProposal.item.title}
            </div>
            <p className="text-[13px] text-[#F2F1EC]/85 mt-1">{activeProposal.rationale}</p>

            {showCustomSchedule && (
              <div className="mt-3 flex items-center gap-2">
                <input
                  type="time"
                  value={customProposalTime}
                  onChange={(e) => setCustomProposalTime(e.target.value)}
                  className="rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-1.5 text-[13px] text-[#F2F1EC]"
                />
                <button
                  type="button"
                  onClick={() => {
                    onAcceptProposal(activeProposal, customProposalTime);
                    setShowCustomSchedule(false);
                  }}
                  className="rounded-[8px] bg-[#2BA5A0] px-3 py-1.5 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
                >
                  Confirm {customProposalTime}
                </button>
              </div>
            )}

            <div className="mt-3 grid grid-cols-3 gap-2">
              <button
                type="button"
                onClick={() => {
                  onAcceptProposal(activeProposal);
                  setShowCustomSchedule(false);
                }}
                className="rounded-[8px] bg-[#2BA5A0] py-2 px-3 text-[12px] font-semibold text-[#0E1412] hover:opacity-90 cursor-pointer"
              >
                Accept ({activeProposal.suggestedTime || 'Now'})
              </button>
              <button
                type="button"
                onClick={() => setShowCustomSchedule((v) => !v)}
                className="rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-2 px-3 text-[12px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
              >
                Schedule Later
              </button>
              <button
                type="button"
                onClick={() => {
                  onRejectProposal();
                  setShowCustomSchedule(false);
                }}
                className="rounded-[8px] border border-[#24332F] bg-[#0E1412] py-2 px-3 text-[12px] font-semibold text-[#E05349] hover:border-[#E05349] cursor-pointer"
              >
                Drop
              </button>
            </div>
          </div>
        )}

        {isProcessing && (
          <div className="py-1 text-[12px] text-[#8E9E99]">Assistant is thinking...</div>
        )}

        <div ref={feedEndRef} />
      </div>

      {/* Quick Prompt Hints */}
      <div className="mt-2 flex gap-1.5 overflow-x-auto pb-1">
        {[
          'remind me to message Tola at 4 pm',
          '320 pages by friday',
          'remember that client calls happen after 11am',
          'note: update business tier pricing PDF',
        ].map((sample) => (
          <button
            key={sample}
            type="button"
            onClick={() => setTextInput(sample)}
            className="shrink-0 rounded-[8px] border border-[#24332F] bg-[#161F1C] px-2.5 py-1 text-[11px] text-[#8E9E99] hover:text-[#F2F1EC] hover:border-[#2BA5A0] cursor-pointer"
          >
            {sample}
          </button>
        ))}
      </div>

      {/* Centered Pulsing Microphone Button */}
      <div className="py-2 flex items-center justify-center relative">
        {isListening && (
          <span className="absolute w-[68px] h-[68px] rounded-full bg-[#2BA5A0]/25 animate-ping" />
        )}
        <button
          type="button"
          onClick={() => (isListening ? stopRecording() : startRecording())}
          aria-label={isListening ? 'Stop voice capture' : 'Start voice capture'}
          className={`relative z-10 w-[54px] h-[54px] rounded-full flex flex-col items-center justify-center font-bold text-[10px] text-white transition-colors cursor-pointer ${
            isListening ? 'bg-[#E05349]' : 'bg-[#2BA5A0] text-[#0E1412]'
          }`}
        >
          {isListening ? <Square className="w-4 h-4 mb-0.5" /> : <Mic className="w-4 h-4 mb-0.5" />}
          <span>{isListening ? 'STOP' : 'MIC'}</span>
        </button>
      </div>

      {/* Universal Input Bar: "Tell me anything..." */}
      <form onSubmit={handleFormSubmit} className="flex items-center gap-2">
        <input
          type="text"
          value={textInput}
          onChange={(e) => setTextInput(e.target.value)}
          placeholder="Tell me anything..."
          className="flex-1 rounded-[8px] border border-[#24332F] bg-[#161F1C] px-3.5 py-2.5 text-[14px] text-[#F2F1EC] placeholder-[#8E9E99] focus:outline-none focus:border-[#2BA5A0]"
        />
        <button
          type="submit"
          disabled={!textInput.trim() || isProcessing}
          className="rounded-[8px] bg-[#2BA5A0] px-4 py-2.5 text-[13px] font-semibold text-[#0E1412] disabled:opacity-40 hover:opacity-90 flex items-center gap-1.5 cursor-pointer"
        >
          <Send className="w-3.5 h-3.5" />
          Send
        </button>
      </form>
    </div>
  );
};
