import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../../AppRoutes'
import { AuthProvider } from '../../auth/AuthProvider'

const auth = vi.hoisted(() => ({
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
  refreshSession: vi.fn(),
  signOut: vi.fn(),
}))

vi.mock('../../auth/supabase', () => ({
  supabase: { auth },
  usePasswordRecovery: () => false,
}))

const ORG = '11111111-1111-4111-8111-111111111111'
const TOKEN_KEY = 'project-brain.pending-invite'
const ACCEPT = 'POST /invites/accept'

const session = {
  access_token: 'token',
  user: { id: 'user-1', email: 'ravi@example.com' },
}

const organization = {
  id: ORG,
  name: 'Acme Labs',
  slug: 'acme-labs',
  allowSelfApproval: true,
  role: 'MEMBER',
  createdBy: 'owner',
  createdAt: '2026-11-22T10:00:00Z',
  updatedAt: '2026-11-22T10:00:00Z',
  version: 0,
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

function serve(routes: Record<string, () => Response>) {
  const calls: { key: string; body: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const { pathname } = new URL(request.url)
      const key = `${request.method} ${pathname.replace('/api/v1', '')}`
      const text = await request.clone().text()
      calls.push({ key, body: text ? JSON.parse(text) : undefined })
      const handler = routes[key]
      return handler ? handler() : json(404, { title: 'Not Found', status: 404 })
    }),
  )
  return calls
}

function CurrentLocation() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname + location.hash}</output>
}

function renderAt(path: string) {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
        <CurrentLocation />
      </MemoryRouter>
    </AuthProvider>,
  )
}

const signedIn = (value: typeof session | null) =>
  auth.getSession.mockResolvedValue({ data: { session: value }, error: null })

describe('InviteAcceptPage', () => {
  beforeEach(() => {
    sessionStorage.clear()
    auth.onAuthStateChange.mockReset()
    auth.onAuthStateChange.mockReturnValue({
      data: { subscription: { unsubscribe: vi.fn() } },
    })
  })

  afterEach(() => vi.unstubAllGlobals())

  it('preserves the invite token through sign-in', async () => {
    signedIn(null)
    const calls = serve({
      [ACCEPT]: () => json(200, { organizationId: ORG }),
      [`GET /organizations/${ORG}`]: () => json(200, organization),
      'GET /organizations': () =>
        json(200, { items: [organization], page: 0, size: 100, totalItems: 1 }),
      [`GET /organizations/${ORG}/projects`]: () =>
        json(200, { items: [], page: 0, size: 20, totalItems: 0 }),
    })
    const user = userEvent.setup()

    renderAt('/invite#token=secret-token')

    expect(await screen.findByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/invite$/))
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('secret-token')
    expect(calls.some((call) => call.key === ACCEPT)).toBe(false)

    const onChange = auth.onAuthStateChange.mock.calls[0][0]
    act(() => onChange('SIGNED_IN', session))
    await user.click(await screen.findByRole('button', { name: 'Accept invite' }))

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(`/orgs/${ORG}`),
    )
    expect(calls.find((call) => call.key === ACCEPT)?.body).toEqual({
      token: 'secret-token',
    })
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull()
  })

  it('returns to the invite after signing up lands on home', async () => {
    signedIn(session)
    sessionStorage.setItem(TOKEN_KEY, 'secret-token')
    serve({})

    renderAt('/')

    expect(await screen.findByRole('button', { name: 'Accept invite' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/invite')
  })

  it('does not accept until the user confirms', async () => {
    signedIn(session)
    const calls = serve({})

    renderAt('/invite#token=secret-token')

    await screen.findByRole('button', { name: 'Accept invite' })
    expect(calls.some((call) => call.key === ACCEPT)).toBe(false)
  })

  it('shows the same invalid state for every failed accept', async () => {
    signedIn(session)
    serve({
      [ACCEPT]: () =>
        json(404, { title: 'Not Found', status: 404, detail: 'This invite is not valid.' }),
    })
    const user = userEvent.setup()

    renderAt('/invite#token=used-token')
    await user.click(await screen.findByRole('button', { name: 'Accept invite' }))

    expect(
      await screen.findByRole('heading', { name: 'This invite isn’t valid' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('ravi@example.com')
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull()
  })

  it('keeps the invite when the connection fails', async () => {
    signedIn(session)
    serve({
      [ACCEPT]: () => json(500, { title: 'Server Error', status: 500 }),
    })
    const user = userEvent.setup()

    renderAt('/invite#token=secret-token')
    await user.click(await screen.findByRole('button', { name: 'Accept invite' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/try again/i)
    expect(screen.getByRole('button', { name: 'Accept invite' })).toBeEnabled()
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe('secret-token')
  })

  it('shows the invalid state without a token', async () => {
    signedIn(session)
    serve({})

    renderAt('/invite')

    expect(
      await screen.findByRole('heading', { name: 'This invite isn’t valid' }),
    ).toBeInTheDocument()
  })
})
