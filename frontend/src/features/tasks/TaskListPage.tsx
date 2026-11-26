import { useState } from 'react'
import { Link, Navigate, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { EmptyState } from '../../components/EmptyState'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { useProject } from '../projects/useProjects'
import { useJobRoles } from '../team/useProjectTeam'
import { TaskFilters } from './TaskFilters'
import { TaskPanel } from './TaskPanel'
import {
  formatDueDate,
  memberName,
  PRIORITY_LABELS,
  STATUS_LABELS,
  TASK_STATUSES,
  useMembers,
  useTasks,
  type Task,
  type TaskStatus,
} from './useTasks'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const primaryButton =
  'rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:cursor-not-allowed disabled:bg-surface-bright disabled:text-text-disabled'
const quietButton =
  'rounded px-2 py-1 text-sm text-text-secondary hover:bg-surface-bright hover:text-text-primary disabled:text-text-disabled disabled:hover:bg-transparent'

// readOnly is fixed on open, so a mid-edit archive can't discard typed text.
type OpenPanel = { kind: 'create' } | { kind: 'task'; task: Task; readOnly: boolean }

function parseStatus(value: string | null) {
  return TASK_STATUSES.includes(value as TaskStatus) ? (value as TaskStatus) : undefined
}

function parseAssignee(value: string | null) {
  return value && UUID_PATTERN.test(value) ? value : undefined
}

function parsePage(value: string | null) {
  return value && /^[1-9]\d{0,8}$/.test(value) ? Number(value) - 1 : 0
}

function withPage(params: URLSearchParams, page: number) {
  const next = new URLSearchParams(params)
  if (page > 0) next.set('page', String(page + 1))
  else next.delete('page')
  return next
}

// Keyed so filters' cached rows and open panels never carry into another project.
export function TaskListPage() {
  const { org } = useCurrentOrg()
  const { projectId = '' } = useParams()
  return <ProjectTasks key={`${org.id}:${projectId}`} projectId={projectId} />
}

function ProjectTasks({ projectId }: { projectId: string }) {
  const { org, can } = useCurrentOrg()
  const [params, setParams] = useSearchParams()
  const [panel, setPanel] = useState<OpenPanel | null>(null)
  const status = parseStatus(params.get('status'))
  const assigneeId = parseAssignee(params.get('assignee'))
  const page = parsePage(params.get('page'))
  const project = useProject(org.id, projectId)
  const members = useMembers(org.id)
  const jobRoles = useJobRoles(org.id)
  const tasks = useTasks(org.id, projectId, { status, assigneeId, page })
  const canEdit = can('EDIT_TASKS')
  const projectsPath = `/orgs/${org.id}`

  function setFilter(name: 'status' | 'assignee', value?: string) {
    setParams((current) => {
      const next = withPage(current, 0)
      if (value) next.set(name, value)
      else next.delete(name)
      return next
    })
  }

  if (project.isPending) return <LoadingState label="Loading project" />

  if (!project.data) {
    const missing =
      project.error instanceof ApiError && [400, 404].includes(project.error.status)
    return missing ? (
      <InlineAlert
        title="Project not found"
        description="It doesn’t exist in this organization."
      >
        <Link to={projectsPath}>Back to projects</Link>
      </InlineAlert>
    ) : (
      <InlineAlert title="Project didn’t load" description="Check your connection, then try again.">
        <button type="button" className={primaryButton} onClick={() => void project.refetch()}>
          Try again
        </button>
      </InlineAlert>
    )
  }

  const archived = project.data.status === 'ARCHIVED'
  const writable = canEdit && !archived
  const data = tasks.data
  const hasFilters = Boolean(status || assigneeId)
  const memberById = new Map(members.data?.map((member) => [member.userId, member]))
  const jobRoleById = new Map(jobRoles.data?.map((role) => [role.id, role.name]))

  if (data && !tasks.isPlaceholderData && data.items.length === 0 && page > 0) {
    return <Navigate replace to={{ search: withPage(params, page - 1).toString() }} />
  }

  let content
  if (tasks.isPending) {
    content = <LoadingState label="Loading tasks" />
  } else if (!data) {
    content = (
      <InlineAlert title="Tasks didn’t load" description="Check your connection, then try again.">
        <button
          type="button"
          disabled={tasks.isFetching}
          className={primaryButton}
          onClick={() => void tasks.refetch()}
        >
          Try again
        </button>
      </InlineAlert>
    )
  } else if (data.items.length === 0) {
    content = hasFilters ? (
      <EmptyState
        title="No matching tasks"
        description="Change or clear the filters to see more tasks."
      />
    ) : (
      <EmptyState
        title="No tasks yet"
        description={
          writable
            ? 'Create a task to start tracking this project’s work.'
            : 'Tasks will appear here once they’re added.'
        }
      />
    )
  } else {
    content = (
      <>
        <div className="overflow-x-auto rounded-lg border border-border bg-surface">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border text-xs text-text-meta">
              <tr>
                <th scope="col" className="px-4 py-2.5 font-medium">Title</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Status</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium lg:table-cell">Role</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium sm:table-cell">Priority</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium md:table-cell">Assignee</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium md:table-cell">Due</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((task) => {
                const assignee = task.assigneeId ? memberById.get(task.assigneeId) : undefined
                return (
                  <tr key={task.id} className="border-b border-hairline last:border-b-0">
                    <td className="max-w-md px-4 py-3">
                      <button
                        type="button"
                        className="text-left font-medium break-words text-text-primary hover:underline"
                        onClick={() => setPanel({ kind: 'task', task, readOnly: !writable })}
                      >
                        {task.title}
                      </button>
                    </td>
                    <td className="px-4 py-3 whitespace-nowrap">{STATUS_LABELS[task.status]}</td>
                    <td className="hidden px-4 py-3 whitespace-nowrap lg:table-cell">
                      {task.responsibleJobRoleId ? (jobRoleById.get(task.responsibleJobRoleId) ?? '…') : 'No role'}
                    </td>
                    <td className="hidden px-4 py-3 whitespace-nowrap sm:table-cell">
                      {PRIORITY_LABELS[task.priority]}
                    </td>
                    <td className="hidden px-4 py-3 whitespace-nowrap md:table-cell">
                      {assignee ? memberName(assignee) : task.assigneeId ? 'Unknown member' : 'Unassigned'}
                    </td>
                    <td className="hidden px-4 py-3 font-mono text-xs whitespace-nowrap text-text-meta md:table-cell">
                      {task.dueDate ? formatDueDate(task.dueDate) : 'None'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        {data.totalItems > data.size && (
          <nav aria-label="Task pages" className="mt-4 flex items-center justify-between text-sm">
            <button
              type="button"
              className={quietButton}
              disabled={page === 0 || tasks.isPlaceholderData}
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
              disabled={(data.page + 1) * data.size >= data.totalItems || tasks.isPlaceholderData}
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
      <div className="flex flex-wrap justify-between gap-3 text-sm">
        <Link to={projectsPath}>Back to projects</Link>
        <span className="flex flex-wrap gap-4">
          <Link to={`${projectsPath}/projects/${projectId}/requirements`}>Requirements</Link>
          <Link to={`${projectsPath}/projects/${projectId}/architecture`}>Architecture</Link>
          <Link to={`${projectsPath}/projects/${projectId}/knowledge`}>Knowledge</Link>
          <Link to={`${projectsPath}/projects/${projectId}/team`}>Project team</Link>
        </span>
      </div>
      <div className="mt-3 mb-4 flex min-h-9 flex-wrap items-center justify-between gap-3">
        <h2 className="min-w-0 text-lg font-semibold break-words text-text-primary">
          {project.data.name}
        </h2>
        {canEdit && (
          <button
            type="button"
            disabled={archived}
            className={primaryButton}
            onClick={() => setPanel({ kind: 'create' })}
          >
            New task
          </button>
        )}
      </div>
      {archived && (
        <p className="mb-4 rounded-md bg-warning-tint px-3 py-2 text-sm text-text-primary">
          This project is archived. Its tasks are read-only.
        </p>
      )}
      <TaskFilters
        status={status}
        assigneeId={assigneeId}
        members={members.data}
        onStatusChange={(value) => setFilter('status', value)}
        onAssigneeChange={(value) => setFilter('assignee', value)}
        onClear={() =>
          setParams((current) => {
            const next = withPage(current, 0)
            next.delete('status')
            next.delete('assignee')
            return next
          })
        }
      />
      {content}
      {panel && (
        <TaskPanel
          key={panel.kind === 'task' ? panel.task.id : 'new'}
          orgId={org.id}
          projectId={projectId}
          task={panel.kind === 'task' ? panel.task : undefined}
          members={members.data}
          readOnly={panel.kind === 'task' && panel.readOnly}
          onClose={() => setPanel(null)}
        />
      )}
    </section>
  )
}
