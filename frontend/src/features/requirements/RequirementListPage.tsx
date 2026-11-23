import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { EmptyState } from '../../components/EmptyState'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { useProject } from '../projects/useProjects'
import { primaryButton, quietButton, TestModeBanner } from './TestModeBanner'
import { UploadRequirementDialog } from './UploadRequirementDialog'
import { useRequirements } from './useRequirements'

function formatSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function RequirementListPage() {
  const { org } = useCurrentOrg()
  const { projectId = '' } = useParams()
  return <ProjectRequirements key={`${org.id}:${projectId}`} projectId={projectId} />
}

function ProjectRequirements({ projectId }: { projectId: string }) {
  const { org, can } = useCurrentOrg()
  const [page, setPage] = useState(0)
  const [uploading, setUploading] = useState(false)
  const project = useProject(org.id, projectId)
  const documents = useRequirements(org.id, projectId, page)
  const projectPath = `/orgs/${org.id}/projects/${projectId}`

  if (project.isPending) return <LoadingState label="Loading project" />
  if (!project.data) {
    const missing = project.error instanceof ApiError && [400, 404].includes(project.error.status)
    return (
      <InlineAlert
        title={missing ? 'Project not found' : 'Project didn’t load'}
        description={missing ? 'It doesn’t exist in this organization.' : 'Check your connection, then try again.'}
      >
        <Link to={`/orgs/${org.id}`}>Back to projects</Link>
      </InlineAlert>
    )
  }

  const archived = project.data.status === 'ARCHIVED'
  const canUpload = can('MANAGE_PROJECTS') && !archived
  const data = documents.data

  let content
  if (documents.isPending) {
    content = <LoadingState label="Loading documents" />
  } else if (!data) {
    content = (
      <InlineAlert title="Documents didn’t load" description="Check your connection, then try again.">
        <button type="button" className={primaryButton} onClick={() => void documents.refetch()}>
          Try again
        </button>
      </InlineAlert>
    )
  } else if (data.items.length === 0) {
    content = (
      <EmptyState
        title="No requirement documents yet"
        description={
          canUpload
            ? 'Upload a dummy PRD to find its gaps and turn it into user stories.'
            : 'Documents will appear here once an Owner or Admin uploads one.'
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
                <th scope="col" className="px-4 py-2.5 font-medium">Document</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium sm:table-cell">Size</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Uploaded</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((document) => (
                <tr key={document.id} className="border-b border-hairline last:border-b-0">
                  <td className="max-w-md px-4 py-3 break-words">
                    <Link
                      to={`${projectPath}/requirements/${document.id}`}
                      className="font-medium text-text-primary hover:underline"
                    >
                      {document.name}
                    </Link>
                  </td>
                  <td className="hidden px-4 py-3 font-mono text-xs whitespace-nowrap text-text-meta sm:table-cell">
                    {formatSize(document.sizeBytes)}
                  </td>
                  <td className="px-4 py-3 font-mono text-xs whitespace-nowrap text-text-meta">
                    {new Date(document.createdAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data.totalItems > data.size && (
          <nav aria-label="Document pages" className="mt-4 flex items-center justify-between text-sm">
            <button type="button" className={quietButton} disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous page
            </button>
            <span className="font-mono text-xs text-text-meta">
              Page {data.page + 1} of {Math.ceil(data.totalItems / data.size)}
            </span>
            <button
              type="button"
              className={quietButton}
              disabled={(data.page + 1) * data.size >= data.totalItems}
              onClick={() => setPage(page + 1)}
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
      <Link to={projectPath} className="text-sm">
        Back to tasks
      </Link>
      <div className="mt-3 mb-4 flex min-h-9 flex-wrap items-center justify-between gap-3">
        <h2 className="min-w-0 text-lg font-semibold break-words text-text-primary">
          {project.data.name} requirements
        </h2>
        {canUpload && (
          <button type="button" className={primaryButton} onClick={() => setUploading(true)}>
            Upload document
          </button>
        )}
      </div>
      <TestModeBanner />
      {content}
      {uploading && (
        <UploadRequirementDialog orgId={org.id} projectId={projectId} onClose={() => setUploading(false)} />
      )}
    </section>
  )
}
