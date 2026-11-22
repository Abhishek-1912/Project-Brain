import {
  memberName,
  STATUS_LABELS,
  TASK_STATUSES,
  type Member,
  type TaskStatus,
} from './useTasks'

const selectClass =
  'rounded-md border border-border bg-surface px-2 py-1.5 text-sm text-text-primary'

interface TaskFiltersProps {
  status?: TaskStatus
  assigneeId?: string
  members?: Member[]
  onStatusChange(status?: TaskStatus): void
  onAssigneeChange(assigneeId?: string): void
  onClear(): void
}

export function TaskFilters({
  status,
  assigneeId,
  members,
  onStatusChange,
  onAssigneeChange,
  onClear,
}: TaskFiltersProps) {
  const unknownAssignee =
    assigneeId && !members?.some((member) => member.userId === assigneeId)

  return (
    <div className="mb-4 flex flex-wrap items-end gap-3">
      <label className="flex flex-col gap-1 text-xs text-text-secondary">
        Filter by status
        <select
          className={selectClass}
          value={status ?? ''}
          onChange={(event) =>
            onStatusChange((event.target.value || undefined) as TaskStatus | undefined)
          }
        >
          <option value="">All statuses</option>
          {TASK_STATUSES.map((item) => (
            <option key={item} value={item}>
              {STATUS_LABELS[item]}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-xs text-text-secondary">
        Filter by assignee
        <select
          className={selectClass}
          value={assigneeId ?? ''}
          onChange={(event) => onAssigneeChange(event.target.value || undefined)}
        >
          <option value="">Everyone</option>
          {unknownAssignee && <option value={assigneeId}>Unknown member</option>}
          {members?.map((member) => (
            <option key={member.userId} value={member.userId}>
              {memberName(member)}
            </option>
          ))}
        </select>
      </label>
      {(status || assigneeId) && (
        <button
          type="button"
          className="rounded px-2 py-1.5 text-sm text-text-secondary hover:bg-surface-bright hover:text-text-primary"
          onClick={onClear}
        >
          Clear filters
        </button>
      )}
    </div>
  )
}
