import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { ProjectTeamPage } from './ProjectTeamPage'

vi.mock('../../auth/supabase', () => ({
  supabase: {
    auth: {
      getSession: async () => ({
        data: { session: { access_token: 'test-token' } },
        error: null,
      }),
    },
  },
}))

type Role = components['schemas']['Role']

const ORG = '11111111-1111-4111-8111-111111111111'
const PROJECT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const ASHA = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const RAVI = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const BACKEND = '77777777-7777-4777-8777-777777777777'
const QA = '88888888-8888-4888-8888-888888888888'
const LEGACY = '99999999-9999-4999-8999-999999999999'
const PROJECT_PATH = `GET /organizations/${ORG}/projects/${PROJECT}`
const TEAM = `GET /organizations/${ORG}/projects/${PROJECT}/team`
const ASSIGNMENT = `/organizations/${ORG}/projects/${PROJECT}/team/${RAVI}/job-roles/${BACKEND}`

const project = (status = 'ACTIVE') => ({
  id: PROJECT,
  organizationId: ORG,
  name: 'Customer portal',
  description: null,
  status,
  createdBy: ASHA,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z',
  version: 1,
})

const person = (userId: string, displayName: string, role: Role, jobRoleIds: string[] = []) => ({
  userId,
  email: `${displayName.toLowerCase()}@example.com`,
  displayName,
  role,
  jobRoleIds,
})

const jobRole = (id: string, name: string, approvalBasis: string, active = true) => ({
  id,
  name,
  active,
  approvalBasis,
})

const team = (raviRoles: string[] = [], raviAccess: Role = 'MEMBER') => ({
  members: [person(ASHA, 'Asha', 'OWNER'), person(RAVI, 'Ravi', raviAccess, raviRoles)],
  jobRoles: [
    jobRole(BACKEND, 'Backend', raviRoles.includes(BACKEND) && raviAccess !== 'VIEWER' ? 'ROLE_HOLDER' : 'OWNER_FALLBACK'),
    jobRole(LEGACY, 'Legacy', 'OWNER_FALLBACK', false),
    jobRole(QA, 'QA', 'OWNER_FALLBACK'),
  ],
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

type Handler = () => Response

function serve(routes: Record<string, Handler>) {
  const calls: string[] = []
  const all: Record<string, Handler> = {
    [PROJECT_PATH]: () => json(200, project()),
    ...routes,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const { pathname } = new URL(request.url)
      const key = `${request.method} ${pathname.replace('/api/v1', '')}`
      calls.push(key)
      const handler = all[key]
      return handler ? handler() : json(404, { title: 'Not Found', status: 404 })
    }),
  )
  return calls
}

function renderPage(role: Role = 'OWNER') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const org = {
    id: ORG,
    name: 'Acme Labs',
    slug: 'acme-labs',
    allowSelfApproval: true,
    role,
    createdBy: ASHA,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <QueryClientProvider client={client}>
      <CurrentOrgContext.Provider value={org}>
        <MemoryRouter initialEntries={[`/orgs/${ORG}/projects/${PROJECT}/team`]}>
          <Routes>
            <Route path="/orgs/:orgId/projects/:projectId/team" element={<ProjectTeamPage />} />
          </Routes>
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

describe('ProjectTeamPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows people as rows and active job roles as columns', async () => {
    serve({ [TEAM]: () => json(200, team([BACKEND])) })

    renderPage()

    expect(await screen.findByRole('rowheader', { name: /Asha/ })).toBeInTheDocument()
    expect(screen.getByRole('rowheader', { name: /Ravi/ })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Backend' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'QA' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: 'Legacy' })).toBeNull()
    expect(screen.getByRole('checkbox', { name: 'Backend for Ravi' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'QA for Ravi' })).not.toBeChecked()
  })

  it('assigns and removes a job role', async () => {
    let roles: string[] = []
    const calls = serve({
      [TEAM]: () => json(200, team(roles)),
      [`PUT ${ASSIGNMENT}`]: () => {
        roles = [BACKEND]
        return new Response(null, { status: 204 })
      },
      [`DELETE ${ASSIGNMENT}`]: () => {
        roles = []
        return new Response(null, { status: 204 })
      },
    })
    const user = userEvent.setup()

    renderPage('ADMIN')
    await user.click(await screen.findByRole('checkbox', { name: 'Backend for Ravi' }))
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Backend for Ravi' })).toBeChecked(),
    )
    expect(calls).toContain(`PUT ${ASSIGNMENT}`)

    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Backend for Ravi' })).toBeEnabled(),
    )
    await user.click(screen.getByRole('checkbox', { name: 'Backend for Ravi' }))
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: 'Backend for Ravi' })).not.toBeChecked(),
    )
    expect(calls).toContain(`DELETE ${ASSIGNMENT}`)
  })

  it('marks a role without an eligible holder as Owner approval', async () => {
    serve({ [TEAM]: () => json(200, team([BACKEND])) })

    renderPage()

    const row = (await screen.findByRole('rowheader', { name: 'Who approves' })).closest('tr')!
    const cells = within(row).getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('Role holder')
    expect(cells[1]).toHaveTextContent('Owner')
  })

  it('does not count a viewer as an approver', async () => {
    serve({ [TEAM]: () => json(200, team([BACKEND], 'VIEWER')) })

    renderPage()

    const raviRow = (await screen.findByRole('rowheader', { name: /Ravi/ })).closest('tr')!
    expect(raviRow).toHaveTextContent('Viewer, can’t approve')
    expect(screen.getByRole('checkbox', { name: 'Backend for Ravi' })).toBeChecked()
    const row = screen.getByRole('rowheader', { name: 'Who approves' }).closest('tr')!
    expect(within(row).getAllByRole('cell')[0]).toHaveTextContent('Owner')
  })

  it('is read-only for a member', async () => {
    const calls = serve({ [TEAM]: () => json(200, team([BACKEND])) })
    const user = userEvent.setup()

    renderPage('MEMBER')
    const checkbox = await screen.findByRole('checkbox', { name: 'Backend for Ravi' })

    expect(checkbox).toBeDisabled()
    await user.click(checkbox)
    expect(calls.some((call) => call.startsWith('DELETE'))).toBe(false)
  })

  it('is read-only in an archived project', async () => {
    serve({
      [PROJECT_PATH]: () => json(200, project('ARCHIVED')),
      [TEAM]: () => json(200, team()),
    })

    renderPage('OWNER')

    expect(await screen.findByRole('checkbox', { name: 'Backend for Ravi' })).toBeDisabled()
    expect(screen.getByText(/project is archived/i)).toBeInTheDocument()
  })

  it('shows an error and keeps the old value when saving fails', async () => {
    serve({
      [TEAM]: () => json(200, team()),
      [`PUT ${ASSIGNMENT}`]: () =>
        json(422, {
          title: 'Unprocessable',
          status: 422,
          detail: 'The job role is not active in this organization.',
        }),
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('checkbox', { name: 'Backend for Ravi' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/not active/i)
    expect(screen.getByRole('checkbox', { name: 'Backend for Ravi' })).not.toBeChecked()
  })

  it('shows not found for a missing project', async () => {
    serve({
      [PROJECT_PATH]: () => json(404, { title: 'Not Found', status: 404 }),
      [TEAM]: () => json(404, { title: 'Not Found', status: 404 }),
    })

    renderPage()

    expect(
      await screen.findByRole('heading', { name: 'Project not found' }),
    ).toBeInTheDocument()
  })
})
