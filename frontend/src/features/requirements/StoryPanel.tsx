import { useId, useState, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { memberName, PRIORITY_LABELS, TASK_PRIORITIES, useMembers } from '../tasks/useTasks'
import { useJobRoles } from '../team/useProjectTeam'
import { primaryButton, quietButton } from './TestModeBanner'
import {
  useApproveStory,
  useRejectStory,
  useStoryReviews,
  useUpdateStory,
  type Story,
  type StoryReview,
} from './useRequirements'

interface StoryPanelProps {
  orgId: string
  documentId: string
  story: Story
  canEdit: boolean
  archived: boolean
  onClose(): void
  reloadStory(): Promise<Story | undefined>
}

interface StoryForm {
  title: string
  asA: string
  iWant: string
  soThat: string
  criteria: string
  priority: Story['priority']
  jobRoleId: string
}

const fieldClass =
  'mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary disabled:text-text-secondary'
const rejectButton =
  'rounded-md border border-error px-3 py-2 text-sm text-error-text hover:bg-error-tint disabled:cursor-not-allowed disabled:border-border disabled:text-text-disabled disabled:hover:bg-transparent'

const REVIEW_VERBS: Record<StoryReview['action'], string> = {
  SUBMITTED: 'submitted',
  APPROVED: 'approved',
  REJECTED: 'rejected',
  REOPENED: 'reopened',
}

const timeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function toForm(story: Story): StoryForm {
  return {
    title: story.title,
    asA: story.asA,
    iWant: story.iWant,
    soThat: story.soThat,
    criteria: story.acceptanceCriteria.join('\n'),
    priority: story.priority,
    jobRoleId: story.jobRoleId ?? '',
  }
}

const criteriaList = (text: string) =>
  text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)

function Field({ label, children }: { label: string; children: (id: string) => ReactNode }) {
  const id = useId()
  return (
    <div>
      <label htmlFor={id} className="text-sm font-medium text-text-primary">
        {label}
      </label>
      {children(id)}
    </div>
  )
}

