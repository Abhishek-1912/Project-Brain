import { useState } from 'react'
import { ApiError } from '../../api/client'
import type { Task } from '../tasks/useTasks'
import {
  QUEUE_LOOKUP_SIZE,
  reviewReason,
  useMyReviews,
  useReviewAction,
  useTaskReviews,
  type ReviewAction,
  type TaskReview,
} from './useReviews'

const primaryButton =
  'rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60'
const quietButton =
  'rounded-md border border-border px-3 py-2 text-sm text-text-secondary hover:text-text-primary disabled:opacity-60'
const rejectButton =
  'rounded-md border border-error px-3 py-2 text-sm text-error-text hover:bg-error-tint disabled:opacity-60'

const ACTION_VERBS: Record<TaskReview['action'], string> = {
  SUBMITTED: 'submitted',
  APPROVED: 'approved',
  REJECTED: 'rejected',
  WITHDRAWN: 'withdrew',
  REOPENED: 'reopened',
}

const timeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

interface TaskReviewSectionProps {
  orgId: string
  task: Task
  dirty?: boolean
  readOnly?: boolean
  onTaskChanged?(task: Task): void
  onReload?(): Promise<void>
}

export function TaskReviewSection({
  orgId,
  task,
  dirty = false,
  readOnly = false,
  onTaskChanged,
  onReload,
}: TaskReviewSectionProps) {
  const history = useTaskReviews(orgId, task.id)
  const inReview = task.status === 'IN_REVIEW'
  const queue = useMyReviews(orgId, 0, QUEUE_LOOKUP_SIZE, !readOnly && inReview)
  const reviewAction = useReviewAction(orgId, task.projectId)
  const [comment, setComment] = useState('')
  const [commentError, setCommentError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [stale, setStale] = useState(false)
  const queueItem = inReview ? queue.data?.items.find((item) => item.task.id === task.id) : undefined
  const busy = reviewAction.isPending || dirty

  async function run(action: ReviewAction) {
    const trimmed = comment.trim()
    if (action === 'reject' && !trimmed) {
      setCommentError('Enter what needs to change.')
      return
    }
    setCommentError(null)
    setError(null)
    setStale(false)
    try {
      const updated = await reviewAction.mutateAsync({
        taskId: task.id,
        action,
        version: task.version,
        comment: action === 'approve' || action === 'reject' ? trimmed : undefined,
      })
      setComment('')
      onTaskChanged?.(updated)
    } catch (caught) {
      const detail = caught instanceof ApiError ? caught.problem.detail : undefined
      setStale(caught instanceof ApiError && caught.status === 409 && /changed by someone else/i.test(detail ?? ''))
      setError(detail ?? 'The review action didn’t finish. Check your connection and try again.')
    }
  }

  async function reload() {
    await onReload?.()
    setError(null)
    setStale(false)
  }

  return (
    <section aria-label="Review" className="mt-6 border-t border-border pt-5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-base font-semibold text-text-primary">Review</h3>
        {queueItem && (
          <span className="rounded bg-primary-dim px-2 py-0.5 text-xs text-text-primary">
            {reviewReason(queueItem.approvalBasis, queueItem.selfApproval)}
          </span>
        )}
      </div>

      {!readOnly && (
        <>
          {dirty && <p className="mt-2 text-sm text-text-meta">Save your changes before using review actions.</p>}

          {queueItem && (
            <div className="mt-3 flex flex-col gap-1.5">
              <label htmlFor="review-comment" className="text-sm font-medium text-text-primary">
                Review comment
              </label>
              <textarea
                id="review-comment"
                maxLength={2000}
                className="min-h-20 w-full resize-y rounded-md border border-border bg-surface-low px-3 py-2 text-sm text-text-primary aria-invalid:border-error"
                placeholder="Required when rejecting"
                value={comment}
                aria-invalid={commentError ? true : undefined}
                aria-describedby={commentError ? 'review-comment-error' : undefined}
                onChange={(event) => {
                  setComment(event.target.value)
                  setCommentError(null)
                }}
              />
              {commentError && (
                <p id="review-comment-error" className="text-sm text-error-text">
                  {commentError}
                </p>
              )}
            </div>
          )}

          {error && (
            <div role="alert" className="mt-3 rounded-md bg-error-tint p-3 text-sm text-error-text">
              <p>{error}</p>
              {stale && (
                <button
                  type="button"
                  className="mt-2 font-medium text-text-primary underline"
                  onClick={() => void reload()}
                >
                  Reload latest
                </button>
              )}
            </div>
          )}

          <div className="mt-3 flex flex-wrap justify-end gap-2">
            {['BACKLOG', 'TODO', 'IN_PROGRESS'].includes(task.status) && (
              <button type="button" disabled={busy} className={primaryButton} onClick={() => void run('submit')}>
                Submit for review
              </button>
            )}
            {inReview && (
              <button type="button" disabled={busy} className={quietButton} onClick={() => void run('withdraw')}>
                Withdraw
              </button>
            )}
            {queueItem && (
              <>
                <button type="button" disabled={busy} className={rejectButton} onClick={() => void run('reject')}>
                  Reject
                </button>
                <button type="button" disabled={busy} className={primaryButton} onClick={() => void run('approve')}>
                  Approve
                </button>
              </>
            )}
            {task.status === 'DONE' && (
              <button type="button" disabled={busy} className={quietButton} onClick={() => void run('reopen')}>
                Reopen
              </button>
            )}
          </div>
        </>
      )}

      {history.data && history.data.length > 0 && (
        <ol className="mt-4 flex flex-col gap-3 border-l-2 border-surface-bright pl-3">
          {history.data.map((entry) => (
            <li key={entry.id} className="text-sm">
              <p className="text-text-primary">
                {entry.actorName ?? 'Former member'} {ACTION_VERBS[entry.action]}
              </p>
              <p className="font-mono text-xs text-text-meta">
                <time dateTime={entry.createdAt}>{timeFormat.format(new Date(entry.createdAt))}</time>
              </p>
              {(entry.selfApproved || entry.approvalBasis === 'OWNER_FALLBACK') && (
                <p className="mt-1 text-xs text-warning">
                  {entry.selfApproved ? 'Self-approved' : 'Owner fallback'}
                </p>
              )}
              {entry.comment && <p className="mt-1 break-words whitespace-pre-wrap">{entry.comment}</p>}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
