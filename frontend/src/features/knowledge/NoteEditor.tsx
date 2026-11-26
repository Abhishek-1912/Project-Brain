import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { primaryButton, quietButton } from '../requirements/TestModeBanner'
import { useCreateNote, useUpdateNote, type KnowledgeNote } from './useKnowledge'

const MAX_TITLE = 200
const MAX_BODY = 20000

const fieldClass =
  'w-full rounded-md border border-border bg-surface-low px-3 py-2 text-text-primary aria-invalid:border-error'

interface FormErrors {
  title?: string
  body?: string
  form?: string
}

function toFormErrors(error: unknown): FormErrors {
  if (error instanceof ApiError) {
    if (error.status === 409) return { form: 'This note changed. Reload to see the latest version.' }
    if (error.status === 403) return { form: 'Your role can’t change notes.' }
    return { form: error.message }
  }
  return { form: 'The note wasn’t saved. Check your connection, then try again.' }
}

interface NoteEditorProps {
  orgId: string
  projectId: string
  note?: KnowledgeNote
  onClose(): void
}

export function NoteEditor({ orgId, projectId, note, onClose }: NoteEditorProps) {
  const create = useCreateNote(orgId, projectId)
  const update = useUpdateNote(orgId, projectId)
  const [title, setTitle] = useState(note?.title ?? '')
  const [body, setBody] = useState(note?.body ?? '')
  const [errors, setErrors] = useState<FormErrors>({})
  const saving = create.isPending || update.isPending

  async function submit(event: FormEvent) {
    event.preventDefault()
    const fields = { title: title.trim(), body: body.trim() }
    const missing: FormErrors = {}
    if (!fields.title) missing.title = 'Enter a title.'
    if (!fields.body) missing.body = 'Write the note.'
    if (missing.title || missing.body) {
      setErrors(missing)
      return
    }
    setErrors({})
    try {
      if (note) {
        await update.mutateAsync({ noteId: note.id, ...fields, version: note.version })
      } else {
        await create.mutateAsync(fields)
      }
      onClose()
    } catch (error) {
      setErrors(toFormErrors(error))
    }
  }

  return (
    <Dialog title={note ? 'Edit note' : 'New note'} onClose={onClose}>
      <form className="mt-5 flex flex-col gap-4" noValidate onSubmit={submit}>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="note-title" className="text-sm font-medium text-text-primary">
            Title
          </label>
          <input
            id="note-title"
            className={fieldClass}
            autoFocus
            maxLength={MAX_TITLE}
            value={title}
            aria-invalid={errors.title ? true : undefined}
            aria-describedby={errors.title ? 'note-title-error' : undefined}
            onChange={(event) => {
              setTitle(event.target.value)
              setErrors((current) => ({ ...current, title: undefined }))
            }}
          />
          {errors.title && (
            <p id="note-title-error" className="text-sm text-error-text">
              {errors.title}
            </p>
          )}
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="note-body" className="text-sm font-medium text-text-primary">
            Note
          </label>
          <textarea
            id="note-body"
            className={`${fieldClass} min-h-40 resize-y`}
            maxLength={MAX_BODY}
            value={body}
            aria-invalid={errors.body ? true : undefined}
            aria-describedby={errors.body ? 'note-body-error' : 'note-body-help'}
            onChange={(event) => {
              setBody(event.target.value)
              setErrors((current) => ({ ...current, body: undefined }))
            }}
          />
          {errors.body ? (
            <p id="note-body-error" className="text-sm text-error-text">
              {errors.body}
            </p>
          ) : (
            <p id="note-body-help" className="text-xs text-text-meta">
              Plain text, up to {MAX_BODY.toLocaleString()} characters. Notes are searchable by everyone in this
              project.
            </p>
          )}
        </div>
        {errors.form && (
          <p role="alert" className="text-sm text-error-text">
            {errors.form}
          </p>
        )}
        <div className="mt-1 flex justify-end gap-3">
          <button type="button" className={quietButton} onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className={primaryButton} disabled={saving}>
            {saving ? 'Saving…' : 'Save note'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
