import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { AuthProvider } from '../../auth/AuthProvider'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { TeamPage } from './TeamPage'

const auth = vi.hoisted(() => ({
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
  refreshSession: vi.fn(),
  signOut: vi.fn(),
}))

vi.mock('../../auth/supabase', () => ({ supabase: { auth } }))

type Role = components['schemas']['Role']

const ORG = '11111111-1111-4111-8111-111111111111'
const ME = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const RAVI = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const INVITE = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const MEMBERS = `GET /organizations/${ORG}/members`
const INVITES = `GET /organizations/${ORG}/invites`
const CREATE_INVITE = `POST /organizations/${ORG}/invites`
const CANCEL_INVITE = `DELETE /organizations/${ORG}/invites/${INVITE}`
const RAVI_PATH = `/organizations/${ORG}/members/${RAVI}`

const member = (userId: string, displayName: string, role: Role, version = 0) => ({
  userId,
  email: `${displayName.toLowerCase()}@example.com`,
  displayName,
  role,
  version,
})

const invite = (role: 'ADMIN' | 'MEMBER' | 'VIEWER' = 'MEMBER') => ({
  id: INVITE,
  email: 'new@example.com',
  role,
  status: 'PENDING',
  expiresAt: '2026-11-30T10:00:00Z',
  createdAt: '2026-11-23T10:00:00Z',
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const page = (items: unknown[]) =>
  json(200, { items, page: 0, size: 100, totalItems: items.length })

type Handler = (request: Request) => Response

const jobRole = (id: string, name: string, active = true, version = 0) => ({
  id,
  organizationId: ORG,
  name,
  active,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-20T10:00:00Z',
  version,
})

const JOB_ROLES = `GET /organizations/${ORG}/job-roles`
const BACKEND = '77777777-7777-4777-8777-777777777777'

function serve(routes: Record<string, Handler>) {
  const calls: { key: string; body: unknown }[] = []
  const defaults: Record<string, Handler> = {
    [JOB_ROLES]: () => page([jobRole(BACKEND, 'Backend')]),
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const { pathname } = new URL(request.url)
      const key = `${request.method} ${pathname.replace('/api/v1', '')}`
      const text = await request.clone().text()
      calls.push({ key, body: text ? JSON.parse(text) : undefined })
      const handler = routes[key] ?? defaults[key]
      return handler
        ? handler(request)
        : json(404, { title: 'Not Found', status: 404 })
    }),
  )
  return calls
}

function renderPage(role: Role, allowSelfApproval = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const org = {
    id: ORG,
    name: 'Acme Labs',
    slug: 'acme-labs',
    allowSelfApproval,
    role,
    createdBy: ME,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <AuthProvider>
      <QueryClientProvider client={client}>
        <CurrentOrgContext.Provider value={org}>
          <MemoryRouter>
            <TeamPage />
          </MemoryRouter>
        </CurrentOrgContext.Provider>
      </QueryClientProvider>
    </AuthProvider>,
  )
}

