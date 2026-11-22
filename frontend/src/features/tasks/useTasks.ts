import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'
import { projectKeys } from '../projects/useProjects'

export type Task = components['schemas']['Task']
export type TaskCreate = components['schemas']['TaskCreate']
export type TaskPatch = components['schemas']['TaskPatch']
export type TaskStatus = components['schemas']['TaskStatus']
export type TaskPriority = components['schemas']['TaskPriority']
export type Member = components['schemas']['Member']

export const STATUS_LABELS: Record<TaskStatus, string> = {
  BACKLOG: 'Backlog',
  TODO: 'To do',
  IN_PROGRESS: 'In progress',
  IN_REVIEW: 'In review',
  DONE: 'Done',
}

export const PRIORITY_LABELS: Record<TaskPriority, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  URGENT: 'Urgent',
}

export const TASK_STATUSES = Object.keys(STATUS_LABELS) as TaskStatus[]
export const TASK_PRIORITIES = Object.keys(PRIORITY_LABELS) as TaskPriority[]

const PAGE_SIZE = 20

export interface TaskFilter {
  status?: TaskStatus
  assigneeId?: string
  page: number
}

const taskKeys = {
  all: (orgId: string, projectId: string) => ['tasks', orgId, projectId] as const,
  list: (orgId: string, projectId: string, filter: TaskFilter) =>
    ['tasks', orgId, projectId, 'list', filter] as const,
}

export function memberName(member: Member) {
  return member.displayName || member.email
}

const dueDateFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeZone: 'UTC',
})

// Due dates have no time zone, so format them as UTC to keep the same day.
export function formatDueDate(date: string) {
  return dueDateFormat.format(new Date(`${date}T00:00:00Z`))
}

export function useMembers(orgId: string) {
  return useQuery({
    queryKey: ['members', orgId],
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/members', {
        params: { path: { orgId }, query: { size: 100 } },
      })
      return data!.items
    },
  })
}

export function useTasks(orgId: string, projectId: string, filter: TaskFilter) {
  return useQuery({
    queryKey: taskKeys.list(orgId, projectId, filter),
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const { data } = await api.GET(
        '/organizations/{orgId}/projects/{projectId}/tasks',
        {
          params: {
            path: { orgId, projectId },
            query: {
              status: filter.status,
              assigneeId: filter.assigneeId,
              page: filter.page,
              size: PAGE_SIZE,
            },
          },
        },
      )
      return data!
    },
  })
}

// A failed write can mean the task or project changed, so reload both either way.
function useRefreshTasks(orgId: string, projectId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: taskKeys.all(orgId, projectId) }),
      queryClient.invalidateQueries({ queryKey: projectKeys.detail(orgId, projectId) }),
    ])
}

export function useCreateTask(orgId: string, projectId: string) {
  const onSettled = useRefreshTasks(orgId, projectId)
  return useMutation({
    mutationFn: async (body: TaskCreate) => {
      const { data } = await api.POST(
        '/organizations/{orgId}/projects/{projectId}/tasks',
        { params: { path: { orgId, projectId } }, body },
      )
      return data!
    },
    onSettled,
  })
}

export function useUpdateTask(orgId: string, projectId: string) {
  const onSettled = useRefreshTasks(orgId, projectId)
  return useMutation({
    mutationFn: async ({ taskId, body }: { taskId: string; body: TaskPatch }) => {
      const { data } = await api.PATCH('/organizations/{orgId}/tasks/{taskId}', {
        params: { path: { orgId, taskId } },
        body,
      })
      return data!
    },
    onSettled,
  })
}

export function useDeleteTask(orgId: string, projectId: string) {
  const onSettled = useRefreshTasks(orgId, projectId)
  return useMutation({
    mutationFn: async (taskId: string) => {
      await api.DELETE('/organizations/{orgId}/tasks/{taskId}', {
        params: { path: { orgId, taskId } },
      })
    },
    onSettled,
  })
}

export function useReloadTask(orgId: string) {
  return useMutation({
    mutationFn: async (taskId: string) => {
      const { data } = await api.GET('/organizations/{orgId}/tasks/{taskId}', {
        params: { path: { orgId, taskId } },
      })
      return data!
    },
  })
}
