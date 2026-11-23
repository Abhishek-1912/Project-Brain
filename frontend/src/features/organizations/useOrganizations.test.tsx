import { renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import {
  CurrentOrgContext,
  useCurrentOrg,
  type Permission,
} from './useOrganizations'

vi.mock('../../auth/supabase', () => ({ supabase: { auth: {} } }))

type Role = components['schemas']['Role']

const organization = (role: Role) => ({
  id: '11111111-1111-4111-8111-111111111111',
  name: 'Acme Labs',
  slug: 'acme-labs',
  allowSelfApproval: true,
  role,
  createdBy: '99999999-9999-4999-8999-999999999999',
  createdAt: '2026-11-22T10:00:00Z',
  updatedAt: '2026-11-22T10:00:00Z',
  version: 0,
})

const ALLOWED: Record<Role, Permission[]> = {
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

const PERMISSIONS: Permission[] = [
  'VIEW',
  'EDIT_TASKS',
  'MANAGE_PROJECTS',
  'VIEW_AUDIT',
  'EDIT_ORGANIZATION',
  'INVITE_MEMBERS',
  'MANAGE_MEMBERS',
  'MANAGE_JOB_ROLES',
]

describe('useCurrentOrg', () => {
  it.each(Object.keys(ALLOWED) as Role[])(
    'matches the backend permissions for %s',
    (role) => {
      const org = organization(role)
      const wrapper = ({ children }: { children: ReactNode }) => (
        <CurrentOrgContext.Provider value={org}>
          {children}
        </CurrentOrgContext.Provider>
      )

      const { result } = renderHook(() => useCurrentOrg(), { wrapper })

      expect(result.current.org).toBe(org)
      expect(result.current.role).toBe(role)
      expect(PERMISSIONS.filter((item) => result.current.can(item))).toEqual(
        ALLOWED[role],
      )
    },
  )

  it('fails clearly outside an organization route', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})

    expect(() => renderHook(() => useCurrentOrg())).toThrow(
      'useCurrentOrg must be used inside an organization route',
    )

    consoleError.mockRestore()
  })
})