describe('TeamPage', () => {
  beforeEach(() => {
    auth.getSession.mockResolvedValue({
      data: { session: { access_token: 'token', user: { id: ME, email: 'me@example.com' } } },
      error: null,
    })
    auth.onAuthStateChange.mockReturnValue({
      data: { subscription: { unsubscribe: vi.fn() } },
    })
  })

  afterEach(() => vi.unstubAllGlobals())

  it('shows the invite link once with a copy action', async () => {
    const invites: unknown[] = []
    const calls = serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER')]),
      [INVITES]: () => page([...invites]),
      [CREATE_INVITE]: () => {
        invites.push(invite())
        return json(201, { ...invite(), token: 'secret-token' })
      },
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.click(await screen.findByRole('button', { name: 'Invite person' }))
    const dialog = screen.getByRole('dialog', { name: 'Invite a person' })
    await user.type(within(dialog).getByLabelText('Email'), 'new@example.com')
    await user.click(within(dialog).getByRole('button', { name: 'Create invite link' }))

    const link = `${window.location.origin}/invite#token=secret-token`
    expect(await within(dialog).findByLabelText('Invite link')).toHaveValue(link)
    expect(calls.find((call) => call.key === CREATE_INVITE)?.body).toEqual({
      email: 'new@example.com',
      role: 'MEMBER',
    })
    await user.click(within(dialog).getByRole('button', { name: 'Copy link' }))
    await expect(navigator.clipboard.readText()).resolves.toBe(link)
    expect(within(dialog).getByRole('status')).toHaveTextContent('Copied')

    await user.click(within(dialog).getByRole('button', { name: 'Done' }))

    expect(await screen.findByText('new@example.com')).toBeInTheDocument()
    expect(screen.queryByDisplayValue(link)).toBeNull()
    expect(document.body).not.toHaveTextContent('secret-token')
  })

  it('limits an admin to Member and Viewer invites', async () => {
    serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'ADMIN')]),
      [INVITES]: () => page([]),
    })
    const user = userEvent.setup()

    renderPage('ADMIN')
    await user.click(await screen.findByRole('button', { name: 'Invite person' }))
    const options = within(screen.getByLabelText('Access role')).getAllByRole('option')

    expect(options.map((option) => option.textContent)).toEqual(['Member', 'Viewer'])
  })

  it('offers an owner Admin invites', async () => {
    serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER')]),
      [INVITES]: () => page([]),
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.click(await screen.findByRole('button', { name: 'Invite person' }))
    const options = within(screen.getByLabelText('Access role')).getAllByRole('option')

    expect(options.map((option) => option.textContent)).toEqual(['Admin', 'Member', 'Viewer'])
  })

  it('hides role changes and removal from an admin', async () => {
    serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'ADMIN'), member(RAVI, 'Ravi', 'MEMBER')]),
      [INVITES]: () => page([invite('ADMIN')]),
    })

    renderPage('ADMIN')

    expect(await screen.findByText('Ravi')).toBeInTheDocument()
    expect(await screen.findByText('new@example.com')).toBeInTheDocument()
    expect(screen.queryByLabelText('Access role for Ravi')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Remove Ravi' })).toBeNull()
    expect(
      screen.queryByRole('button', { name: 'Cancel invite for new@example.com' }),
    ).toBeNull()
  })

  it('does not load invites for a member', async () => {
    const calls = serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'MEMBER')]),
    })

    renderPage('MEMBER')

    expect(await screen.findByText('Asha')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Invite person' })).toBeNull()
    expect(calls.some((call) => call.key === INVITES)).toBe(false)
  })

  it('cancels a pending invite', async () => {
    let invites = [invite()]
    const calls = serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER')]),
      [INVITES]: () => page(invites),
      [CANCEL_INVITE]: () => {
        invites = []
        return new Response(null, { status: 204 })
      },
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.click(
      await screen.findByRole('button', { name: 'Cancel invite for new@example.com' }),
    )

    await waitFor(() => expect(screen.queryByText('new@example.com')).toBeNull())
    expect(calls.some((call) => call.key === CANCEL_INVITE)).toBe(true)
  })

  it('changes a role with the member version', async () => {
    const calls = serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER'), member(RAVI, 'Ravi', 'MEMBER', 2)]),
      [INVITES]: () => page([]),
      [`PATCH ${RAVI_PATH}`]: () => json(200, member(RAVI, 'Ravi', 'VIEWER', 3)),
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.selectOptions(await screen.findByLabelText('Access role for Ravi'), 'Viewer')

    await waitFor(() =>
      expect(calls.find((call) => call.key === `PATCH ${RAVI_PATH}`)?.body).toEqual({
        role: 'VIEWER',
        version: 2,
      }),
    )
  })

  it('confirms before removing a member', async () => {
    let members = [member(ME, 'Asha', 'OWNER'), member(RAVI, 'Ravi', 'MEMBER')]
    const calls = serve({
      [MEMBERS]: () => page(members),
      [INVITES]: () => page([]),
      [`DELETE ${RAVI_PATH}`]: () => {
        members = [member(ME, 'Asha', 'OWNER')]
        return new Response(null, { status: 204 })
      },
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.click(await screen.findByRole('button', { name: 'Remove Ravi' }))
    const confirm = screen.getByRole('dialog', { name: 'Remove Ravi?' })
    expect(calls.some((call) => call.key === `DELETE ${RAVI_PATH}`)).toBe(false)
    await user.click(within(confirm).getByRole('button', { name: 'Keep member' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls.some((call) => call.key === `DELETE ${RAVI_PATH}`)).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Remove Ravi' }))
    await user.click(
      within(screen.getByRole('dialog', { name: 'Remove Ravi?' })).getByRole('button', {
        name: 'Remove member',
      }),
    )

    await waitFor(() => expect(screen.queryByText('Ravi')).toBeNull())
  })

  it('protects the last owner', async () => {
    serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER'), member(RAVI, 'Ravi', 'MEMBER')]),
      [INVITES]: () => page([]),
    })

    renderPage('OWNER')

    expect(await screen.findByLabelText('Access role for Asha')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Remove Asha' })).toBeNull()
  })

  it('lets an owner change self-approval', async () => {
    const calls = serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER')]),
      [INVITES]: () => page([]),
      [`PATCH /organizations/${ORG}/settings`]: () => json(200, {}),
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.click(await screen.findByRole('checkbox', { name: 'Allow self-approval' }))

    await waitFor(() =>
      expect(calls.find((call) => call.key === `PATCH /organizations/${ORG}/settings`)?.body).toEqual({
        allowSelfApproval: false,
        version: 0,
      }),
    )
  })

  it('hides settings and job role management from an admin', async () => {
    serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'ADMIN')]),
      [INVITES]: () => page([]),
    })

    renderPage('ADMIN')

    await screen.findByText('Asha')
    expect(screen.queryByRole('checkbox', { name: 'Allow self-approval' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Add job role' })).toBeNull()
  })

  it('lets an owner create, rename and deactivate job roles', async () => {
    let roles = [jobRole(BACKEND, 'Backend', true, 2)]
    const path = `/organizations/${ORG}/job-roles`
    const calls = serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER')]),
      [INVITES]: () => page([]),
      [JOB_ROLES]: () => page(roles),
      [`POST ${path}`]: () => {
        const created = jobRole('66666666-6666-4666-8666-666666666666', 'Designer')
        roles = [...roles, created]
        return json(201, created)
      },
      [`PATCH ${path}/${BACKEND}`]: () => {
        roles = [jobRole(BACKEND, 'Platform', false, 3), ...roles.slice(1)]
        return json(200, roles[0])
      },
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.type(await screen.findByLabelText('New job role'), '  Designer ')
    await user.click(screen.getByRole('button', { name: 'Add job role' }))
    expect(await screen.findByLabelText('Name for Designer')).toHaveValue('Designer')
    expect(calls.find((call) => call.key === `POST ${path}`)?.body).toEqual({ name: 'Designer' })

    const name = screen.getByLabelText('Name for Backend')
    await user.clear(name)
    await user.type(name, 'Platform')
    await user.click(screen.getByRole('button', { name: 'Save Backend' }))
    await waitFor(() =>
      expect(calls.find((call) => call.key === `PATCH ${path}/${BACKEND}`)?.body).toEqual({
        name: 'Platform',
        version: 2,
      }),
    )
    expect(await screen.findByRole('button', { name: 'Reactivate Platform' })).toBeInTheDocument()
  })

  it('deactivates a job role with its version', async () => {
    const path = `/organizations/${ORG}/job-roles/${BACKEND}`
    const calls = serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER')]),
      [INVITES]: () => page([]),
      [JOB_ROLES]: () => page([jobRole(BACKEND, 'Backend', true, 4)]),
      [`PATCH ${path}`]: () => json(200, jobRole(BACKEND, 'Backend', false, 5)),
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.click(await screen.findByRole('button', { name: 'Deactivate Backend' }))

    await waitFor(() =>
      expect(calls.find((call) => call.key === `PATCH ${path}`)?.body).toEqual({
        active: false,
        version: 4,
      }),
    )
  })

  it('shows an API error when a role change fails', async () => {
    serve({
      [MEMBERS]: () => page([member(ME, 'Asha', 'OWNER'), member(RAVI, 'Ravi', 'MEMBER')]),
      [INVITES]: () => page([]),
      [`PATCH ${RAVI_PATH}`]: () =>
        json(409, {
          title: 'Conflict',
          status: 409,
          detail: 'The member was changed by someone else. Reload and try again.',
        }),
    })
    const user = userEvent.setup()

    renderPage('OWNER')
    await user.selectOptions(await screen.findByLabelText('Access role for Ravi'), 'Viewer')

    expect(await screen.findByRole('alert')).toHaveTextContent(/changed by someone else/i)
  })
})
