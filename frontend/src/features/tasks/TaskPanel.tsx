import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import {
  formatDueDate,
  memberName,
  PRIORITY_LABELS,
  STATUS_LABELS,
  TASK_PRIORITIES,
  TASK_STATUSES,
  useCreateTask,
  useDeleteTask,
  useReloadTask,
  useUpdateTask,
  type Member,
  type Task,
  type TaskPatch,
  type TaskPriority,
  type TaskStatus,
} from './useTasks'

const fieldClass =
  'w-full rounded-md border border-border bg-surface-low px-3 py-2 text-sm text-text-primary aria-invalid:border-error disabled:text-text-disabled'
const labelClass = 'text-sm font-medium text-text-primary'
const cancelButton =
  'rounded-md px-3 py-2 text-sm text-text-secondary hover:text-text-primary'
const primaryButton =
  'rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60'
const dangerButton =
  'rounded-md bg-error px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-60'

interface TaskForm {
  title: string
  description: string
  status: TaskStatus
  priority: TaskPriority
  assigneeId: string
  dueDate: string
}

interface PanelErrors {
  title?: string
  form?: string
  stale?: boolean
}

function toForm(task?: Task): TaskForm {
  return {
    title: task?.title ?? '',
    description: task?.description ?? '',
    status: task?.status ?? 'BACKLOG',
    priority: task?.priority ?? 'MEDIUM',
    assigneeId: task?.assigneeId ?? '',
    dueDate: task?.dueDate ?? '',
  }
}

function toValues(form: TaskForm) {
  return {
    title: form.title.trim(),
    description: form.description.trim() || null,
    status: form.status,
    priority: form.priority,
    assigneeId: form.assigneeId || null,
    dueDate: form.dueDate || null,
  }
}

// Sends only changed fields, so a save can't overwrite values it didn't touch.
function toPatch(task: Task, form: TaskForm): TaskPatch | null {
  const values = toValues(form)
  const patch: TaskPatch = { version: task.version }
  if (values.title !== task.title) patch.title = values.title
  if (values.description !== task.description) patch.description = values.description
  if (values.status !== task.status) patch.status = values.status
  if (values.priority !== task.priority) patch.priority = values.priority
  if (values.assigneeId !== task.assigneeId) patch.assigneeId = values.assigneeId
  if (values.dueDate !== task.dueDate) patch.dueDate = values.dueDate
  return Object.keys(patch).length > 1 ? patch : null
}

function toPanelErrors(error: unknown): PanelErrors {
  if (error instanceof ApiError) {
    const detail = error.problem.detail
    if (error.status === 409 && /changed by someone else/i.test(detail ?? '')) {
      return { form: detail, stale: true }
    }
    if (error.status === 404) return { form: 'This task no longer exists.' }
    if (error.status === 403) return { form: 'Your role can’t change tasks.' }
    const title = error.problem.errors?.title?.[0]
    if (title) return { title }
    if (detail) return { form: detail }
  }
  return { form: 'The task wasn’t saved. Check your connection and try again.' }
}

function assigneeLabel(assigneeId: string | null, members?: Member[]) {
  if (!assigneeId) return 'Unassigned'
  const member = members?.find((item) => item.userId === assigneeId)
  return member ? memberName(member) : 'Unknown member'
}

function TaskDetails({
  task,
  members,
  onClose,
}: {
  task: Task
  members?: Member[]
  onClose(): void
}) {
  const rows = [
    ['Status', STATUS_LABELS[task.status]],
    ['Priority', PRIORITY_LABELS[task.priority]],
    ['Assignee', assigneeLabel(task.assigneeId, members)],
    ['Due date', task.dueDate ? formatDueDate(task.dueDate) : 'No due date'],
  ]

  return (
    <Dialog title="Task details" placement="side" onClose={onClose}>
      <p className="mt-5 text-base font-medium break-words text-text-primary">{task.title}</p>
      {task.description && (
        <p className="mt-2 text-sm leading-6 break-words whitespace-pre-wrap">{task.description}</p>
      )}
      <dl className="mt-6 grid grid-cols-[6rem_minmax(0,1fr)] gap-y-2 text-sm">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-text-meta">{label}</dt>
            <dd className="text-text-primary">{value}</dd>
          </div>
        ))}
      </dl>
      <div className="mt-6 flex justify-end">
        <button type="button" className={cancelButton} onClick={onClose}>
          Close
        </button>
      </div>
    </Dialog>
  )
}

