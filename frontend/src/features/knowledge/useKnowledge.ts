import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type KnowledgeSource = components['schemas']['KnowledgeSource']
export type KnowledgeAnswer = components['schemas']['KnowledgeAnswer']
export type KnowledgeCitation = components['schemas']['KnowledgeCitation']
export type SourceType = KnowledgeSource['sourceType']

export const SOURCE_POLL_MS = 3000

export const SOURCE_TYPE_LABELS: Record<SourceType, string> = {
  DOCUMENT: 'Document',
  CLARIFICATION: 'Clarification',
  STORY: 'Story',
  ARCHITECTURE: 'Architecture',
  NOTE: 'Note',
}

export const SOURCE_STATUS: Record<KnowledgeSource['status'], { label: string; className: string }> = {
  PENDING: { label: 'Waiting', className: 'bg-surface-high text-text-secondary' },
  INDEXING: { label: 'Indexing', className: 'bg-info-tint text-info' },
  READY: { label: 'Ready', className: 'bg-success-tint text-success' },
  FAILED: { label: 'Failed', className: 'bg-error-tint text-error-text' },
}

const isWaiting = (source: KnowledgeSource) => source.status === 'PENDING' || source.status === 'INDEXING'

export const sourcesKey = (orgId: string, projectId: string) => ['knowledge-sources', orgId, projectId] as const

export function useKnowledgeSources(orgId: string, projectId: string) {
  return useQuery({
    queryKey: sourcesKey(orgId, projectId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/projects/{projectId}/knowledge/sources', {
        params: { path: { orgId, projectId }, query: { size: 100 } },
      })
      return data!
    },
    refetchInterval: (query) => (query.state.data?.items.some(isWaiting) ? SOURCE_POLL_MS : false),
  })
}

export function useRebuildKnowledge(orgId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const { data } = await api.POST('/organizations/{orgId}/projects/{projectId}/knowledge/rebuild', {
        params: { path: { orgId, projectId } },
      })
      return data!.queued
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: sourcesKey(orgId, projectId) }),
  })
}

export function useAskKnowledge(orgId: string, projectId: string) {
  return useMutation({
    mutationFn: async (question: string) => {
      const { data } = await api.POST('/organizations/{orgId}/projects/{projectId}/knowledge/ask', {
        params: { path: { orgId, projectId } },
        body: { question },
      })
      return data!
    },
  })
}

/** Where a citation's source can be read. Stories and questions live on their document, which a citation doesn't name. */
export function sourcePath(projectPath: string, sourceType: SourceType, sourceId: string) {
  switch (sourceType) {
    case 'DOCUMENT':
      return `${projectPath}/requirements/${sourceId}`
    case 'STORY':
    case 'CLARIFICATION':
      return `${projectPath}/requirements`
    case 'ARCHITECTURE':
      return `${projectPath}/architecture`
    case 'NOTE':
      return `${projectPath}/knowledge#note-${sourceId}`
  }
}
