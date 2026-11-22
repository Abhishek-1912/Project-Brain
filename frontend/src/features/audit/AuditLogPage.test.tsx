import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { AuditLogPage } from './AuditLogPage'

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
const ASHA = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const GONE = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const ENTITY = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const AUDIT = `GET /organizations/${ORG}/audit-events`
const MEMBERS = `GET /organizations/${ORG}/members`

const auditEvent = (id: number, overrides = {}) => ({
  id: `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
  organizationId: ORG,
  actorId: ASHA,
  action: 'PROJECT_UPDATED',
  entityType: 'PROJECT',
  entityId: ENTITY,
  metadata: { name: 'Customer portal', changedFields: ['name', 'status'] },
  createdAt: '2026-11-22T10:00:00Z',
  ...overrides,
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const page = (items: unknown[], number = 0, totalItems = items.length) =>
  json(200, { items, page: number, size: 20, totalItems })

type Handler = (request: Request) => Response

function serve(routes: Record<string, Handler>) {
  const calls: { key: string; url: URL }[] = []
  const all: Record<string, Handler> = {
    [MEMBERS]: () =>
      page([
        { userId: ASHA, email: 'asha@example.com', displayName: 'Asha', role: 'ADMIN' },
      ]),
    ...routes,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url)
      const key = `${request.method} ${url.pathname.replace('/api/v1', '')}`
      calls.push({ key, url })
      const handler = all[key]
      return handler
        ? handler(request)
        : json(404, { title: 'Not Found', status: 404 })
    }),
  )
  return calls
}

function CurrentSearch() {
  return <output data-testid="search">{useLocation().search}</output>
}

function renderPage(role: Role = 'ADMIN', search = '') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const org = {
    id: ORG,
    name: 'Acme Labs',
    slug: 'acme-labs',
    role,
    createdBy: ASHA,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <QueryClientProvider client={client}>
      <CurrentOrgContext.Provider value={org}>
        <MemoryRouter initialEntries={[`/orgs/${ORG}/audit${search}`]}>
          <Routes>
            <Route path="/orgs/:orgId/audit" element={<AuditLogPage />} />
          </Routes>
          <CurrentSearch />
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

const auditCalls = (calls: ReturnType<typeof serve>) =>
  calls.filter((call) => call.key === AUDIT)

describe('AuditLogPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows audit events to an admin', async () => {
    serve({ [AUDIT]: () => page([auditEvent(1)]) })

    renderPage('ADMIN')

    const row = (await screen.findByText('Project updated')).closest('tr')!
    expect(within(row).getByText('Customer portal')).toBeInTheDocument()
    expect(within(row).getByText('Changed name, status')).toBeInTheDocument()
    expect(await within(row).findByText('Asha')).toBeInTheDocument()
    expect(row.querySelector('time')).toHaveAttribute(
      'datetime',
      '2026-11-22T10:00:00Z',
    )
  })

  it('shows a deleted task by its title', async () => {
    serve({
      [AUDIT]: () =>
        page([
          auditEvent(1, {
            action: 'TASK_DELETED',
            entityType: 'TASK',
            metadata: { title: 'Write docs', projectId: ENTITY },
          }),
        ]),
    })

    renderPage()

    const row = (await screen.findByText('Task deleted')).closest('tr')!
    expect(within(row).getByText('Write docs')).toBeInTheDocument()
  })

  it('shows the previous name of a renamed organization', async () => {
    serve({
      [AUDIT]: () =>
        page([
          auditEvent(1, {
            action: 'ORGANIZATION_UPDATED',
            entityType: 'ORGANIZATION',
            metadata: { name: 'Acme Labs', previousName: 'Acme' },
          }),
        ]),
    })

    renderPage()

    const row = (await screen.findByText('Organization updated')).closest('tr')!
    expect(within(row).getByText('Acme Labs')).toBeInTheDocument()
    expect(within(row).getByText('Renamed from Acme')).toBeInTheDocument()
  })

  it('keeps unknown actions and actors readable', async () => {
    serve({
      [AUDIT]: () =>
        page([auditEvent(1, { action: 'MEMBER_ROLE_CHANGED', actorId: GONE, metadata: {} })]),
    })

    renderPage()

    const row = (await screen.findByText('Member role changed')).closest('tr')!
    expect(await within(row).findByText('Unknown member')).toBeInTheDocument()
  })

  it('shows empty state', async () => {
    serve({ [AUDIT]: () => page([]) })

    renderPage()

    expect(
      await screen.findByRole('heading', { name: 'No activity yet' }),
    ).toBeInTheDocument()
  })

  it.each<Role>(['MEMBER', 'VIEWER'])(
    'does not load audit events for a %s',
    async (role) => {
      const calls = serve({ [AUDIT]: () => page([auditEvent(1)]) })

      renderPage(role)

      expect(
        await screen.findByRole('heading', { name: 'You can’t view the audit log' }),
      ).toBeInTheDocument()
      expect(auditCalls(calls)).toHaveLength(0)
      expect(screen.queryByText('Project updated')).toBeNull()
    },
  )

  it('shows no access when the API refuses', async () => {
    serve({ [AUDIT]: () => json(403, { title: 'Forbidden', status: 403 }) })

    renderPage('ADMIN')

    expect(
      await screen.findByRole('heading', { name: 'You can’t view the audit log' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull()
  })

  it('paginates through the URL', async () => {
    const calls = serve({
      [AUDIT]: (request) =>
        new URL(request.url).searchParams.get('page') === '1'
          ? page([auditEvent(2, { action: 'PROJECT_CREATED' })], 1, 21)
          : page([auditEvent(1)], 0, 21),
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('Project updated')
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next page' }))

    expect(await screen.findByText('Project created')).toBeInTheDocument()
    expect(screen.getByTestId('search')).toHaveTextContent('?page=2')
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(auditCalls(calls).at(-1)?.url.searchParams.get('page')).toBe('1')
  })

  it('moves back when the requested page is empty', async () => {
    const calls = serve({
      [AUDIT]: (request) =>
        new URL(request.url).searchParams.get('page') === '4'
          ? page([], 4, 1)
          : page([auditEvent(1)], 0, 1),
    })

    renderPage('ADMIN', '?page=5')

    expect(await screen.findByText('Project updated')).toBeInTheDocument()
    expect(auditCalls(calls).at(-1)?.url.searchParams.get('page')).toBe('0')
  })

  it('offers a retry when events fail to load', async () => {
    let attempts = 0
    serve({
      [AUDIT]: () => {
        attempts += 1
        return attempts === 1
          ? json(500, { title: 'Server Error', status: 500 })
          : page([auditEvent(1)])
      },
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Project updated')).toBeInTheDocument()
  })
})
