import type { ApiDesign, DataModel, Overview, SectionKey } from './useArchitecture'

export type SectionValue = Overview | DataModel | ApiDesign

/** Trims the free-text lists edited as joined strings before the content is sent. */
export function normalizeSection(section: SectionKey, value: SectionValue): SectionValue {
  if (section === 'overview') {
    const overview = value as Overview
    return {
      ...overview,
      decisions: overview.decisions.map((decision) => ({
        ...decision,
        alternatives: decision.alternatives.map((item) => item.trim()).filter(Boolean),
      })),
    }
  }
  if (section === 'apiDesign') {
    const api = value as ApiDesign
    return {
      endpoints: api.endpoints.map((endpoint) => ({
        ...endpoint,
        roles: endpoint.roles.map((role) => role.trim()).filter(Boolean),
      })),
    }
  }
  return value
}
