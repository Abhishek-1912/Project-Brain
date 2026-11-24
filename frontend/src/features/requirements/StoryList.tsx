import { useEffect, useState } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { PRIORITY_LABELS } from '../tasks/useTasks'
import { useJobRoles } from '../team/useProjectTeam'
import { StoryPanel } from './StoryPanel'
import { primaryButton, quietButton } from './TestModeBanner'
import {
  isActiveRun,
  runErrorMessage,
  useAiRun,
  useCreateTasksFromStories,
  useGenerateStories,
  useRefreshStories,
  useStories,
  type AiRun,
  type Story,
} from './useRequirements'

const STATUS: Record<Story['status'], { label: string; className: string }> = {
  DRAFT: { label: 'Draft', className: 'bg-surface-high text-text-secondary' },
  APPROVED: { label: 'Approved', className: 'bg-success-tint text-success' },
  REJECTED: { label: 'Rejected', className: 'bg-error-tint text-error-text' },
  CONVERTED: { label: 'Task created', className: 'bg-info-tint text-info' },
}

const plural = (count: number, word: string) => `${count} ${word}${count === 1 ? '' : 's'}`

interface StoryListProps {
  orgId: string
  projectId: string
  documentId: string
  latestRun: AiRun | null
  canEdit: boolean
  canGenerate: boolean
  archived: boolean
}

