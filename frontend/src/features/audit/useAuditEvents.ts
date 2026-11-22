import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type AuditEvent = components['schemas']['AuditEvent']

const PAGE_SIZE = 20

export function useAuditEvents(orgId: string, page: number) {
  return useQuery({
    queryKey: ['audit-events', orgId, page],
    placeholderData: keepPreviousData,
    // The log changes with every write, so always read it fresh on open.
    staleTime: 0,
    refetchOnMount: 'always',
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/audit-events', {
        params: { path: { orgId }, query: { page, size: PAGE_SIZE } },
      })
      return data!
    },
  })
}
