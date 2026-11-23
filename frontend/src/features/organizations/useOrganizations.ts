import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext } from 'react'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type Organization = components['schemas']['Organization']
export type OrganizationCreate = components['schemas']['OrganizationCreate']
export type Role = components['schemas']['Role']
export type Permission =
  | 'VIEW'
  | 'EDIT_TASKS'
  | 'MANAGE_PROJECTS'
  | 'VIEW_AUDIT'
  | 'EDIT_ORGANIZATION'
  | 'INVITE_MEMBERS'
  | 'MANAGE_MEMBERS'
  | 'MANAGE_JOB_ROLES'

// Mirrors backend Role.java. The API still enforces every permission.
const ROLE_PERMISSIONS: Record<Role, readonly Permission[]> = {
  OWNER: [
    'VIEW',
    'EDIT_TASKS',
    'MANAGE_PROJECTS',
    'VIEW_AUDIT',
    'EDIT_ORGANIZATION',
    'INVITE_MEMBERS',
    'MANAGE_MEMBERS',
    'MANAGE_JOB_ROLES',
  ],
  ADMIN: ['VIEW', 'EDIT_TASKS', 'MANAGE_PROJECTS', 'VIEW_AUDIT', 'INVITE_MEMBERS'],
  MEMBER: ['VIEW', 'EDIT_TASKS'],
  VIEWER: ['VIEW'],
}

const organizationKeys = {
  list: ['organizations', 'list'] as const,
  detail: (orgId: string) => ['organizations', 'detail', orgId] as const,
}

export function useOrganizations() {
  return useQuery({
    queryKey: organizationKeys.list,
    queryFn: async () => {
      const { data } = await api.GET('/organizations', {
        params: { query: { size: 100 } },
      })
      return data!.items
    },
  })
}

export function useOrganization(orgId: string) {
  return useQuery({
    queryKey: organizationKeys.detail(orgId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}', {
        params: { path: { orgId } },
      })
      return data!
    },
  })
}

export function useCreateOrganization() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: OrganizationCreate) => {
      const { data } = await api.POST('/organizations', { body })
      return data!
    },
    onSuccess: async (organization) => {
      queryClient.setQueryData(
        organizationKeys.detail(organization.id),
        organization,
      )
      await queryClient.invalidateQueries({ queryKey: organizationKeys.list })
    },
  })
}

export const CurrentOrgContext = createContext<Organization | null>(null)

export function useCurrentOrg() {
  const org = useContext(CurrentOrgContext)
  if (!org) {
    throw new Error('useCurrentOrg must be used inside an organization route')
  }
  return {
    org,
    role: org.role,
    can: (permission: Permission) =>
      ROLE_PERMISSIONS[org.role].includes(permission),
  }
}
