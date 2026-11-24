import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { useProject } from '../projects/useProjects'
import { primaryButton, TestModeBanner } from '../requirements/TestModeBanner'
import { isActiveRun, runErrorMessage, useAiRun, type AiRun } from '../requirements/useRequirements'
import {
  STEP_LABELS,
  STEP_NUMBERS,
  VERSION_STATUS,
  useArchitecture,
  useArchitectureVersions,
  useGenerateArchitecture,
  useRefreshArchitecture,
  type ArchitectureVersionSummary,
} from './useArchitecture'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

export function ArchitecturePage() {
  const { org } = useCurrentOrg()
  const { projectId = '' } = useParams()
  return <ProjectArchitecture key={`${org.id}:${projectId}`} projectId={projectId} />
}

function VersionLink({ basePath, version }: { basePath: string; version: ArchitectureVersionSummary }) {
  const status = VERSION_STATUS[version.status]
  return (
    <Link
      to={`${basePath}/versions/${version.id}`}
      className="flex flex-wrap items-center gap-3 font-medium text-text-primary hover:underline"
    >
      <span>Version {version.versionNumber}</span>
      <span className={`rounded px-1.5 py-0.5 text-xs no-underline ${status.className}`}>{status.label}</span>
      <span className="font-mono text-xs text-text-meta">{dateFormat.format(new Date(version.createdAt))}</span>
    </Link>
  )
}

function RunUsage({ run }: { run: AiRun }) {
  return (
    <p className="font-mono text-xs leading-5 text-text-meta">
      {run.model}, {run.inputTokens.toLocaleString()} input tokens, {run.outputTokens.toLocaleString()} output
      tokens across three steps, {run.billingTier === 'FREE' ? 'Free tier' : 'Paid tier'}
    </p>
  )
}

function ProjectArchitecture({ projectId }: { projectId: string }) {
  const { org, can } = useCurrentOrg()
  const project = useProject(org.id, projectId)
  const state = useArchitecture(org.id, projectId)
  const versions = useArchitectureVersions(org.id, projectId)
  const generate = useGenerateArchitecture(org.id, projectId)
  const refresh = useRefreshArchitecture(org.id, projectId)
  const [startedRunId, setStartedRunId] = useState<string | null>(null)

  const latestRun = state.data?.latestRun ?? null
  const trackedRunId = startedRunId ?? (isActiveRun(latestRun) ? latestRun!.id : null)
  const trackedRun = useAiRun(org.id, trackedRunId)
  const finishedRunId = trackedRun.data && !isActiveRun(trackedRun.data) ? trackedRun.data.id : null

  useEffect(() => {
    if (!finishedRunId) return
    let current = true
    void refresh().finally(() => {
      if (current) setStartedRunId(null)
    })
    return () => {
      current = false
    }
    // refresh is rebuilt each render, and only a newly finished run should trigger it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [finishedRunId])

  const projectPath = `/orgs/${org.id}/projects/${projectId}`
  const basePath = `${projectPath}/architecture`

  if (project.isPending || state.isPending) return <LoadingState label="Loading architecture" />
  if (!project.data || !state.data) {
    const missing = project.error instanceof ApiError && [400, 404].includes(project.error.status)
    return (
      <InlineAlert
        title={missing ? 'Project not found' : 'Architecture didn’t load'}
        description={missing ? 'It doesn’t exist in this organization.' : 'Check your connection, then try again.'}
      >
        <Link to={`/orgs/${org.id}`}>Back to projects</Link>
      </InlineAlert>
    )
  }

  const archived = project.data.status === 'ARCHIVED'
  const canGenerate = can('MANAGE_PROJECTS') && !archived
  const { current, draft } = state.data
  const run = trackedRun.data ?? latestRun
  const running = generate.isPending || Boolean(trackedRunId && (!trackedRun.data || isActiveRun(trackedRun.data)))
  const failed = !running && run?.status === 'FAILED'
  const startFailure = generate.error
    ? generate.error instanceof ApiError
      ? generate.error.message
      : 'The run didn’t start. Check your connection, then try again.'
    : null

  function start() {
    generate.mutate(undefined, { onSuccess: (runId) => setStartedRunId(runId) })
  }

  return (
    <section>
      <Link to={projectPath} className="text-sm">
        Back to tasks
      </Link>
      <div className="mt-3 mb-4 flex min-h-9 flex-wrap items-center justify-between gap-3">
        <h2 className="min-w-0 text-lg font-semibold break-words text-text-primary">
          {project.data.name} architecture
        </h2>
        {canGenerate && !failed && (
          <button type="button" className={primaryButton} disabled={running} onClick={start}>
            {current || draft ? 'Update architecture' : 'Generate architecture'}
          </button>
        )}
      </div>
      <TestModeBanner />

      <div aria-live="polite" className="mb-5">
        {running && (
          <p role="status" className="text-sm text-info">
            {run?.status === 'RUNNING' && run.currentStep
              ? `Step ${STEP_NUMBERS[run.currentStep]} of 3: ${STEP_LABELS[run.currentStep]}`
              : 'Waiting to start'}
          </p>
        )}
        {failed && (
          <InlineAlert
            title="Architecture generation failed"
            description={`${runErrorMessage(run.errorCode)}${
              run.currentStep ? ` It stopped at the ${STEP_LABELS[run.currentStep]} step.` : ''
            }`}
          >
            {canGenerate && (
              <button type="button" className={primaryButton} onClick={start}>
                Try again
              </button>
            )}
          </InlineAlert>
        )}
        {!running && run?.status === 'SUCCEEDED' && <RunUsage run={run} />}
        {startFailure && !running && (
          <p role="alert" className="mt-2 text-sm text-error-text">
            {startFailure}
          </p>
        )}
      </div>

      {!current && !draft ? (
        !running && (
          <p className="max-w-prose text-sm leading-6">
            No architecture yet.{' '}
            {canGenerate
              ? 'Generate one from this project’s approved user stories.'
              : 'An Owner or Admin can generate one once user stories are approved.'}
          </p>
        )
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          <section aria-label="Current architecture" className="rounded-lg border border-border bg-surface px-4 py-3">
            <h3 className="text-sm font-semibold text-text-primary">Current architecture</h3>
            <div className="mt-2 text-sm">
              {current ? <VersionLink basePath={basePath} version={current} /> : 'No version approved yet.'}
            </div>
          </section>
          <section
            aria-label="Draft waiting for review"
            className="rounded-lg border border-border bg-surface px-4 py-3"
          >
            <h3 className="text-sm font-semibold text-text-primary">Draft waiting for review</h3>
            <div className="mt-2 text-sm">
              {draft ? <VersionLink basePath={basePath} version={draft} /> : 'No draft waiting.'}
            </div>
          </section>
        </div>
      )}

      {versions.data && versions.data.items.length > 0 && (
        <section aria-label="Version history" className="mt-8">
          <h3 className="text-base font-semibold text-text-primary">Version history</h3>
          <ul className="mt-3 divide-y divide-hairline rounded-lg border border-border bg-surface">
            {versions.data.items.map((version) => (
              <li key={version.id} className="px-4 py-3 text-sm">
                <VersionLink basePath={basePath} version={version} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </section>
  )
}