function DeleteDialog({
  orgId,
  task,
  onCancel,
  onDeleted,
}: {
  orgId: string
  task: Task
  onCancel(): void
  onDeleted(): void
}) {
  const deleteTask = useDeleteTask(orgId, task.projectId)
  const [error, setError] = useState<string | null>(null)

  async function confirmDelete() {
    setError(null)
    try {
      await deleteTask.mutateAsync(task.id)
      onDeleted()
    } catch (caught) {
      setError(
        caught instanceof ApiError && caught.problem.detail
          ? caught.problem.detail
          : 'The task wasn’t deleted. Check your connection and try again.',
      )
    }
  }

  return (
    <Dialog title={`Delete ${task.title}?`} onClose={onCancel}>
      <p className="mt-2 text-sm leading-6">
        This permanently removes the task. It can’t be undone.
      </p>
      {error && (
        <p role="alert" className="mt-3 text-sm text-error-text">
          {error}
        </p>
      )}
      <div className="mt-5 flex justify-end gap-3">
        <button type="button" className={cancelButton} onClick={onCancel}>
          Keep task
        </button>
        <button
          type="button"
          disabled={deleteTask.isPending}
          className={dangerButton}
          onClick={() => void confirmDelete()}
        >
          Delete permanently
        </button>
      </div>
    </Dialog>
  )
}

interface TaskPanelProps {
  orgId: string
  projectId: string
  task?: Task
  members?: Member[]
  readOnly: boolean
  onClose(): void
}

export function TaskPanel({ task, readOnly, ...props }: TaskPanelProps) {
  if (readOnly && task) {
    return <TaskDetails task={task} members={props.members} onClose={props.onClose} />
  }
  return <TaskEditor task={task} {...props} />
}

