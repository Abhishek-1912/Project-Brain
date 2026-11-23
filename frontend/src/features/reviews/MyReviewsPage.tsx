import { useState } from 'react'
import { EmptyState } from '../../components/EmptyState'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { TaskPanel } from '../tasks/TaskPanel'
import { useMembers, type Task } from '../tasks/useTasks'
import { reviewReason, useMyReviews } from './useReviews'

const quietButton =
  'rounded px-2 py-1 text-sm text-text-secondary hover:bg-surface-bright hover:text-text-primary disabled:text-text-disabled disabled:hover:bg-transparent'
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function MyReviewsPage() {
  const { org } = useCurrentOrg()
  return <OrganizationReviews key={org.id} orgId={org.id} />
}

function OrganizationReviews({ orgId }: { orgId: string }) {
  const [page, setPage] = useState(0)
  const [openTask, setOpenTask] = useState<Task | null>(null)
  const reviews = useMyReviews(orgId, page)
  const members = useMembers(orgId)
  const data = reviews.data

  let content
  if (reviews.isPending) {
    content = <LoadingState label="Loading reviews" />
  } else if (!data) {
    content = (
      <InlineAlert title="Reviews didn’t load" description="Check your connection, then try again.">
        <button
          type="button"
          disabled={reviews.isFetching}
          className="rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60"
          onClick={() => void reviews.refetch()}
        >
          Try again
        </button>
      </InlineAlert>
    )
  } else if (data.items.length === 0 && page === 0) {
    content = (
      <EmptyState
        title="Nothing to review"
        description="Tasks you can approve or reject will appear here."
      />
    )
  } else {
    content = (
      <>
        <div className="overflow-x-auto rounded-lg border border-border bg-surface">
          <table className="w-full min-w-2xl text-left text-sm">
            <thead className="border-b border-border text-xs text-text-meta">
              <tr>
                <th scope="col" className="px-4 py-2.5 font-medium">Task</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Project</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Role</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Submitted</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Why you</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((item) => {
                const reason = reviewReason(item.approvalBasis, item.selfApproval)
                return (
                  <tr key={item.task.id} className="border-b border-hairline last:border-b-0">
                    <td className="max-w-xs px-4 py-3">
                      <button
                        type="button"
                        className="text-left font-medium break-words text-text-primary hover:underline"
                        onClick={() => setOpenTask(item.task)}
                      >
                        {item.task.title}
                      </button>
                    </td>
                    <td className="max-w-xs px-4 py-3 break-words">{item.projectName}</td>
                    <td className="px-4 py-3 whitespace-nowrap">{item.jobRoleName}</td>
                    <td className="px-4 py-3 whitespace-nowrap">
                      <span className="block">{item.submittedByName ?? 'Former member'}</span>
                      <time dateTime={item.submittedAt} className="font-mono text-xs text-text-meta">
                        {dateFormat.format(new Date(item.submittedAt))}
                      </time>
                    </td>
                    <td className="px-4 py-3 whitespace-nowrap">
                      <span
                        className={`rounded px-2 py-0.5 text-xs ${
                          reason === 'Role holder'
                            ? 'bg-success-tint text-success'
                            : 'bg-warning-tint text-warning'
                        }`}
                      >
                        {reason}
                      </span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        {data.totalItems > data.size && (
          <nav aria-label="Review pages" className="mt-4 flex items-center justify-between text-sm">
            <button
              type="button"
              className={quietButton}
              disabled={page === 0 || reviews.isPlaceholderData}
              onClick={() => setPage((current) => current - 1)}
            >
              Previous page
            </button>
            <span className="font-mono text-xs text-text-meta">
              Page {data.page + 1} of {Math.ceil(data.totalItems / data.size)}
            </span>
            <button
              type="button"
              className={quietButton}
              disabled={(data.page + 1) * data.size >= data.totalItems || reviews.isPlaceholderData}
              onClick={() => setPage((current) => current + 1)}
            >
              Next page
            </button>
          </nav>
        )}
      </>
    )
  }

  return (
    <section>
      <h2 className="text-lg font-semibold text-text-primary">Waiting for my review</h2>
      <p className="mt-1 mb-4 text-sm">Tasks you can approve or reject, oldest first.</p>
      {content}
      {openTask && (
        <TaskPanel
          key={openTask.id}
          orgId={orgId}
          projectId={openTask.projectId}
          task={openTask}
          members={members.data}
          readOnly={false}
          onClose={() => setOpenTask(null)}
        />
      )}
    </section>
  )
}
