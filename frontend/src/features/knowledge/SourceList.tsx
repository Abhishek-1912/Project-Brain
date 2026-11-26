import { ApiError } from '../../api/client'
import { EmptyState } from '../../components/EmptyState'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { quietButton } from '../requirements/TestModeBanner'
import { runErrorMessage } from '../requirements/useRequirements'
import {
  SOURCE_STATUS,
  SOURCE_TYPE_LABELS,
  useKnowledgeSources,
  useRebuildKnowledge,
  type KnowledgeSource,
} from './useKnowledge'

const INDEX_ERRORS: Record<string, string> = {
  EMBEDDING_UNAVAILABLE: 'The embedding model isn’t available. Check the AI service, then rebuild.',
  INTERNAL_ERROR: 'Indexing failed unexpectedly. Rebuild the index to try again.',
}

const failureText = (code: string | null) => (code && INDEX_ERRORS[code]) || runErrorMessage(code)

interface SourceListProps {
  orgId: string
  projectId: string
  canRebuild: boolean
  archived: boolean
}

export function SourceList({ orgId, projectId, canRebuild, archived }: SourceListProps) {
  const sources = useKnowledgeSources(orgId, projectId)
  const rebuild = useRebuildKnowledge(orgId, projectId)

  const rebuildButton = canRebuild && (
    <button
      type="button"
      className={quietButton}
      disabled={archived || rebuild.isPending}
      onClick={() => rebuild.mutate()}
    >
      Rebuild index
    </button>
  )

  return (
    <section aria-labelledby="indexed-sources-heading" className="mb-8">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-3">
        <h3 id="indexed-sources-heading" className="text-base font-semibold text-text-primary">
          Indexed sources
        </h3>
        {rebuildButton}
      </div>
      <div aria-live="polite">
        {rebuild.data !== undefined && !rebuild.isPending && (
          <p role="status" className="mb-2 text-sm text-info">
            Queued {rebuild.data} {rebuild.data === 1 ? 'item' : 'items'} for indexing.
          </p>
        )}
        {rebuild.error && (
          <p role="alert" className="mb-2 text-sm text-error-text">
            {rebuild.error instanceof ApiError
              ? rebuild.error.message
              : 'The rebuild didn’t start. Check your connection, then try again.'}
          </p>
        )}
      </div>
      <SourceItems sources={sources} canRebuild={canRebuild} />
    </section>
  )
}

function SourceItems({
  sources,
  canRebuild,
}: {
  sources: ReturnType<typeof useKnowledgeSources>
  canRebuild: boolean
}) {
  if (sources.isPending) return <LoadingState label="Loading sources" />
  if (!sources.data) {
    return <InlineAlert title="Sources didn’t load" description="Check your connection, then try again." />
  }
  if (sources.data.items.length === 0) {
    return (
      <EmptyState
        title="Nothing indexed yet"
        description={
          canRebuild
            ? 'Rebuild the index to add uploaded documents, answered questions, approved stories, the approved architecture, and notes.'
            : 'Knowledge appears here as documents are uploaded and items are approved.'
        }
      />
    )
  }
  return (
    <ul className="divide-y divide-border border-y border-border">
      {sources.data.items.map((source) => (
        <SourceRow key={source.id} source={source} />
      ))}
    </ul>
  )
}

function SourceRow({ source }: { source: KnowledgeSource }) {
  const status = SOURCE_STATUS[source.status]
  return (
    <li className="py-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="min-w-0 font-medium break-words text-text-primary">{source.title}</span>
        <span className="text-xs text-text-meta">{SOURCE_TYPE_LABELS[source.sourceType]}</span>
        <span className={`rounded px-1.5 py-0.5 text-xs ${status.className}`}>{status.label}</span>
      </div>
      {source.status === 'FAILED' && (
        <p className="mt-0.5 text-xs text-error-text">{failureText(source.errorCode)}</p>
      )}
    </li>
  )
}
