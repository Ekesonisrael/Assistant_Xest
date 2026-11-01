import React, { useState } from 'react';
import { Plus, Search, Volume2, Bell, Trash2 } from 'lucide-react';
import { Item } from '../types';

interface NotesViewProps {
  notes: Item[];
  onCreateNote: (title: string, details: string) => void;
  onReadAloud: (note: Item) => void;
  onAttachReminder: (note: Item, timeString: string) => void;
  onDeleteNote: (note: Item) => void;
}

export const NotesView: React.FC<NotesViewProps> = ({
  notes,
  onCreateNote,
  onReadAloud,
  onAttachReminder,
  onDeleteNote,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [showAddModal, setShowAddModal] = useState(false);
  const [titleInput, setTitleInput] = useState('');
  const [detailsInput, setDetailsInput] = useState('');

  const [reminderTarget, setReminderTarget] = useState<Item | null>(null);
  const [reminderTimeInput, setReminderTimeInput] = useState('16:00');

  const filtered = notes.filter((n) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return n.title.toLowerCase().includes(q) || n.details.toLowerCase().includes(q);
  });

  const handleSaveNewNote = (e: React.FormEvent) => {
    e.preventDefault();
    if (!titleInput.trim()) return;
    onCreateNote(titleInput.trim(), detailsInput.trim());
    setTitleInput('');
    setDetailsInput('');
    setShowAddModal(false);
  };

  return (
    <div className="max-w-[680px] mx-auto px-4 pt-4 pb-24">
      <div className="flex items-center justify-between gap-3 mb-3">
        <div>
          <h2 className="text-[20px] font-bold text-[#F2F1EC]">Notes & Thoughts</h2>
          <p className="text-[12px] text-[#8E9E99]">
            Searchable, spoken aloud, with reminder attachments.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setShowAddModal(true)}
          className="rounded-[8px] bg-[#2BA5A0] px-3 py-2 text-[12px] font-semibold text-[#0E1412] flex items-center gap-1.5 hover:opacity-90 cursor-pointer"
        >
          <Plus className="w-3.5 h-3.5" />
          New Note
        </button>
      </div>

      {/* Search Bar */}
      <div className="relative mb-4">
        <Search className="w-4 h-4 text-[#8E9E99] absolute left-3 top-1/2 -translate-y-1/2" />
        <input
          type="text"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="Search notes..."
          className="w-full rounded-[8px] border border-[#24332F] bg-[#161F1C] pl-9 pr-3 py-2.5 text-[13px] text-[#F2F1EC] placeholder-[#8E9E99] focus:outline-none focus:border-[#2BA5A0]"
        />
      </div>

      {filtered.length === 0 ? (
        <div className="rounded-[8px] border border-[#24332F] bg-[#161F1C] py-12 px-4 text-center text-[13px] text-[#8E9E99]">
          {searchQuery.trim()
            ? `No notes matching '${searchQuery}'.`
            : "No notes saved yet. Type 'note: ...' on Home or tap '+ New Note'."}
        </div>
      ) : (
        <div className="space-y-2.5">
          {filtered.map((note) => {
            const createdStr = new Date(note.createdAt).toLocaleString('en-GB', {
              day: '2-digit',
              month: 'short',
              hour: '2-digit',
              minute: '2-digit',
            });
            const reminderStr = note.dueAt
              ? new Date(note.dueAt).toLocaleString('en-GB', {
                  day: '2-digit',
                  month: 'short',
                  hour: '2-digit',
                  minute: '2-digit',
                })
              : null;

            return (
              <div
                key={note.id}
                className="rounded-[8px] border border-[#24332F] bg-[#161F1C] p-3.5"
              >
                <div className="flex items-start justify-between gap-2">
                  <h3 className="text-[14px] font-semibold text-[#F2F1EC] flex-1">{note.title}</h3>
                  <span className="text-[11px] text-[#8E9E99] shrink-0">{createdStr}</span>
                </div>

                {note.details && (
                  <p className="text-[13px] text-[#F2F1EC]/85 mt-1.5 whitespace-pre-line">
                    {note.details}
                  </p>
                )}

                {reminderStr && (
                  <div className="mt-2 inline-block rounded-[8px] bg-[#2BA5A0]/15 px-2.5 py-1 text-[11px] font-semibold text-[#2BA5A0]">
                    Reminder: {reminderStr}
                  </div>
                )}

                <div className="mt-3 flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => onReadAloud(note)}
                    className="flex-1 rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-1.5 px-2.5 text-[11px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] flex items-center justify-center gap-1.5 cursor-pointer"
                  >
                    <Volume2 className="w-3.5 h-3.5 text-[#2BA5A0]" />
                    Read aloud
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setReminderTarget(note);
                      if (note.dueAt) {
                        const d = new Date(note.dueAt);
                        setReminderTimeInput(
                          `${String(d.getHours()).padStart(2, '0')}:${String(
                            d.getMinutes()
                          ).padStart(2, '0')}`
                        );
                      } else {
                        setReminderTimeInput('16:00');
                      }
                    }}
                    className="flex-1 rounded-[8px] border border-[#24332F] bg-[#1E2A27] py-1.5 px-2.5 text-[11px] font-medium text-[#F2F1EC] hover:border-[#2BA5A0] flex items-center justify-center gap-1.5 cursor-pointer"
                  >
                    <Bell className="w-3.5 h-3.5 text-[#2BA5A0]" />
                    {reminderStr ? 'Edit reminder' : '+ Reminder'}
                  </button>
                  <button
                    type="button"
                    onClick={() => onDeleteNote(note)}
                    className="rounded-[8px] border border-[#24332F] bg-[#0E1412] py-1.5 px-2.5 text-[11px] font-medium text-[#E05349] hover:border-[#E05349] flex items-center gap-1 cursor-pointer"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    Delete
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* New Note Modal */}
      {showAddModal && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
          <form
            onSubmit={handleSaveNewNote}
            className="w-full max-w-[440px] rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4 space-y-3"
          >
            <h3 className="text-[16px] font-bold text-[#F2F1EC]">New Note</h3>
            <div>
              <label className="block text-[12px] text-[#8E9E99] mb-1">Title</label>
              <input
                type="text"
                required
                value={titleInput}
                onChange={(e) => setTitleInput(e.target.value)}
                placeholder="Note title"
                className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC] focus:outline-none focus:border-[#2BA5A0]"
              />
            </div>
            <div>
              <label className="block text-[12px] text-[#8E9E99] mb-1">Details (optional)</label>
              <textarea
                rows={4}
                value={detailsInput}
                onChange={(e) => setDetailsInput(e.target.value)}
                placeholder="Additional details..."
                className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC] focus:outline-none focus:border-[#2BA5A0]"
              />
            </div>
            <div className="flex justify-end gap-2 pt-1">
              <button
                type="button"
                onClick={() => setShowAddModal(false)}
                className="rounded-[8px] border border-[#24332F] px-3.5 py-2 text-[12px] text-[#8E9E99] hover:text-[#F2F1EC] cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="submit"
                className="rounded-[8px] bg-[#2BA5A0] px-4 py-2 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
              >
                Save
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Attach Reminder Modal */}
      {reminderTarget && (
        <div className="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
          <div className="w-full max-w-[400px] rounded-[8px] border border-[#24332F] bg-[#161F1C] p-4 space-y-3">
            <h3 className="text-[16px] font-bold text-[#F2F1EC]">Attach Reminder to Note</h3>
            <p className="text-[12px] text-[#8E9E99]">
              Set a time for the assistant to remind and read &ldquo;{reminderTarget.title}&rdquo;
              aloud:
            </p>
            <input
              type="time"
              value={reminderTimeInput}
              onChange={(e) => setReminderTimeInput(e.target.value)}
              className="w-full rounded-[8px] border border-[#24332F] bg-[#0E1412] px-3 py-2 text-[13px] text-[#F2F1EC]"
            />
            <div className="flex justify-end gap-2 pt-1">
              <button
                type="button"
                onClick={() => setReminderTarget(null)}
                className="rounded-[8px] border border-[#24332F] px-3.5 py-2 text-[12px] text-[#8E9E99] cursor-pointer"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => {
                  onAttachReminder(reminderTarget, reminderTimeInput);
                  setReminderTarget(null);
                }}
                className="rounded-[8px] bg-[#2BA5A0] px-4 py-2 text-[12px] font-semibold text-[#0E1412] cursor-pointer"
              >
                Set Reminder
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
