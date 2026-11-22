import { Navigate, useSearchParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { EmptyState } from '../../components/EmptyState'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { memberName, useMembers, type Member } from '../tasks/useTasks'
import { useAuditEvents, type AuditEvent } from './useAuditEvents'

const quietButton =
  'rounded px-2 py-1 text-sm text-text-secondary hover:bg-surface-bright hover:text-text-primary disabled:text-text-disabled disabled:hover:bg-transparent'

const timeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
})

function parsePage(value: string | null) {
  return value && /^[1-9]\d{0,8}$/.test(value) ? Number(value) - 1 : 0
}

function withPage(params: URLSearchParams, page: number) {
  const next = new URLSearchParams(params)
  if (page > 0) next.set('page', String(page + 1))
  else next.delete('page')
  return next
}

function actionLabel(action: string) {
  const words = action.toLowerCase().replaceAll('_', ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

const text = (value: unknown) => (typeof value === 'string' && value ? value : undefined)

function eventSubject({ metadata }: AuditEvent) {
  return text(metadata.name) ?? text(metadata.title)
}

function eventChange({ metadata }: AuditEvent) {
  const previousName = text(metadata.previousName)
  if (previousName && previousName !== metadata.name) {
    return `Renamed from ${previousName}`
  }
  if (Array.isArray(metadata.changedFields)) {
    const fields = metadata.changedFields.filter((field) => typeof field === 'string')
    if (fields.length > 0) return `Changed ${fields.join(', ')}`
  }
  return undefined
}

function NoAccess() {
  return (
    <section>
      <h2 className="mb-4 text-lg font-semibold text-text-primary">Audit log</h2>
      <InlineAlert
        title="You can’t view the audit log"
        description="Ask an owner or admin of this organization for access."
      />
    </section>
  )
}

function actorName(event: AuditEvent, members?: Member[]) {
  if (!members) return '…'
  const actor = members.find((member) => member.userId === event.actorId)
  return actor ? memberName(actor) : 'Unknown member'
}

// Keyed so one organization's events never show while another's load.
export function AuditLogPage() {
  const { org, can } = useCurrentOrg()
  if (!can('VIEW_AUDIT')) return <NoAccess />
  return <OrganizationAudit key={org.id} orgId={org.id} />
}

function OrganizationAudit({ orgId }: { orgId: string }) {
  const [params, setParams] = useSearchParams()
  const page = parsePage(params.get('page'))
  const events = useAuditEvents(orgId, page)
  const members = useMembers(orgId)
  const data = events.data

  if (events.error instanceof ApiError && events.error.status === 403) {
    return <NoAccess />
  }

  if (data && !events.isPlaceholderData && data.items.length === 0 && page > 0) {
    const lastPage = Math.max(Math.ceil(data.totalItems / data.size) - 1, 0)
    return <Navigate replace to={{ search: withPage(params, lastPage).toString() }} />
  }

  let content
  if (events.isPending) {
    content = <LoadingState label="Loading audit log" />
  } else if (!data) {
    content = (
      <InlineAlert title="Audit log didn’t load" description="Check your connection, then try again.">
        <button
          type="button"
          disabled={events.isFetching}
          className="rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60"
          onClick={() => void events.refetch()}
        >
          Try again
        </button>
      </InlineAlert>
    )
  } else if (data.items.length === 0) {
    content = (
      <EmptyState
        title="No activity yet"
        description="Changes to this organization and its projects will be listed here."
      />
    )
  } else {
    content = (
      <>
        <div className="overflow-x-auto rounded-lg border border-border bg-surface">
          <table className="w-full min-w-xl text-left text-sm">
            <thead className="border-b border-border text-xs text-text-meta">
              <tr>
                <th scope="col" className="px-4 py-2.5 font-medium">Event</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Details</th>
                <th scope="col" className="px-4 py-2.5 font-medium">By</th>
                <th scope="col" className="px-4 py-2.5 font-medium">When</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((event) => {
                const subject = eventSubject(event)
                const change = eventChange(event)
                return (
                  <tr key={event.id} className="border-b border-hairline last:border-b-0">
                    <td className="px-4 py-3 align-top font-medium whitespace-nowrap text-text-primary">
                      {actionLabel(event.action)}
                    </td>
                    <td className="max-w-sm px-4 py-3 align-top">
                      {subject && <p className="break-words text-text-primary">{subject}</p>}
                      {change && <p className="mt-0.5 text-text-meta">{change}</p>}
                    </td>
                    <td className="px-4 py-3 align-top whitespace-nowrap">
                      {actorName(event, members.data)}
                    </td>
                    <td className="px-4 py-3 align-top font-mono text-xs whitespace-nowrap text-text-meta">
                      <time dateTime={event.createdAt}>
                        {timeFormat.format(new Date(event.createdAt))}
                      </time>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        {data.totalItems > data.size && (
          <nav aria-label="Audit log pages" className="mt-4 flex items-center justify-between text-sm">
            <button
              type="button"
              className={quietButton}
              disabled={page === 0 || events.isPlaceholderData}
              onClick={() => setParams((current) => withPage(current, page - 1))}
            >
              Previous page
            </button>
            <span className="font-mono text-xs text-text-meta">
              Page {data.page + 1} of {Math.ceil(data.totalItems / data.size)}
            </span>
            <button
              type="button"
              className={quietButton}
              disabled={(data.page + 1) * data.size >= data.totalItems || events.isPlaceholderData}
              onClick={() => setParams((current) => withPage(current, page + 1))}
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
      <h2 className="mb-4 text-lg font-semibold text-text-primary">Audit log</h2>
      {content}
    </section>
  )
}
