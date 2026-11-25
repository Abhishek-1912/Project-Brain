import { useId, useState } from 'react'
import { ApiError } from '../../api/client'
import { memberName, useMembers } from '../tasks/useTasks'
import { primaryButton, quietButton } from '../requirements/TestModeBanner'
import {
  downloadArchitectureMarkdown,
  useApproveArchitecture,
  useArchitectureReviews,
  useRejectArchitecture,
  type ArchitectureVersion,
} from './useArchitecture'

const rejectButton =
  'rounded-md border border-error px-3 py-2 text-sm text-error-text hover:bg-error-tint disabled:cursor-not-allowed disabled:border-border disabled:text-text-disabled disabled:hover:bg-transparent'

const timeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

interface ArchitectureReviewProps {
  orgId: string
  version: ArchitectureVersion
  editing: boolean
}

export function ArchitectureReview({ orgId, version, editing }: ArchitectureReviewProps) {
  const commentId = useId()
  const approve = useApproveArchitecture(orgId, version.projectId)
  const reject = useRejectArchitecture(orgId, version.projectId)
  const reviews = useArchitectureReviews(orgId, version.id)
  const members = useMembers(orgId)
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [downloading, setDownloading] = useState(false)
  const busy = approve.isPending || reject.isPending
  const memberById = new Map(members.data?.map((member) => [member.userId, member]))

  function fail(cause: unknown) {
    setError(cause instanceof ApiError ? cause.message : 'That didn’t save. Check your connection, then try again.')
  }

  async function download() {
    setDownloading(true)
    setError(null)
    try {
      await downloadArchitectureMarkdown(orgId, version.id, version.versionNumber)
    }
    catch (cause) {
      fail(cause)
    }
    finally {
      setDownloading(false)
    }
  }

  return (
    <section aria-labelledby="architecture-review-heading" className="mt-10 border-t border-border pt-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 id="architecture-review-heading" className="text-base font-semibold text-text-primary">
          Review
        </h3>
        <button type="button" className={quietButton} disabled={downloading} onClick={() => void download()}>
          Download Markdown
        </button>
      </div>

      {version.canReview && (
        <div className="mt-3 max-w-prose">
          {editing && (
            <p className="text-sm text-warning">Save or cancel your edits before deciding.</p>
          )}
          <button
            type="button"
            className={`${primaryButton} mt-3`}
            disabled={editing || busy}
            onClick={() => {
              setError(null)
              approve.mutate({ versionId: version.id, version: version.version }, { onError: fail })
            }}
          >
            Approve version
          </button>
          <div className="mt-4">
            <label htmlFor={commentId} className="text-sm font-medium text-text-primary">
              Rejection comment
            </label>
            <textarea
              id={commentId}
              rows={2}
              maxLength={2000}
              value={comment}
              className="mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
              onChange={(event) => setComment(event.target.value)}
            />
            <button
              type="button"
              className={`${rejectButton} mt-2`}
              disabled={editing || busy || !comment.trim()}
              onClick={() => {
                setError(null)
                reject.mutate(
                  { versionId: version.id, version: version.version, comment: comment.trim() },
                  { onSuccess: () => setComment(''), onError: fail },
                )
              }}
            >
              Reject version
            </button>
          </div>
        </div>
      )}

      {error && (
        <p role="alert" className="mt-3 text-sm text-error-text">
          {error}
        </p>
      )}

      <h4 className="mt-6 text-sm font-semibold text-text-primary">Review history</h4>
      {reviews.data && reviews.data.length > 0 ? (
        <ol className="mt-2 space-y-2">
          {reviews.data.map((review) => {
            const member = memberById.get(review.actorId)
            const verb = review.action === 'APPROVED' ? 'approved' : 'rejected'
            return (
              <li key={review.id} className="text-sm leading-6">
                <p className="text-text-primary">
                  {member ? memberName(member) : 'Former member'} {verb}
                  {review.selfApproved ? ' their own version' : ''}
                  {review.approvalBasis === 'OWNER_FALLBACK' ? ' as the Owner fallback' : ''}
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
  )
}
