import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type JobRole = components['schemas']['JobRole']
export type JobRolePatch = components['schemas']['JobRolePatch']
export type ProjectTeam = components['schemas']['ProjectTeam']
export type ProjectTeamMember = components['schemas']['ProjectTeamMember']
export type ProjectTeamJobRole = components['schemas']['ProjectTeamJobRole']

const teamKey = (orgId: string, projectId: string) => ['project-team', orgId, projectId] as const

export function useJobRoles(orgId: string) {
  return useQuery({
    queryKey: ['job-roles', orgId],
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/job-roles', {
        params: { path: { orgId }, query: { size: 100 } },
      })
      return data!.items
    },
  })
}

export function useProjectTeam(orgId: string, projectId: string, enabled = true) {
  return useQuery({
    queryKey: teamKey(orgId, projectId),
    enabled,
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/projects/{projectId}/team', {
        params: { path: { orgId, projectId } },
      })
      return data!
    },
  })
}

function useRefreshJobRoles(orgId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['job-roles', orgId] }),
      queryClient.invalidateQueries({ queryKey: ['project-team', orgId] }),
      queryClient.invalidateQueries({ queryKey: ['reviews', orgId] }),
    ])
}

export function useCreateJobRole(orgId: string) {
  const onSuccess = useRefreshJobRoles(orgId)
  return useMutation({
    mutationFn: async (name: string) => {
      const { data } = await api.POST('/organizations/{orgId}/job-roles', {
        params: { path: { orgId } },
        body: { name },
      })
      return data!
    },
    onSuccess,
  })
}

export function useUpdateJobRole(orgId: string) {
  const onSettled = useRefreshJobRoles(orgId)
  return useMutation({
    mutationFn: async ({ jobRoleId, body }: { jobRoleId: string; body: JobRolePatch }) => {
      const { data } = await api.PATCH('/organizations/{orgId}/job-roles/{jobRoleId}', {
        params: { path: { orgId, jobRoleId } },
        body,
      })
      return data!
    },
    onSettled,
  })
}

export function useSetProjectJobRole(orgId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      userId,
      jobRoleId,
      assigned,
    }: {
      userId: string
      jobRoleId: string
      assigned: boolean
    }) => {
      const params = { path: { orgId, projectId, userId, jobRoleId } }
      const path = '/organizations/{orgId}/projects/{projectId}/team/{userId}/job-roles/{jobRoleId}'
      if (assigned) await api.PUT(path, { params })
      else await api.DELETE(path, { params })
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: teamKey(orgId, projectId) }),
  })
}
