import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type RequirementDocument = components['schemas']['RequirementDocument']
export type AiRun = components['schemas']['AiRun']
export type ClarificationQuestion = components['schemas']['ClarificationQuestion']
export type RequirementAnalysisState = components['schemas']['RequirementAnalysisState']

export interface AnalysisItem {
  text: string
  sourceQuote?: string | null
  sourceVerified?: boolean
}

export const PAGE_SIZE = 20
const POLL_MS = 1000

export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

const RUN_ERRORS: Record<string, string> = {
  RATE_LIMITED: 'The Gemini free-tier limit was reached. Wait a minute, then try again.',
  PROVIDER_TIMEOUT: 'Gemini took too long to respond. Try again.',
  PROVIDER_ERROR: 'Gemini returned an error. Try again shortly.',
  INVALID_OUTPUT: 'The AI returned a result that couldn’t be used, so nothing was saved. Try again.',
  SERVICE_UNAVAILABLE: 'The AI service is unavailable. Try again shortly.',
  SERVICE_BUSY: 'Too many AI runs are waiting. Try again shortly.',
  ACCESS_REVOKED: 'The person who started this run no longer has access, so nothing was saved.',
  PROJECT_ARCHIVED: 'The project was archived during this run, so nothing was saved.',
  ABANDONED: 'The server restarted during this run, so nothing was saved. Try again.',
}

export function runErrorMessage(code: string | null) {
  return (code && RUN_ERRORS[code]) || 'The run failed and nothing was saved. Try again.'
}

export const isActiveRun = (run?: Pick<AiRun, 'status'> | null) =>
  run?.status === 'QUEUED' || run?.status === 'RUNNING'

const listKey = (orgId: string, projectId: string) => ['requirements', orgId, projectId] as const
const documentKey = (orgId: string, documentId: string) =>
  ['requirement', orgId, documentId] as const
export const analysisKey = (orgId: string, documentId: string) =>
  ['requirement-analysis', orgId, documentId] as const

export function useRequirements(orgId: string, projectId: string, page: number) {
  return useQuery({
    queryKey: [...listKey(orgId, projectId), page],
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/projects/{projectId}/requirements', {
        params: { path: { orgId, projectId }, query: { page, size: PAGE_SIZE } },
      })
      return data!
    },
  })
}

export function useRequirement(orgId: string, documentId: string) {
  return useQuery({
    queryKey: documentKey(orgId, documentId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/requirements/{documentId}', {
        params: { path: { orgId, documentId } },
      })
      return data!
    },
  })
}

export function useAnalysis(orgId: string, documentId: string) {
  return useQuery({
    queryKey: analysisKey(orgId, documentId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/requirements/{documentId}/analysis', {
        params: { path: { orgId, documentId } },
      })
      return data!
    },
  })
}

export async function uploadRequirement(
  orgId: string,
  projectId: string,
  file: File,
  approverJobRoleId?: string,
) {
  const form = new FormData()
  form.append('file', file)
  form.append('dummyDataConfirmed', 'true')
  if (approverJobRoleId) form.append('approverJobRoleId', approverJobRoleId)
  const { data } = await api.POST('/organizations/{orgId}/projects/{projectId}/requirements', {
    params: { path: { orgId, projectId } },
    // The generated body type describes the form's fields, but the request sends real FormData.
    body: form as never,
    bodySerializer: (body) => body as unknown as FormData,
  })
  return data!
}

export function useUploadRequirement(orgId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ file, approverJobRoleId }: { file: File; approverJobRoleId?: string }) =>
      uploadRequirement(orgId, projectId, file, approverJobRoleId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: listKey(orgId, projectId) }),
  })
}

export function useStartAnalysis(orgId: string, documentId: string) {
  return useMutation({
    mutationFn: async () => {
      const { data } = await api.POST('/organizations/{orgId}/requirements/{documentId}/analyze', {
        params: { path: { orgId, documentId } },
      })
      return data!.runId
    },
  })
}

export function useAiRun(orgId: string, runId: string | null) {
  return useQuery({
    queryKey: ['ai-run', orgId, runId],
    enabled: Boolean(runId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/ai-runs/{runId}', {
        params: { path: { orgId, runId: runId! } },
      })
      return data!
    },
    refetchInterval: (query) => (isActiveRun(query.state.data) || !query.state.data ? POLL_MS : false),
  })
}

export function useAnswerClarification(orgId: string, documentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      questionId,
      body,
    }: {
      questionId: string
      body: components['schemas']['ClarificationUpdate']
    }) => {
      const { data } = await api.PATCH('/organizations/{orgId}/clarifications/{questionId}', {
        params: { path: { orgId, questionId } },
        body,
      })
      return data!
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: analysisKey(orgId, documentId) }),
  })
}
