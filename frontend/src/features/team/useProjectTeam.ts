import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type JobRole = components['schemas']['JobRole']
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

export function useProjectTeam(orgId: string, projectId: string) {
  return useQuery({
    queryKey: teamKey(orgId, projectId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/projects/{projectId}/team', {
        params: { path: { orgId, projectId } },
      })
      return data!
    },
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