function TaskEditor({
  orgId,
  projectId,
  task,
  members,
  onClose,
}: Omit<TaskPanelProps, 'readOnly'>) {
  const createTask = useCreateTask(orgId, projectId)
  const updateTask = useUpdateTask(orgId, projectId)
  const reloadTask = useReloadTask(orgId)
  const [baseline, setBaseline] = useState(task)
  const [form, setForm] = useState(() => toForm(task))
  const [errors, setErrors] = useState<PanelErrors>({})
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const saving = createTask.isPending || updateTask.isPending
  const unknownAssignee =
    form.assigneeId && !members?.some((member) => member.userId === form.assigneeId)

  const setField = <K extends keyof TaskForm>(field: K, value: TaskForm[K]) =>
    setForm((current) => ({ ...current, [field]: value }))

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!form.title.trim()) {
      setErrors({ title: 'Enter a task title.' })
      return
    }
    setErrors({})
    try {
      if (baseline) {
        const patch = toPatch(baseline, form)
        if (patch) await updateTask.mutateAsync({ taskId: baseline.id, body: patch })
      } else {
        await createTask.mutateAsync(toValues(form))
      }
      onClose()
    } catch (error) {
      setErrors(toPanelErrors(error))
    }
  }

  async function reload() {
    if (!baseline) return
    try {
      const latest = await reloadTask.mutateAsync(baseline.id)
      setBaseline(latest)
      setForm(toForm(latest))
      setErrors({})
    } catch (error) {
      setErrors(
        error instanceof ApiError && error.status === 404
          ? { form: 'This task no longer exists.' }
          : { form: 'The latest version didn’t load. Your text is still here.', stale: true },
      )
    }
  }

  return (
    <>
      <Dialog title={task ? 'Edit task' : 'New task'} placement="side" onClose={onClose}>
        <form className="mt-5 flex flex-col gap-4" noValidate onSubmit={handleSubmit}>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="task-title" className={labelClass}>Title</label>
            <input
              id="task-title"
              className={fieldClass}
              autoFocus
              maxLength={200}
              value={form.title}
              aria-invalid={errors.title ? true : undefined}
              aria-describedby={errors.title ? 'task-title-error' : undefined}
              onChange={(event) => {
                setField('title', event.target.value)
                setErrors((current) => ({ ...current, title: undefined }))
              }}
            />
            {errors.title && (
              <p id="task-title-error" className="text-sm text-error-text">
                {errors.title}
              </p>
            )}
          </div>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="task-description" className={labelClass}>Description</label>
            <textarea
              id="task-description"
              className={`${fieldClass} min-h-28 resize-y`}
              maxLength={10000}
              value={form.description}
              onChange={(event) => setField('description', event.target.value)}
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="task-status" className={labelClass}>Status</label>
              <select
                id="task-status"
                className={fieldClass}
                value={form.status}
                onChange={(event) => setField('status', event.target.value as TaskStatus)}
              >
                {TASK_STATUSES.map((item) => (
                  <option key={item} value={item}>{STATUS_LABELS[item]}</option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="task-priority" className={labelClass}>Priority</label>
              <select
                id="task-priority"
                className={fieldClass}
                value={form.priority}
                onChange={(event) => setField('priority', event.target.value as TaskPriority)}
              >
                {TASK_PRIORITIES.map((item) => (
                  <option key={item} value={item}>{PRIORITY_LABELS[item]}</option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="task-assignee" className={labelClass}>Assignee</label>
              <select
                id="task-assignee"
                className={fieldClass}
                disabled={!members}
                value={form.assigneeId}
                onChange={(event) => setField('assigneeId', event.target.value)}
              >
                <option value="">Unassigned</option>
                {unknownAssignee && <option value={form.assigneeId}>Unknown member</option>}
                {members?.map((member) => (
                  <option key={member.userId} value={member.userId}>
                    {memberName(member)}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="task-due-date" className={labelClass}>Due date</label>
              <input
                id="task-due-date"
                type="date"
                className={fieldClass}
                value={form.dueDate}
                onChange={(event) => setField('dueDate', event.target.value)}
              />
            </div>
          </div>

          {errors.form && (
            <div role="alert" className="rounded-md bg-error-tint p-3 text-sm text-error-text">
              <p>{errors.form}</p>
              {errors.stale && (
                <button
                  type="button"
                  disabled={reloadTask.isPending}
                  className="mt-2 font-medium text-text-primary underline disabled:opacity-60"
                  onClick={() => void reload()}
                >
                  Reload latest
                </button>
              )}
            </div>
          )}

          <div className="mt-1 flex items-center justify-between gap-3">
            {task ? (
              <button
                type="button"
                className="rounded-md px-2 py-2 text-sm text-error-text hover:bg-error-tint"
                onClick={() => setConfirmingDelete(true)}
              >
                Delete task
              </button>
            ) : (
              <span />
            )}
            <div className="flex gap-2">
              <button type="button" className={cancelButton} onClick={onClose}>
                Cancel
              </button>
              <button type="submit" disabled={saving} className={primaryButton}>
                {task ? 'Save changes' : 'Create task'}
              </button>
            </div>
          </div>
        </form>
      </Dialog>
      {confirmingDelete && baseline && (
        <DeleteDialog
          orgId={orgId}
          task={baseline}
          onCancel={() => setConfirmingDelete(false)}
          onDeleted={onClose}
        />
      )}
    </>
  )
}
