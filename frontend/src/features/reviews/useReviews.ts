import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'
import { projectKeys } from '../projects/useProjects'

export type ApprovalBasis = components['schemas']['ApprovalBasis']
export type ReviewQueueItem = components['schemas']['ReviewQueueItem']
export type TaskReview = components['schemas']['TaskReview']
export type ReviewAction = 'submit' | 'approve' | 'reject' | 'withdraw' | 'reopen'

export const QUEUE_LOOKUP_SIZE = 100

export function reviewReason(basis: ApprovalBasis, selfApproval: boolean) {
  if (selfApproval) return 'Self-approval'
  return basis === 'OWNER_FALLBACK' ? 'Owner fallback' : 'Role holder'
}

export function useMyReviews(orgId: string, page: number, size = 20, enabled = true) {
  return useQuery({
    queryKey: ['reviews', orgId, page, size],
    enabled,
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/reviews/mine', {
        params: { path: { orgId }, query: { page, size } },
      })
      return data!
    },
  })
}

export function useTaskReviews(orgId: string, taskId: string) {
  return useQuery({
    queryKey: ['task-reviews', orgId, taskId],
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/tasks/{taskId}/reviews', {
        params: { path: { orgId, taskId }, query: { size: 100 } },
      })
      return data!.items
    },
  })
}

export function useReviewAction(orgId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      taskId,
      action,
      version,
      comment,
    }: {
      taskId: string
      action: ReviewAction
      version: number
      comment?: string
    }) => {
      // Every review action shares the same request and response shape.
      const path = `/organizations/{orgId}/tasks/{taskId}/${action}` as '/organizations/{orgId}/tasks/{taskId}/submit'
      const { data } = await api.POST(path, {
        params: { path: { orgId, taskId } },
        body: comment ? { version, comment } : { version },
      })
      return data!
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['tasks', orgId, projectId] }),
        queryClient.invalidateQueries({ queryKey: ['reviews', orgId] }),
        queryClient.invalidateQueries({ queryKey: ['task-reviews', orgId] }),
        queryClient.invalidateQueries({ queryKey: projectKeys.detail(orgId, projectId) }),
      ]),
  })
}
