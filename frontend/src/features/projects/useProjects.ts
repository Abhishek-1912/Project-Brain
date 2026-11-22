import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type Project = components['schemas']['Project']
export type ProjectCreate = components['schemas']['ProjectCreate']
export type ProjectPatch = components['schemas']['ProjectPatch']

const PAGE_SIZE = 20

const projectKeys = {
  all: (orgId: string) => ['projects', orgId] as const,
  list: (orgId: string, page: number) =>
    ['projects', orgId, 'list', page] as const,
}

export function useProjects(orgId: string, page: number) {
  return useQuery({
    queryKey: projectKeys.list(orgId, page),
    placeholderData: keepPreviousData,
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/projects', {
        params: { path: { orgId }, query: { page, size: PAGE_SIZE } },
      })
      return data!
    },
  })
}

export function useCreateProject(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: ProjectCreate) => {
      const { data } = await api.POST('/organizations/{orgId}/projects', {
        params: { path: { orgId } },
        body,
      })
      return data!
    },
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: projectKeys.all(orgId) }),
  })
}

export function useUpdateProject(orgId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({
      projectId,
      body,
    }: {
      projectId: string
      body: ProjectPatch
    }) => {
      const { data } = await api.PATCH(
        '/organizations/{orgId}/projects/{projectId}',
        { params: { path: { orgId, projectId } }, body },
      )
      return data!
    },
    // A failed update can mean this copy is stale, so reload it either way.
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: projectKeys.all(orgId) }),
  })
}
