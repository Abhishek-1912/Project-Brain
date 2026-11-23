import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type RequirementDocument = components['schemas']['RequirementDocument']
export type AiRun = components['schemas']['AiRun']
export type ClarificationQuestion = components['schemas']['ClarificationQuestion']
export type RequirementAnalysisState = components['schemas']['RequirementAnalysisState']
export type Story = components['schemas']['StoryDraft']
export type StoryUpdate = components['schemas']['StoryUpdate']
export type StoryReview = components['schemas']['StoryReview']

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

const storiesKey = (orgId: string, documentId: string) => ['stories', orgId, documentId] as const
const storyReviewsKey = (orgId: string, storyId: string) => ['story-reviews', orgId, storyId] as const

export function useStories(orgId: string, documentId: string) {
  return useQuery({
    queryKey: storiesKey(orgId, documentId),
    queryFn: async () => {
      // Counts and Create tasks must cover every story, so read all pages.
      const items: Story[] = []
      for (let page = 0; ; page += 1) {
        const { data } = await api.GET('/organizations/{orgId}/requirements/{documentId}/stories', {
          params: { path: { orgId, documentId }, query: { page, size: 100 } },
        })
        items.push(...data!.items)
        if (data!.items.length === 0 || items.length >= data!.totalItems) return { items }
      }
    },
  })
}

export function useStoryReviews(orgId: string, storyId: string) {
  return useQuery({
    queryKey: storyReviewsKey(orgId, storyId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/stories/{storyId}/reviews', {
        params: { path: { orgId, storyId }, query: { size: 100 } },
      })
      return data!.items
    },
  })
}

export function useRefreshStories(orgId: string, documentId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: storiesKey(orgId, documentId) }),
      queryClient.invalidateQueries({ queryKey: analysisKey(orgId, documentId) }),
    ])
}

export function useGenerateStories(orgId: string, documentId: string) {
  return useMutation({
    mutationFn: async () => {
      const { data } = await api.POST('/organizations/{orgId}/requirements/{documentId}/stories/generate', {
        params: { path: { orgId, documentId } },
      })
      return data!.runId
    },
  })
}

function useStoryChanged(orgId: string, documentId: string) {
  const queryClient = useQueryClient()
  return (story: Story) =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: storiesKey(orgId, documentId) }),
      queryClient.invalidateQueries({ queryKey: storyReviewsKey(orgId, story.id) }),
    ])
}

export function useUpdateStory(orgId: string, documentId: string) {
  const onSuccess = useStoryChanged(orgId, documentId)
  return useMutation({
    mutationFn: async ({ storyId, body }: { storyId: string; body: StoryUpdate }) => {
      const { data } = await api.PATCH('/organizations/{orgId}/stories/{storyId}', {
        params: { path: { orgId, storyId } },
        body,
      })
      return data!
    },
    onSuccess,
  })
}

export function useApproveStory(orgId: string, documentId: string) {
  const onSuccess = useStoryChanged(orgId, documentId)
  return useMutation({
    mutationFn: async ({ storyId, version }: { storyId: string; version: number }) => {
      const { data } = await api.POST('/organizations/{orgId}/stories/{storyId}/approve', {
        params: { path: { orgId, storyId } },
        body: { version },
      })
      return data!
    },
    onSuccess,
  })
}

export function useRejectStory(orgId: string, documentId: string) {
  const onSuccess = useStoryChanged(orgId, documentId)
  return useMutation({
    mutationFn: async ({
      storyId,
      version,
      comment,
    }: {
      storyId: string
      version: number
      comment: string
    }) => {
      const { data } = await api.POST('/organizations/{orgId}/stories/{storyId}/reject', {
        params: { path: { orgId, storyId } },
        body: { version, comment },
      })
      return data!
    },
    onSuccess,
  })
}

export function useCreateTasksFromStories(orgId: string, projectId: string, documentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async () => {
      const { data } = await api.POST('/organizations/{orgId}/requirements/{documentId}/create-tasks', {
        params: { path: { orgId, documentId } },
      })
      return data!.created
    },
    // A failed or repeated request may still have changed stories, so always re-read them.
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: storiesKey(orgId, documentId) }),
        queryClient.invalidateQueries({ queryKey: ['tasks', orgId, projectId] }),
      ]),
  })
}
