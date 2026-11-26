import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { useProject } from '../projects/useProjects'
import { TestModeBanner } from '../requirements/TestModeBanner'
import { AskPanel } from './AskPanel'
import { SourceList } from './SourceList'

export function KnowledgePage() {
  const { org } = useCurrentOrg()
  const { projectId = '' } = useParams()
  return <ProjectKnowledge key={`${org.id}:${projectId}`} projectId={projectId} />
}

function ProjectKnowledge({ projectId }: { projectId: string }) {
  const { org, can } = useCurrentOrg()
  const project = useProject(org.id, projectId)
  const projectPath = `/orgs/${org.id}/projects/${projectId}`

  if (project.isPending) return <LoadingState label="Loading knowledge" />
  if (!project.data) {
    const missing = project.error instanceof ApiError && [400, 404].includes(project.error.status)
    return (
      <InlineAlert
        title={missing ? 'Project not found' : 'Knowledge didn’t load'}
        description={missing ? 'It doesn’t exist in this organization.' : 'Check your connection, then try again.'}
      >
        <Link to={`/orgs/${org.id}`}>Back to projects</Link>
      </InlineAlert>
    )
  }

  const archived = project.data.status === 'ARCHIVED'

  return (
    <section>
      <Link to={projectPath} className="text-sm">
        Back to tasks
      </Link>
      <h2 className="mt-3 mb-4 min-w-0 text-lg font-semibold break-words text-text-primary">
        {project.data.name} knowledge
      </h2>
      <TestModeBanner />
      <AskPanel orgId={org.id} projectId={projectId} />
      <SourceList
        orgId={org.id}
        projectId={projectId}
        canRebuild={can('MANAGE_PROJECTS')}
        archived={archived}
      />
    </section>
  )
}