export function StoryPanel({
  orgId,
  documentId,
  story,
  canEdit,
  archived,
  onClose,
  reloadStory,
}: StoryPanelProps) {
  const update = useUpdateStory(orgId, documentId)
  const approve = useApproveStory(orgId, documentId)
  const reject = useRejectStory(orgId, documentId)
  const jobRoles = useJobRoles(orgId)
  const members = useMembers(orgId)
  const reviews = useStoryReviews(orgId, story.id)
  const [baseline, setBaseline] = useState(story)
  const [form, setForm] = useState(() => toForm(story))
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)

  const readOnly = !canEdit || archived || baseline.status === 'CONVERTED'
  const dirty = JSON.stringify(form) !== JSON.stringify(toForm(baseline))
  const busy = update.isPending || approve.isPending || reject.isPending
  const canDecide = baseline.canReview && baseline.status === 'DRAFT' && !archived
  const criteria = criteriaList(form.criteria)
  const complete =
    Boolean(form.title.trim() && form.asA.trim() && form.iWant.trim() && form.soThat.trim()) &&
    criteria.length >= 1 &&
    criteria.length <= 20
  const roleOptions = (jobRoles.data ?? []).filter((role) => role.active || role.id === form.jobRoleId)
  const memberById = new Map(members.data?.map((member) => [member.userId, member]))

  function set<K extends keyof StoryForm>(key: K, value: StoryForm[K]) {
    setForm((current) => ({ ...current, [key]: value }))
  }

  function fail(cause: unknown) {
    if (cause instanceof ApiError) {
      setConflict(cause.status === 409)
      setError(cause.message)
    } else {
      setError('That didn’t save. Check your connection, then try again.')
    }
  }

  function succeed(next: Story) {
    setBaseline(next)
    setForm(toForm(next))
    setComment('')
    setError(null)
    setConflict(false)
  }

  function save() {
    setError(null)
    update.mutate(
      {
        storyId: baseline.id,
        body: {
          title: form.title.trim(),
          asA: form.asA.trim(),
          iWant: form.iWant.trim(),
          soThat: form.soThat.trim(),
          acceptanceCriteria: criteria,
          priority: form.priority,
          jobRoleId: form.jobRoleId || null,
          version: baseline.version,
        },
      },
      { onSuccess: succeed, onError: fail },
    )
  }

  function approveStory() {
    setError(null)
    approve.mutate({ storyId: baseline.id, version: baseline.version }, { onSuccess: succeed, onError: fail })
  }

  function rejectStory() {
    setError(null)
    reject.mutate(
      { storyId: baseline.id, version: baseline.version, comment: comment.trim() },
      { onSuccess: succeed, onError: fail },
    )
  }

  async function reload() {
    const latest = await reloadStory()
    if (latest) succeed(latest)
  }

  function close() {
    if (dirty && !window.confirm('Discard your unsaved changes?')) return
    onClose()
  }

  return (
    <Dialog title={baseline.title} placement="side" onClose={close}>
      <form
        className="mt-4 space-y-4"
        onSubmit={(event) => {
          event.preventDefault()
          if (!readOnly && dirty && complete) save()
        }}
      >
        <Field label="Title">
          {(id) => (
            <input
              id={id}
              value={form.title}
              maxLength={200}
              disabled={readOnly}
              className={fieldClass}
              onChange={(event) => set('title', event.target.value)}
            />
          )}
        </Field>
        <Field label="As a">
          {(id) => (
            <input
              id={id}
              value={form.asA}
              maxLength={300}
              disabled={readOnly}
              className={fieldClass}
              onChange={(event) => set('asA', event.target.value)}
            />
          )}
        </Field>
        <Field label="I want">
          {(id) => (
            <textarea
              id={id}
              rows={2}
              value={form.iWant}
              maxLength={1000}
              disabled={readOnly}
              className={fieldClass}
              onChange={(event) => set('iWant', event.target.value)}
            />
          )}
        </Field>
        <Field label="So that">
          {(id) => (
            <textarea
              id={id}
              rows={2}
              value={form.soThat}
              maxLength={1000}
              disabled={readOnly}
              className={fieldClass}
              onChange={(event) => set('soThat', event.target.value)}
            />
          )}
        </Field>
        <Field label="Acceptance criteria, one per line">
          {(id) => (
            <textarea
              id={id}
              rows={4}
              value={form.criteria}
              disabled={readOnly}
              className={fieldClass}
              onChange={(event) => set('criteria', event.target.value)}
            />
          )}
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Priority">
            {(id) => (
              <select
                id={id}
                value={form.priority}
                disabled={readOnly}
                className={fieldClass}
                onChange={(event) => set('priority', event.target.value as Story['priority'])}
              >
                {TASK_PRIORITIES.map((priority) => (
                  <option key={priority} value={priority}>
                    {PRIORITY_LABELS[priority]}
                  </option>
                ))}
              </select>
            )}
          </Field>
          <Field label="Job role">
            {(id) => (
              <select
                id={id}
                value={form.jobRoleId}
                disabled={readOnly}
                className={fieldClass}
                onChange={(event) => set('jobRoleId', event.target.value)}
              >
                <option value="">No role</option>
                {roleOptions.map((role) => (
                  <option key={role.id} value={role.id}>
                    {role.active ? role.name : `${role.name} (inactive)`}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>

        {baseline.sourceQuotes.length > 0 && (
          <section>
            <h3 className="text-sm font-medium text-text-primary">Source in document</h3>
            <ul className="mt-1 space-y-2">
              {baseline.sourceQuotes.map((source, index) => (
                <li key={`${index}:${source.quote}`} className="border-l-2 border-border pl-3 text-sm leading-6">
                  {source.quote}
                  <span className={`ml-2 text-xs ${source.verified ? 'text-success' : 'text-warning'}`}>
                    {source.verified ? 'Verified' : 'Not found in document'}
                  </span>
                </li>
              ))}
            </ul>
          </section>
        )}

        {error && (
          <div role="alert" className="text-sm text-error-text">
            <p>{error}</p>
            {conflict && (
              <button type="button" className={`${quietButton} mt-1`} onClick={() => void reload()}>
                Reload latest
              </button>
            )}
          </div>
        )}

        {!readOnly && (
          <button type="submit" className={primaryButton} disabled={!dirty || !complete || busy}>
            Save changes
          </button>
        )}
      </form>

      {canDecide && (
        <section className="mt-6 border-t border-border pt-4">
          <h3 className="text-sm font-semibold text-text-primary">Your review</h3>
          {dirty ? (
            <p className="mt-1 text-sm text-warning">Save your changes before deciding.</p>
          ) : (
            !baseline.jobRoleId && (
              <p className="mt-1 text-sm text-warning">Choose a job role and save before approving.</p>
            )
          )}
          <button
            type="button"
            className={`${primaryButton} mt-3`}
            disabled={dirty || !baseline.jobRoleId || busy}
            onClick={approveStory}
          >
            Approve story
          </button>
          <div className="mt-4">
            <Field label="Rejection comment">
              {(id) => (
                <textarea
                  id={id}
                  rows={2}
                  maxLength={2000}
                  value={comment}
                  className={fieldClass}
                  onChange={(event) => setComment(event.target.value)}
                />
              )}
            </Field>
            <button
              type="button"
              className={`${rejectButton} mt-2`}
              disabled={dirty || !comment.trim() || busy}
              onClick={rejectStory}
            >
              Reject story
            </button>
          </div>
        </section>
      )}

      <section className="mt-6 border-t border-border pt-4">
        <h3 className="text-sm font-semibold text-text-primary">Review history</h3>
        {reviews.data && reviews.data.length > 0 ? (
          <ol className="mt-2 space-y-2">
            {reviews.data.map((review) => {
              const member = memberById.get(review.actorId)
              return (
                <li key={review.id} className="text-sm leading-6">
                  <p className="text-text-primary">
                    {member ? memberName(member) : 'Former member'} {REVIEW_VERBS[review.action]}
                    {review.selfApproved ? ' their own story' : ''}
                  </p>
                  {review.comment && <p>{review.comment}</p>}
                  <p className="font-mono text-xs text-text-meta">{timeFormat.format(new Date(review.createdAt))}</p>
                </li>
              )
            })}
          </ol>
        ) : (
          <p className="mt-1 text-sm text-text-meta">{reviews.isPending ? 'Loading' : 'No decisions yet.'}</p>
        )}
      </section>

      <div className="mt-6 flex justify-end">
        <button type="button" className={quietButton} onClick={close}>
          Close
        </button>
      </div>
    </Dialog>
  )
}
