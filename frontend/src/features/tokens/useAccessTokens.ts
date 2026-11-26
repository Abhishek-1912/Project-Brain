import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type AccessToken = components['schemas']['AccessToken']
export type CreatedAccessToken = components['schemas']['CreatedAccessToken']
export type TokenScope = AccessToken['scope']

export const SCOPE_LABELS: Record<TokenScope, string> = {
  READ: 'Read only',
  READ_WRITE: 'Read and write',
}

export const TOKEN_STATUS: Record<AccessToken['status'], { label: string; className: string }> = {
  ACTIVE: { label: 'Active', className: 'bg-success-tint text-success' },
  EXPIRED: { label: 'Expired', className: 'bg-surface-high text-text-meta' },
  REVOKED: { label: 'Revoked', className: 'bg-error-tint text-error-text' },
}

const tokensKey = (orgId: string) => ['access-tokens', orgId] as const

export function useAccessTokens(orgId: string) {
  return useQuery({
    queryKey: tokensKey(orgId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/access-tokens', { params: { path: { orgId } } })
      return data!.items
    },
  })
}

export function useCreateAccessToken(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: { name: string; scope: TokenScope; expiresInDays: 7 | 30 | 90 }) => {
      const { data } = await api.POST('/organizations/{orgId}/access-tokens', {
        params: { path: { orgId } },
        body,
      })
      return data!
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tokensKey(orgId) }),
  })
}

export function useRevokeAccessToken(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (tokenId: string) => {
      await api.POST('/organizations/{orgId}/access-tokens/{tokenId}/revoke', {
        params: { path: { orgId, tokenId } },
      })
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tokensKey(orgId) }),
  })
}

/** The MCP endpoint sits on the same server as the API, at /mcp. */
export function mcpUrl() {
  const base = import.meta.env.VITE_API_BASE_URL || window.location.origin
  return `${new URL(base, window.location.origin).origin}/mcp`
}

export function claudeCommand(secret: string) {
  return `claude mcp add --transport http project-brain ${mcpUrl()} --header "Authorization: Bearer ${secret}"`
}
