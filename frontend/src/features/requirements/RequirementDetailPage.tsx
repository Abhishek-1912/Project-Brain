import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { useProject } from '../projects/useProjects'
import { useProjectTeam } from '../team/useProjectTeam'
import { AnalysisView } from './AnalysisView'
import { ClarificationList } from './ClarificationList'
import { StoryList } from './StoryList'
import { primaryButton, TestModeBanner } from './TestModeBanner'
import {
  analysisKey,
  isActiveRun,
  runErrorMessage,
  useAiRun,
  useAnalysis,
  useRequirement,
  useStartAnalysis,
  type AiRun,
} from './useRequirements'

export function RequirementDetailPage() {
  const { org } = useCurrentOrg()
  const { projectId = '', documentId = '' } = useParams()
  return (
    <RequirementDetail key={`${org.id}:${documentId}`} projectId={projectId} documentId={documentId} />
  )
}

function RunUsage({ run }: { run: AiRun }) {
  return (
    <p className="font-mono text-xs leading-5 text-text-meta">
      {run.model}, {run.promptVersion}, {run.inputTokens.toLocaleString()} input tokens,{' '}
      {run.outputTokens.toLocaleString()} output tokens, {run.billingTier === 'FREE' ? 'Free tier' : 'Paid tier'}
    </p>
  )
}

function RequirementDetail({ projectId, documentId }: { projectId: string; documentId: string }) {
  const { org, can } = useCurrentOrg()
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const [startedRunId, setStartedRunId] = useState<string | null>(null)
  const project = useProject(org.id, projectId)
  const document = useRequirement(org.id, documentId)
  const analysis = useAnalysis(org.id, documentId)
  const start = useStartAnalysis(org.id, documentId)
  const canManage = can('MANAGE_PROJECTS')
  const team = useProjectTeam(org.id, projectId, !canManage)

  const latestRun = analysis.data?.latestRun ?? null
  const trackedRunId = startedRunId ?? (isActiveRun(latestRun) ? latestRun!.id : null)
  const trackedRun = useAiRun(org.id, trackedRunId)
  const finishedRunId =
    trackedRun.data && !isActiveRun(trackedRun.data) ? trackedRun.data.id : null

  useEffect(() => {
    if (!finishedRunId) return
    let current = true
    void queryClient.invalidateQueries({ queryKey: analysisKey(org.id, documentId) }).finally(() => {
      if (current) setStartedRunId(null)
    })
    return () => {
      current = false
    }
  }, [finishedRunId, org.id, documentId, queryClient])

  const requirementsPath = `/orgs/${org.id}/projects/${projectId}/requirements`

  if (document.isPending || project.isPending) return <LoadingState label="Loading document" />
  if (!document.data || !project.data) {
    const missing = document.error instanceof ApiError && [400, 404].includes(document.error.status)
    return (
      <InlineAlert
        title={missing ? 'Document not found' : 'Document didn’t load'}
        description={missing ? 'It doesn’t exist in this organization.' : 'Check your connection, then try again.'}
      >
        <Link to={requirementsPath}>Back to requirements</Link>
      </InlineAlert>
    )
  }

  const archived = project.data.status === 'ARCHIVED'
  const approverRole = document.data.approverJobRoleId
  const holdsApproverRole = Boolean(
    approverRole &&
      team.data?.members.some(
        (member) =>
          member.userId === user?.id && member.role !== 'VIEWER' && member.jobRoleIds.includes(approverRole),
      ),
  )
  const canAnswer = !archived && (canManage || holdsApproverRole)
  const canRun = canManage && !archived
  const run = trackedRun.data ?? latestRun
  const running = start.isPending || Boolean(trackedRunId && (!trackedRun.data || isActiveRun(trackedRun.data)))
  const failed = !running && run?.status === 'FAILED'
  const startFailure = start.error
    ? start.error instanceof ApiError
      ? start.error.message
      : 'The analysis didn’t start. Check your connection, then try again.'
    : null

  function startRun() {
    start.mutate(undefined, { onSuccess: (runId) => setStartedRunId(runId) })
  }

  return (
    <section>
      <Link to={requirementsPath} className="text-sm">
        Back to requirements
      </Link>
      <div className="mt-3 mb-4 flex min-h-9 flex-wrap items-center justify-between gap-3">
        <h2 className="min-w-0 text-lg font-semibold break-words text-text-primary">{document.data.name}</h2>
        {canRun && !failed && (
          <button type="button" className={primaryButton} disabled={running} onClick={startRun}>
            {analysis.data?.analysis ? 'Update analysis' : 'Analyze document'}
          </button>
        )}
      </div>
      <TestModeBanner />
      <div aria-live="polite" className="mb-5">
        {running && (
          <p role="status" className="text-sm text-info">
            {run?.status === 'QUEUED' ? 'Waiting to start' : 'Analyzing the document'}
          </p>
        )}
        {failed && (
          <InlineAlert title="Analysis failed" description={runErrorMessage(run.errorCode)}>
            {canRun && (
              <button type="button" className={primaryButton} onClick={startRun}>
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
      {analysis.isPending ? (
        <LoadingState label="Loading analysis" />
      ) : !analysis.data ? (
        <InlineAlert title="Analysis didn’t load" description="Check your connection, then try again.">
          <button type="button" className={primaryButton} onClick={() => void analysis.refetch()}>
            Try again
          </button>
        </InlineAlert>
      ) : analysis.data.analysis ? (
        <>
          <AnalysisView analysis={analysis.data.analysis} />
          <ClarificationList
            orgId={org.id}
            documentId={documentId}
            questions={analysis.data.questions}
            canAnswer={canAnswer}
          />
          <StoryList
            orgId={org.id}
            projectId={projectId}
            documentId={documentId}
            latestRun={analysis.data.latestStoryRun ?? null}
            canEdit={canAnswer}
            canGenerate={canRun}
            archived={archived}
          />
        </>
      ) : (
        !running &&
        !failed && (
          <p className="max-w-prose text-sm leading-6">
            {canRun
              ? 'Analyze this document to find its features, users, gaps, and risks.'
              : 'This document hasn’t been analyzed yet.'}
          </p>
        )
      )}
    </section>
  )
}
