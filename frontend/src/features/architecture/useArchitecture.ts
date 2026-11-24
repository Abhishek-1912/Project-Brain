import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../../api/client'
import type { components } from '../../api/schema'

export type ArchitectureState = components['schemas']['ArchitectureState']
export type ArchitectureVersion = components['schemas']['ArchitectureVersion']
export type ArchitectureVersionSummary = components['schemas']['ArchitectureVersionSummary']

// The API types sections as open objects; these describe the shape Spring validates and stores.
export interface Overview {
  summary: string
  components: { name: string; responsibility: string; technology?: string | null }[]
  techStack: { area: string; choice: string; reason: string }[]
  decisions: { title: string; decision: string; reason: string; alternatives: string[] }[]
  risks: { text: string }[]
}

export interface Column {
  name: string
  type: string
  nullable: boolean
  description: string
}

export interface Relation {
  column: string
  referencesTable: string
  referencesColumn: string
}

export interface Table {
  name: string
  description: string
  columns: Column[]
  relations: Relation[]
  storyIds: string[]
}

export interface DataModel {
  tables: Table[]
}

export interface Field {
  name: string
  type: string
  required: boolean
  description: string
}

export interface Endpoint {
  method: string
  path: string
  summary: string
  requestFields: Field[]
  responseFields: Field[]
  roles: string[]
  storyIds: string[]
}

export interface ApiDesign {
  endpoints: Endpoint[]
}

export const STEP_LABELS: Record<string, string> = {
  OVERVIEW: 'overview',
  DATA_MODEL: 'data model',
  API_DESIGN: 'API design',
}

export const STEP_NUMBERS: Record<string, number> = { OVERVIEW: 1, DATA_MODEL: 2, API_DESIGN: 3 }

export const VERSION_STATUS: Record<ArchitectureVersionSummary['status'], { label: string; className: string }> = {
  DRAFT: { label: 'Draft', className: 'bg-surface-high text-text-secondary' },
  APPROVED: { label: 'Approved', className: 'bg-success-tint text-success' },
  REJECTED: { label: 'Rejected', className: 'bg-error-tint text-error-text' },
  SUPERSEDED: { label: 'Superseded', className: 'bg-surface-high text-text-meta' },
}

export const stateKey = (orgId: string, projectId: string) => ['architecture', orgId, projectId] as const
export const versionsKey = (orgId: string, projectId: string) => ['architecture-versions', orgId, projectId] as const
export const versionKey = (orgId: string, versionId: string) => ['architecture-version', orgId, versionId] as const

export function useArchitecture(orgId: string, projectId: string) {
  return useQuery({
    queryKey: stateKey(orgId, projectId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/projects/{projectId}/architecture', {
        params: { path: { orgId, projectId } },
      })
      return data!
    },
  })
}

export function useArchitectureVersions(orgId: string, projectId: string) {
  return useQuery({
    queryKey: versionsKey(orgId, projectId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/projects/{projectId}/architecture/versions', {
        params: { path: { orgId, projectId }, query: { size: 100 } },
      })
      return data!
    },
  })
}

export function useArchitectureVersion(orgId: string, versionId: string) {
  return useQuery({
    queryKey: versionKey(orgId, versionId),
    queryFn: async () => {
      const { data } = await api.GET('/organizations/{orgId}/architecture-versions/{versionId}', {
        params: { path: { orgId, versionId } },
      })
      return data!
    },
  })
}

export function useGenerateArchitecture(orgId: string, projectId: string) {
  return useMutation({
    mutationFn: async () => {
      const { data } = await api.POST('/organizations/{orgId}/projects/{projectId}/architecture/generate', {
        params: { path: { orgId, projectId } },
      })
      return data!.runId
    },
  })
}

export function useRefreshArchitecture(orgId: string, projectId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: stateKey(orgId, projectId) }),
      queryClient.invalidateQueries({ queryKey: versionsKey(orgId, projectId) }),
      queryClient.invalidateQueries({ queryKey: ['architecture-version', orgId] }),
    ])
}

export function sectionsOf(version: ArchitectureVersion) {
  return {
    overview: version.overview as unknown as Overview,
    dataModel: version.dataModel as unknown as DataModel,
    apiDesign: version.apiDesign as unknown as ApiDesign,
  }
}