export function StoryList({
  orgId,
  projectId,
  documentId,
  latestRun,
  canEdit,
  canGenerate,
  archived,
}: StoryListProps) {
  const stories = useStories(orgId, documentId)
  const generate = useGenerateStories(orgId, documentId)
  const createTasks = useCreateTasksFromStories(orgId, projectId, documentId)
  const refresh = useRefreshStories(orgId, documentId)
  const jobRoles = useJobRoles(orgId)
  const [startedRunId, setStartedRunId] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [openStoryId, setOpenStoryId] = useState<string | null>(null)
  const [createdCount, setCreatedCount] = useState<number | null>(null)

  const trackedRunId = startedRunId ?? (isActiveRun(latestRun) ? latestRun!.id : null)
  const trackedRun = useAiRun(orgId, trackedRunId)
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

  const items = stories.data?.items ?? []
  const run = trackedRun.data ?? latestRun
  const running =
    generate.isPending || Boolean(trackedRunId && (!trackedRun.data || isActiveRun(trackedRun.data)))
  const failed = !running && run?.status === 'FAILED'
  const draftCount = items.filter((story) => story.status === 'DRAFT').length
  const approvedCount = items.filter((story) => story.status === 'APPROVED').length
  const openStory = items.find((story) => story.id === openStoryId)
  const roleName = new Map(jobRoles.data?.map((role) => [role.id, role.name]))

  const actionError = generate.error ?? createTasks.error
  const actionMessage = actionError
    ? actionError instanceof ApiError
      ? actionError.message
      : 'That didn’t finish. Check your connection, then try again.'
    : null

  function startGeneration() {
    setConfirming(false)
    setCreatedCount(null)
    generate.mutate(undefined, { onSuccess: (runId) => setStartedRunId(runId) })
  }

  function requestGeneration() {
    if (draftCount > 0) setConfirming(true)
    else startGeneration()
  }

  function convert() {
    setCreatedCount(null)
    createTasks.mutate(undefined, { onSuccess: (created) => setCreatedCount(created.length) })
  }

  async function reloadStory(storyId: string) {
    const result = await stories.refetch()
    return result.data?.items.find((story) => story.id === storyId)
  }

  return (
    <section aria-labelledby="stories-heading" className="mt-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 id="stories-heading" className="text-base font-semibold text-text-primary">
          User stories
        </h3>
        <div className="flex flex-wrap gap-2">
          {canEdit && !archived && approvedCount > 0 && (
            <button
              type="button"
              className={primaryButton}
              disabled={createTasks.isPending || running}
              onClick={convert}
            >
              Create {plural(approvedCount, 'task')}
            </button>
          )}
          {canGenerate && !failed && (
            <button
              type="button"
              className={items.length > 0 ? quietButton : primaryButton}
              disabled={running || stories.isPending}
              onClick={requestGeneration}
            >
              {items.length > 0 ? 'Regenerate stories' : 'Generate stories'}
            </button>
          )}
        </div>
      </div>

      <div aria-live="polite" className="mt-2">
        {running && (
          <p role="status" className="text-sm text-info">
            {run?.status === 'QUEUED' && trackedRun.data ? 'Waiting to start' : 'Generating stories'}
          </p>
        )}
        {failed && (
          <InlineAlert title="Story generation failed" description={runErrorMessage(run.errorCode)}>
            {canGenerate && (
              <button type="button" className={primaryButton} onClick={requestGeneration}>
                Try again
              </button>
            )}
          </InlineAlert>
        )}
        {createdCount !== null && (
          <p role="status" className="text-sm text-success">
            {createdCount > 0
              ? `Created ${plural(createdCount, 'task')} in the backlog.`
              : 'These stories already have tasks.'}
          </p>
        )}
        {actionMessage && (
          <p role="alert" className="text-sm text-error-text">
            {actionMessage}
          </p>
        )}
      </div>

      {stories.isPending ? (
        <LoadingState label="Loading stories" />
      ) : !stories.data ? (
        <InlineAlert title="Stories didn’t load" description="Check your connection, then try again.">
          <button type="button" className={primaryButton} onClick={() => void stories.refetch()}>
            Try again
          </button>
        </InlineAlert>
      ) : items.length === 0 ? (
        !running && (
          <p className="mt-2 max-w-prose text-sm leading-6">
            {canGenerate
              ? 'Answer the questions you can, then generate story drafts for people to review.'
              : 'No stories have been generated yet.'}
          </p>
        )
      ) : (
        <div className="mt-3 overflow-x-auto rounded-lg border border-border bg-surface">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border text-xs text-text-meta">
              <tr>
                <th scope="col" className="px-4 py-2.5 font-medium">Story</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Status</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium sm:table-cell">Priority</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium md:table-cell">Job role</th>
              </tr>
            </thead>
            <tbody>
              {items.map((story) => (
                <tr key={story.id} className="border-b border-hairline last:border-b-0">
                  <td className="max-w-md px-4 py-3">
                    <button
                      type="button"
                      className="text-left font-medium break-words text-text-primary hover:underline"
                      onClick={() => setOpenStoryId(story.id)}
                    >
                      {story.title}
                    </button>
                  </td>
                  <td className="px-4 py-3 whitespace-nowrap">
                    <span className={`rounded px-1.5 py-0.5 text-xs ${STATUS[story.status].className}`}>
                      {STATUS[story.status].label}
                    </span>
                  </td>
                  <td className="hidden px-4 py-3 whitespace-nowrap sm:table-cell">
                    {PRIORITY_LABELS[story.priority]}
                  </td>
                  <td className="hidden px-4 py-3 whitespace-nowrap md:table-cell">
                    {story.jobRoleId ? (roleName.get(story.jobRoleId) ?? '…') : 'No role'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {confirming && (
        <Dialog title={`Replace ${plural(draftCount, 'draft')}?`} onClose={() => setConfirming(false)}>
          <p className="mt-3 text-sm leading-6">
            Regenerating replaces every draft, including any edits you made to them. Approved, rejected, and
            converted stories are kept.
          </p>
          <div className="mt-5 flex justify-end gap-2">
            <button type="button" className={quietButton} onClick={() => setConfirming(false)}>
              Keep drafts
            </button>
            <button type="button" className={primaryButton} onClick={startGeneration}>
              Replace drafts
            </button>
          </div>
        </Dialog>
      )}

      {openStory && (
        <StoryPanel
          key={openStory.id}
          orgId={orgId}
          documentId={documentId}
          story={openStory}
          canEdit={canEdit}
          archived={archived}
          onClose={() => setOpenStoryId(null)}
          reloadStory={() => reloadStory(openStory.id)}
        />
      )}
    </section>
  )
}
