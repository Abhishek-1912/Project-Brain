import { useState } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { EmptyState } from '../../components/EmptyState'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { primaryButton, quietButton } from '../requirements/TestModeBanner'
import { NoteEditor } from './NoteEditor'
import { useDeleteNote, useKnowledgeNotes, type KnowledgeNote } from './useKnowledge'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

type Panel = { kind: 'create' } | { kind: 'edit'; note: KnowledgeNote } | { kind: 'delete'; note: KnowledgeNote }

interface NoteListProps {
  orgId: string
  projectId: string
  canWrite: boolean
}

export function NoteList({ orgId, projectId, canWrite }: NoteListProps) {
  const notes = useKnowledgeNotes(orgId, projectId)
  const [panel, setPanel] = useState<Panel | null>(null)
  const close = () => setPanel(null)

  const newNote = canWrite && (
    <button type="button" className={primaryButton} onClick={() => setPanel({ kind: 'create' })}>
      New note
    </button>
  )

  return (
    <section aria-labelledby="notes-heading">
      <div className="mb-2 flex min-h-9 flex-wrap items-center justify-between gap-3">
        <h3 id="notes-heading" className="text-base font-semibold text-text-primary">
          Notes
        </h3>
        {notes.data && notes.data.items.length > 0 && newNote}
      </div>
      {notes.isPending ? (
        <LoadingState label="Loading notes" />
      ) : !notes.data ? (
        <InlineAlert title="Notes didn’t load" description="Check your connection, then try again." />
      ) : notes.data.items.length === 0 ? (
        <EmptyState
          title="No notes yet"
          description={
            canWrite
              ? 'Write down decisions, conventions, and context the documents don’t cover. Notes become searchable.'
              : 'Owners and admins can add notes with decisions and context for this project.'
          }
          action={newNote || undefined}
        />
      ) : (
        <div className="divide-y divide-border border-y border-border">
          {notes.data.items.map((note) => (
            <NoteItem
              key={note.id}
              note={note}
              canWrite={canWrite}
              onEdit={() => setPanel({ kind: 'edit', note })}
              onDelete={() => setPanel({ kind: 'delete', note })}
            />
          ))}
        </div>
      )}
      {panel?.kind === 'create' && <NoteEditor orgId={orgId} projectId={projectId} onClose={close} />}
      {panel?.kind === 'edit' && <NoteEditor orgId={orgId} projectId={projectId} note={panel.note} onClose={close} />}
      {panel?.kind === 'delete' && (
        <DeleteNote orgId={orgId} projectId={projectId} note={panel.note} onClose={close} />
      )}
    </section>
  )
}

interface NoteItemProps {
  note: KnowledgeNote
  canWrite: boolean
  onEdit(): void
  onDelete(): void
}

function NoteItem({ note, canWrite, onEdit, onDelete }: NoteItemProps) {
  const headingId = `note-${note.id}-title`
  return (
    <article id={`note-${note.id}`} aria-labelledby={headingId} className="py-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h4 id={headingId} className="min-w-0 font-medium break-words text-text-primary">
          {note.title}
        </h4>
        <span className="flex items-center gap-1">
          <span className="font-mono text-xs text-text-meta">
            Updated {dateFormat.format(new Date(note.updatedAt))}
          </span>
          {canWrite && (
            <>
              <button type="button" className={quietButton} onClick={onEdit}>
                Edit
              </button>
              <button type="button" className={quietButton} onClick={onDelete}>
                Delete
              </button>
            </>
          )}
        </span>
      </div>
      <p className="mt-1 max-w-prose text-sm leading-6 whitespace-pre-wrap break-words">{note.body}</p>
    </article>
  )
}

function DeleteNote({ orgId, projectId, note, onClose }: { orgId: string; projectId: string; note: KnowledgeNote; onClose(): void }) {
  const remove = useDeleteNote(orgId, projectId)
  const failure = remove.error
    ? remove.error instanceof ApiError
      ? remove.error.message
      : 'The note wasn’t deleted. Check your connection, then try again.'
    : null

  return (
    <Dialog title="Delete note?" onClose={onClose}>
      <p className="mt-3 text-sm leading-6">
        “{note.title}” will be deleted and removed from search. This can’t be undone.
      </p>
      {failure && (
        <p role="alert" className="mt-3 text-sm text-error-text">
          {failure}
        </p>
      )}
      <div className="mt-5 flex justify-end gap-3">
        <button type="button" className={quietButton} onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="rounded-md bg-error px-3 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
          disabled={remove.isPending}
          onClick={() => remove.mutate(note.id, { onSuccess: onClose })}
        >
          Delete note
        </button>
      </div>
    </Dialog>
  )
}
