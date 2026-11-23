import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type Invite = components['schemas']['Invite']
export type InviteCreate = components['schemas']['InviteCreate']
export type InviteRole = components['schemas']['InviteRole']
export type MemberPatch = components['schemas']['MemberPatch']

const inviteKey = (orgId: string) => ['invites', orgId] as const
const memberKey = (orgId: string) => ['members', orgId] as const

export function useInvites(orgId: string, enabled: boolean) {
  return useQuery({
    queryKey: inviteKey(orgId),
    enabled,
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/invites', {
        params: { path: { orgId }, query: { size: 100 } },
      })
      return data!.items
    },
  })
}

export function useCreateInvite(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: InviteCreate) => {
      const { data } = await api.POST('/organizations/{orgId}/invites', {
        params: { path: { orgId } },
        body,
      })
      return data!
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: inviteKey(orgId) }),
  })
}

export function useCancelInvite(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (inviteId: string) => {
      await api.DELETE('/organizations/{orgId}/invites/{inviteId}', {
        params: { path: { orgId, inviteId } },
      })
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: inviteKey(orgId) }),
  })
}

export function useUpdateMember(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ userId, body }: { userId: string; body: MemberPatch }) => {
      const { data } = await api.PATCH('/organizations/{orgId}/members/{userId}', {
        params: { path: { orgId, userId } },
        body,
      })
      return data!
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: memberKey(orgId) }),
        queryClient.invalidateQueries({ queryKey: ['organizations'] }),
        queryClient.invalidateQueries({ queryKey: ['project-team', orgId] }),
      ]),
  })
}

export function useUpdateApprovalSettings(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: components['schemas']['OrganizationSettingsPatch']) => {
      const { data } = await api.PATCH('/organizations/{orgId}/settings', {
        params: { path: { orgId } },
        body,
      })
      return data!
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['organizations'] }),
        queryClient.invalidateQueries({ queryKey: ['reviews', orgId] }),
      ]),
  })
}

export function useRemoveMember(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (userId: string) => {
      await api.DELETE('/organizations/{orgId}/members/{userId}', {
        params: { path: { orgId, userId } },
      })
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: memberKey(orgId) }),
        queryClient.invalidateQueries({ queryKey: ['tasks', orgId] }),
        queryClient.invalidateQueries({ queryKey: ['project-team', orgId] }),
      ]),
  })
}
