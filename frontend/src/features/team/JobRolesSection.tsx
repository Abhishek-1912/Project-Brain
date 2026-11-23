import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import {
  useCreateJobRole,
  useJobRoles,
  useUpdateJobRole,
  type JobRole,
} from './useProjectTeam'

const fieldClass =
  'w-full rounded-md border border-border bg-surface-low px-3 py-1.5 text-sm text-text-primary'
const primaryButton =
  'rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60'
const quietButton =
  'rounded px-2 py-1 text-sm whitespace-nowrap text-text-secondary hover:bg-surface-bright hover:text-text-primary disabled:text-text-disabled'

function apiMessage(error: unknown, fallback: string) {
  return error instanceof ApiError && error.problem.detail ? error.problem.detail : fallback
}

function JobRoleRow({
  orgId,
  role,
  onError,
}: {
  orgId: string
  role: JobRole
  onError(message: string | null): void
}) {
  const updateRole = useUpdateJobRole(orgId)
  const [name, setName] = useState(role.name)
  const trimmed = name.trim()
  const changed = trimmed !== role.name

  function update(body: { name?: string; active?: boolean }) {
    onError(null)
    updateRole.mutate(
      { jobRoleId: role.id, body: { ...body, version: role.version } },
      {
        onError: (error) =>
          onError(apiMessage(error, 'The job role wasn’t saved. Check your connection and try again.')),
      },
    )
  }

  return (
    <li className="flex flex-wrap items-center gap-2 border-b border-hairline px-4 py-2 last:border-b-0">
      <input
        aria-label={`Name for ${role.name}`}
        maxLength={60}
        className={`${fieldClass} min-w-40 flex-1`}
        value={name}
        onChange={(event) => setName(event.target.value)}
      />
      <span className="w-16 text-xs text-text-meta">{role.active ? 'Active' : 'Inactive'}</span>
      {changed && (
        <button
          type="button"
          aria-label={`Save ${role.name}`}
          disabled={!trimmed || updateRole.isPending}
          className={quietButton}
          onClick={() => update({ name: trimmed })}
        >
          Save
        </button>
      )}
      <button
        type="button"
        aria-label={`${role.active ? 'Deactivate' : 'Reactivate'} ${role.name}`}
        disabled={updateRole.isPending}
        className={quietButton}
        onClick={() => update({ active: !role.active })}
      >
        {role.active ? 'Deactivate' : 'Reactivate'}
      </button>
    </li>
  )
}

export function JobRolesSection({ orgId }: { orgId: string }) {
  const roles = useJobRoles(orgId)
  const createRole = useCreateJobRole(orgId)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setError('Enter a job role name.')
      return
    }
    setError(null)
    try {
      await createRole.mutateAsync(trimmed)
      setName('')
    } catch (caught) {
      setError(apiMessage(caught, 'The job role wasn’t added. Check your connection and try again.'))
    }
  }

  return (
    <div className="mt-8">
      <h3 className="text-base font-semibold text-text-primary">Job roles</h3>
      <p className="mt-1 mb-3 max-w-prose text-sm leading-6">
        Assign these on each project’s team. Deactivated roles stay on existing tasks but can’t be used for new work.
      </p>
      {error && (
        <p role="alert" className="mb-3 rounded-md bg-error-tint px-3 py-2 text-sm text-error-text">
          {error}
        </p>
      )}
      {roles.isPending ? (
        <LoadingState label="Loading job roles" />
      ) : !roles.data ? (
        <InlineAlert title="Job roles didn’t load" description="Check your connection, then try again.">
          <button type="button" className={primaryButton} onClick={() => void roles.refetch()}>
            Try again
          </button>
        </InlineAlert>
      ) : (
        <ul className="rounded-lg border border-border bg-surface">
          {roles.data.map((role) => (
            <JobRoleRow key={`${role.id}:${role.version}`} orgId={orgId} role={role} onError={setError} />
          ))}
        </ul>
      )}
      <form className="mt-3 flex flex-wrap items-end gap-2" noValidate onSubmit={handleSubmit}>
        <label className="flex min-w-48 flex-1 flex-col gap-1 text-sm font-medium text-text-primary">
          New job role
          <input
            maxLength={60}
            className={`${fieldClass} font-normal`}
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        <button type="submit" disabled={createRole.isPending} className={primaryButton}>
          Add job role
        </button>
      </form>
    </div>
  )
}
