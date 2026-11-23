import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg, type Role } from '../organizations/useOrganizations'
import { useProject } from '../projects/useProjects'
import { useProjectTeam, useSetProjectJobRole } from './useProjectTeam'

const primaryButton =
  'rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover'

const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer, can’t approve',
}

export function ProjectTeamPage() {
  const { org } = useCurrentOrg()
  const { projectId = '' } = useParams()
  return <ProjectTeamGrid key={`${org.id}:${projectId}`} projectId={projectId} />
}

function ProjectTeamGrid({ projectId }: { projectId: string }) {
  const { org, can } = useCurrentOrg()
  const project = useProject(org.id, projectId)
  const team = useProjectTeam(org.id, projectId)
  const setJobRole = useSetProjectJobRole(org.id, projectId)
  const [error, setError] = useState<string | null>(null)
  const tasksPath = `/orgs/${org.id}/projects/${projectId}`

  if (project.isPending || team.isPending) return <LoadingState label="Loading project team" />

  if (!project.data || !team.data) {
    const failure = project.error ?? team.error
    const missing = failure instanceof ApiError && [400, 404].includes(failure.status)
    return missing ? (
      <InlineAlert title="Project not found" description="It doesn’t exist in this organization.">
        <Link to={`/orgs/${org.id}`}>Back to projects</Link>
      </InlineAlert>
    ) : (
      <InlineAlert title="Project team didn’t load" description="Check your connection, then try again.">
        <button
          type="button"
          className={primaryButton}
          onClick={() => {
            void project.refetch()
            void team.refetch()
          }}
        >
          Try again
        </button>
      </InlineAlert>
    )
  }

  const archived = project.data.status === 'ARCHIVED'
  const canEdit = can('MANAGE_PROJECTS') && !archived
  const roles = team.data.jobRoles.filter((role) => role.active)

  function toggle(userId: string, jobRoleId: string, assigned: boolean) {
    setError(null)
    setJobRole.mutate(
      { userId, jobRoleId, assigned },
      {
        onError: (caught) =>
          setError(
            caught instanceof ApiError && caught.problem.detail
              ? caught.problem.detail
              : 'The job role wasn’t saved. Check your connection and try again.',
          ),
      },
    )
  }

  return (
    <section>
      <Link to={tasksPath} className="text-sm">
        Back to tasks
      </Link>
      <h2 className="mt-3 text-lg font-semibold break-words text-text-primary">
        {project.data.name} team
      </h2>
      <p className="mt-1 mb-4 max-w-prose text-sm leading-6">
        People with a job role approve that role’s tasks on this project. If nobody holds a role, the Owner approves.
      </p>
      {archived && (
        <p className="mb-4 rounded-md bg-warning-tint px-3 py-2 text-sm text-text-primary">
          This project is archived. Its team is read-only.
        </p>
      )}
      {error && (
        <p role="alert" className="mb-4 rounded-md bg-error-tint px-3 py-2 text-sm text-error-text">
          {error}
        </p>
      )}
      <div className="overflow-x-auto rounded-lg border border-border bg-surface">
        <table className="w-full min-w-max text-left text-sm">
          <thead className="border-b border-border text-xs text-text-meta">
            <tr>
              <th scope="col" className="sticky left-0 bg-surface px-4 py-2.5 font-medium">
                Person
              </th>
              {roles.map((role) => (
                <th key={role.id} scope="col" className="px-3 py-2.5 text-center font-medium whitespace-nowrap">
                  {role.name}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {team.data.members.map((member) => (
              <tr key={member.userId} className="border-b border-hairline">
                <th scope="row" className="sticky left-0 bg-surface px-4 py-2.5 text-left font-normal">
                  <span className="block font-medium text-text-primary">{member.displayName}</span>
                  <span className="block text-xs text-text-meta">{ROLE_LABELS[member.role]}</span>
                </th>
                {roles.map((role) => {
                  const assigned = member.jobRoleIds.includes(role.id)
                  return (
                    <td key={role.id} className="px-3 py-2.5 text-center">
                      <input
                        type="checkbox"
                        aria-label={`${role.name} for ${member.displayName}`}
                        className="size-4 accent-primary disabled:opacity-60"
                        checked={assigned}
                        disabled={!canEdit || setJobRole.isPending}
                        onChange={() => toggle(member.userId, role.id, !assigned)}
                      />
                    </td>
                  )
                })}
              </tr>
            ))}
          </tbody>
          <tfoot className="border-t border-border bg-surface-low text-xs">
            <tr>
              <th scope="row" className="sticky left-0 bg-surface-low px-4 py-2.5 text-left font-medium text-text-secondary">
                Who approves
              </th>
              {roles.map((role) => (
                <td
                  key={role.id}
                  className={`px-3 py-2.5 text-center whitespace-nowrap ${
                    role.approvalBasis === 'OWNER_FALLBACK' ? 'text-warning' : 'text-text-secondary'
                  }`}
                >
                  {role.approvalBasis === 'OWNER_FALLBACK' ? 'Owner' : 'Role holder'}
                </td>
              ))}
            </tr>
          </tfoot>
        </table>
      </div>
    </section>
  )
}
