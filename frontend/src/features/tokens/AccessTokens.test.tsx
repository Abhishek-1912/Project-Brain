import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { AccessTokensPage } from './AccessTokensPage'

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

const USER = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const ORG = '11111111-1111-4111-8111-111111111111'
const TOKEN_ID = '22222222-2222-4222-8222-222222222222'
const SECRET = 'pb_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ'
const API = `/organizations/${ORG}/access-tokens`

const token = (overrides: Record<string, unknown> = {}) => ({
  id: TOKEN_ID,
  name: 'Claude Code laptop',
  scope: 'READ_WRITE',
  displayPrefix: 'pb_abcde',
  expiresAt: '2026-12-26T10:00:00Z',
  createdAt: '2026-11-26T10:00:00Z',
  lastUsedAt: null,
  revokedAt: null,
  status: 'ACTIVE',
  ...overrides,
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Handler = (request: Request) => Response | Promise<Response>

function serve(routes: Record<string, Handler>) {
  const calls: string[] = []
  const all: Record<string, Handler> = {
    [`GET ${API}`]: () => json(200, { items: [token()] }),
    ...routes,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const { pathname } = new URL(request.url)
      const key = `${request.method} ${pathname.replace('/api/v1', '')}`
      calls.push(key)
      const handler = all[key]
      return handler ? handler(request) : json(404, { title: 'Not Found', status: 404 })
    }),
  )
  return calls
}

function renderPage(role: Role = 'MEMBER') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const org = {
    id: ORG,
    name: 'Acme Labs',
    slug: 'acme-labs',
    allowSelfApproval: true,
    role,
    createdBy: USER,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <QueryClientProvider client={client}>
      <CurrentOrgContext.Provider value={org}>
        <MemoryRouter initialEntries={[`/orgs/${ORG}/access-tokens`]}>
          <Routes>
            <Route path="/orgs/:orgId/access-tokens" element={<AccessTokensPage />} />
          </Routes>
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

describe('access tokens', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lists your tokens with their scope and status', async () => {
    serve({
      [`GET ${API}`]: () =>
        json(200, {
          items: [
            token(),
            token({ id: crypto.randomUUID(), name: 'Old CI', scope: 'READ', status: 'REVOKED', revokedAt: '2026-11-25T10:00:00Z' }),
          ],
        }),
    })
    renderPage()

    const rows = await screen.findAllByRole('listitem')
    expect(within(rows[0]).getByText('Claude Code laptop')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Read and write')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Active')).toBeInTheDocument()
    expect(within(rows[0]).getByText(/pb_abcde/)).toBeInTheDocument()
    expect(within(rows[1]).getByText('Revoked')).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
  })

  it('creates a token and shows the secret once with a setup command', async () => {
    const user = userEvent.setup()
    let sent: unknown
    serve({
      [`POST ${API}`]: async (request) => {
        sent = await request.json()
        return json(201, { ...token({ name: 'Laptop', scope: 'READ' }), token: SECRET })
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Create token' }))
    const dialog = screen.getByRole('dialog', { name: 'Create token' })
    await user.type(within(dialog).getByLabelText('Name'), '  Laptop  ')
    await user.click(within(dialog).getByLabelText('Read only'))
    await user.selectOptions(within(dialog).getByLabelText('Expires after'), '7')
    await user.click(within(dialog).getByRole('button', { name: 'Create token' }))

    const created = await screen.findByRole('dialog', { name: 'Copy your token' })
    expect(within(created).getByText(SECRET)).toBeInTheDocument()
    expect(within(created).getByText(/claude mcp add --transport http project-brain .*\/mcp/)).toHaveTextContent(SECRET)
    expect(within(created).getByText(/won’t be shown again/)).toBeInTheDocument()
    expect(sent).toEqual({ name: 'Laptop', scope: 'READ', expiresInDays: 7 })

    await user.click(within(created).getByRole('button', { name: 'Copy token' }))
    expect(await navigator.clipboard.readText()).toBe(SECRET)

    await user.click(within(created).getByRole('button', { name: 'Done' }))
    expect(screen.queryByText(SECRET)).not.toBeInTheDocument()
  })

  it('asks for a name before creating', async () => {
    const user = userEvent.setup()
    const calls = serve({})
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Create token' }))
    const dialog = screen.getByRole('dialog', { name: 'Create token' })
    await user.click(within(dialog).getByRole('button', { name: 'Create token' }))

    expect(within(dialog).getByText('Enter a name.')).toBeInTheDocument()
    expect(calls).not.toContain(`POST ${API}`)
  })

  it('explains the ten-token limit', async () => {
    const user = userEvent.setup()
    serve({
      [`POST ${API}`]: () =>
        json(409, { title: 'Conflict', status: 409, detail: 'You already have 10 active tokens here. Revoke one first.' }),
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Create token' }))
    const dialog = screen.getByRole('dialog', { name: 'Create token' })
    await user.type(within(dialog).getByLabelText('Name'), 'Eleventh')
    await user.click(within(dialog).getByRole('button', { name: 'Create token' }))

    expect(await within(dialog).findByText(/10 active tokens/)).toBeInTheDocument()
  })

  it('revokes after confirmation and refreshes the list', async () => {
    const user = userEvent.setup()
    let revoked = false
    const calls = serve({
      [`GET ${API}`]: () =>
        json(200, { items: [revoked ? token({ status: 'REVOKED', revokedAt: '2026-11-26T11:00:00Z' }) : token()] }),
      [`POST ${API}/${TOKEN_ID}/revoke`]: () => {
        revoked = true
        return new Response(null, { status: 204 })
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Revoke' }))
    const dialog = screen.getByRole('dialog', { name: 'Revoke token?' })
    expect(within(dialog).getByText(/stop working immediately/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Revoke token' }))

    expect(await screen.findByText('Revoked')).toBeInTheDocument()
    expect(calls).toContain(`POST ${API}/${TOKEN_ID}/revoke`)
  })

  it('explains what tokens are for when there are none', async () => {
    serve({ [`GET ${API}`]: () => json(200, { items: [] }) })
    renderPage('VIEWER')

    expect(await screen.findByText('No access tokens yet')).toBeInTheDocument()
    expect(screen.getByText(/to connect Claude Code/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Create token' })).toBeEnabled())
  })
})
