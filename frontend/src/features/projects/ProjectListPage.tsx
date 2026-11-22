import { useState } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { EmptyState } from '../../components/EmptyState'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { ProjectFormDialog } from './ProjectFormDialog'
import { useProjects, useUpdateProject, type Project } from './useProjects'

const primaryButton =
  'rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60'
const quietButton =
  'rounded px-2 py-1 text-sm text-text-secondary hover:bg-surface-bright hover:text-text-primary disabled:text-text-disabled disabled:hover:bg-transparent'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

type OpenDialog =
  | { kind: 'create' }
  | { kind: 'edit'; project: Project }
  | { kind: 'archive'; project: Project }

function StatusLabel({ status }: { status: Project['status'] }) {
  return status === 'ACTIVE' ? (
    <span className="inline-flex items-center gap-1.5 rounded bg-success-tint px-2 py-0.5 text-xs font-medium text-success">
      <span aria-hidden="true" className="size-1.5 rounded-full bg-success" />
      Active
    </span>
  ) : (
    <span className="inline-flex items-center gap-1.5 rounded bg-surface-high px-2 py-0.5 text-xs font-medium text-text-secondary">
      <span aria-hidden="true" className="size-1.5 rounded-full border border-text-muted" />
      Archived
    </span>
  )
}

function ArchiveDialog({
  orgId,
  project,
  onClose,
}: {
  orgId: string
  project: Project
  onClose(): void
}) {
  const updateProject = useUpdateProject(orgId)
  const [error, setError] = useState<string | null>(null)

  async function archive() {
    setError(null)
    try {
      await updateProject.mutateAsync({
        projectId: project.id,
        body: { status: 'ARCHIVED', version: project.version },
      })
      onClose()
    } catch (caught) {
      setError(
        caught instanceof ApiError && caught.problem.detail
          ? caught.problem.detail
          : 'The project wasn’t archived. Check your connection and try again.',
      )
    }
  }

  return (
    <Dialog title={`Archive ${project.name}?`} onClose={onClose}>
      <p className="mt-2 text-sm leading-6">
        Its tasks stay saved, but they can’t be changed while the project is archived.
      </p>
      {error && (
        <p role="alert" className="mt-3 text-sm text-error-text">
          {error}
        </p>
      )}
      <div className="mt-5 flex justify-end gap-3">
        <button
          type="button"
          className="rounded-md px-3 py-2 text-sm text-text-secondary hover:text-text-primary"
          onClick={onClose}
        >
          Cancel
        </button>
        <button
          type="button"
          disabled={updateProject.isPending}
          className="rounded-md bg-error px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
          onClick={() => void archive()}
        >
          Archive project
        </button>
      </div>
    </Dialog>
  )
}

// Keyed so page and dialog state never carry into another organization.
export function ProjectListPage() {
  const { org } = useCurrentOrg()
  return <OrganizationProjects key={org.id} />
}

function OrganizationProjects() {
  const { org, can } = useCurrentOrg()
  const canManage = can('MANAGE_PROJECTS')
  const [page, setPage] = useState(0)
  const [dialog, setDialog] = useState<OpenDialog | null>(null)
  const { data, isPending, isFetching, isPlaceholderData, refetch } = useProjects(org.id, page)
  const closeDialog = () => setDialog(null)
  const hasProjects = Boolean(data && data.totalItems > 0)
  const hasNext = Boolean(data && (data.page + 1) * data.size < data.totalItems)

  let content
  if (isPending) {
    content = <LoadingState label="Loading projects" />
  } else if (!data) {
    content = (
      <section role="alert" className="max-w-prose border-l-2 border-error py-1 pl-5">
        <h3 className="text-base font-semibold text-text-primary">Projects didn’t load</h3>
        <p className="mt-1 text-sm leading-6">Check your connection, then try again.</p>
        <button
          type="button"
          disabled={isFetching}
          className={`${primaryButton} mt-4`}
          onClick={() => void refetch()}
        >
          Try again
        </button>
      </section>
    )
  } else if (!hasProjects) {
    content = (
      <EmptyState
        title="No projects yet"
        description={
          canManage
            ? 'Create a project to start organizing your team’s work.'
            : 'An owner or admin can create the first project.'
        }
        action={
          canManage && (
            <button
              type="button"
              className={primaryButton}
              onClick={() => setDialog({ kind: 'create' })}
            >
              Create project
            </button>
          )
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
                <th scope="col" className="px-4 py-2.5 font-medium">Name</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Status</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium sm:table-cell">Updated</th>
                {canManage && (
                  <th scope="col" className="px-4 py-2.5 text-right font-medium">Actions</th>
                )}
              </tr>
            </thead>
            <tbody>
              {data.items.map((project) => (
                <tr key={project.id} className="border-b border-hairline last:border-b-0">
                  <td className="max-w-md px-4 py-3 align-top">
                    <p className="font-medium break-words text-text-primary">{project.name}</p>
                    {project.description && (
                      <p className="mt-0.5 line-clamp-2 text-text-meta">{project.description}</p>
                    )}
                  </td>
                  <td className="px-4 py-3 align-top whitespace-nowrap">
                    <StatusLabel status={project.status} />
                  </td>
                  <td className="hidden px-4 py-3 align-top font-mono text-xs whitespace-nowrap text-text-meta sm:table-cell">
                    {dateFormat.format(new Date(project.updatedAt))}
                  </td>
                  {canManage && (
                    <td className="px-4 py-2 text-right align-top whitespace-nowrap">
                      {project.status === 'ACTIVE' && (
                        <>
                          <button
                            type="button"
                            aria-label={`Edit ${project.name}`}
                            className={quietButton}
                            onClick={() => setDialog({ kind: 'edit', project })}
                          >
                            Edit
                          </button>
                          <button
                            type="button"
                            aria-label={`Archive ${project.name}`}
                            className={quietButton}
                            onClick={() => setDialog({ kind: 'archive', project })}
                          >
                            Archive
                          </button>
                        </>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data.totalItems > data.size && (
          <nav aria-label="Project pages" className="mt-4 flex items-center justify-between text-sm">
            <button
              type="button"
              className={quietButton}
              disabled={page === 0 || isPlaceholderData}
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
              disabled={!hasNext || isPlaceholderData}
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
      <div className="mb-4 flex min-h-9 items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-text-primary">Projects</h2>
        {canManage && hasProjects && (
          <button
            type="button"
            className={primaryButton}
            onClick={() => setDialog({ kind: 'create' })}
          >
            Create project
          </button>
        )}
      </div>
      {content}
      {dialog?.kind === 'create' && (
        <ProjectFormDialog orgId={org.id} onClose={closeDialog} />
      )}
      {dialog?.kind === 'edit' && (
        <ProjectFormDialog orgId={org.id} project={dialog.project} onClose={closeDialog} />
      )}
      {dialog?.kind === 'archive' && (
        <ArchiveDialog orgId={org.id} project={dialog.project} onClose={closeDialog} />
      )}
    </section>
  )
}
