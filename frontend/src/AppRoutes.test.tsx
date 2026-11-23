import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from './AppRoutes'
import { AuthProvider } from './auth/AuthProvider'

const auth = vi.hoisted(() => ({
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
  refreshSession: vi.fn(),
  signOut: vi.fn(),
}))

vi.mock('./auth/supabase', () => ({
  supabase: { auth },
  usePasswordRecovery: () => false,
}))

const ORG_A = '11111111-1111-4111-8111-111111111111'
const ORG_B = '22222222-2222-4222-8222-222222222222'

const organization = (id: string, name: string, slug: string) => ({
  id,
  name,
  slug,
  allowSelfApproval: true,
  role: 'OWNER',
  createdBy: '99999999-9999-4999-8999-999999999999',
  createdAt: '2026-11-22T10:00:00Z',
  updatedAt: '2026-11-22T10:00:00Z',
  version: 0,
})

const acme = organization(ORG_A, 'Acme Labs', 'acme-labs')
const globex = organization(ORG_B, 'Globex', 'globex')

const sessionFor = (id: string) => ({
  access_token: `token-${id}`,
  user: { id, email: `${id}@example.com` },
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const page = (items: unknown[]) => () =>
  json(200, { items, page: 0, size: 100, totalItems: items.length })

const problem = (status: number, title: string, extra = {}) =>
  json(status, { title, status, ...extra })

type Handler = (request: Request) => Response
type Routes = Record<string, Handler>

function serve(routes: Routes) {
  const fetchFake = vi.fn(async (request: Request) => {
    const { pathname } = new URL(request.url)
    const key = `${request.method} ${pathname.replace('/api/v1', '')}`
    const handler = routes[key]
    return handler ? handler(request) : problem(404, 'Not Found')
  })
  vi.stubGlobal('fetch', fetchFake)
  return fetchFake
}

function CurrentPath() {
  return <output data-testid="path">{useLocation().pathname}</output>
}

function renderAt(path: string) {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
        <CurrentPath />
      </MemoryRouter>
    </AuthProvider>,
  )
}

const expectPath = (path: string) =>
  waitFor(() => expect(screen.getByTestId('path')).toHaveTextContent(path))

describe('organization routes', () => {
  beforeEach(() => {
    auth.getSession.mockResolvedValue({
      data: { session: sessionFor('user-a') },
      error: null,
    })
    auth.onAuthStateChange.mockReturnValue({
      data: { subscription: { unsubscribe: vi.fn() } },
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    auth.onAuthStateChange.mockReset()
  })

  it('sends a user with no organization to onboarding', async () => {
    serve({ 'GET /organizations': page([]) })

    renderAt('/')

    await expectPath('/orgs/new')
    expect(
      await screen.findByRole('heading', { name: 'Create your organization' }),
    ).toBeInTheDocument()
  })

  it('opens the first organization for an existing member', async () => {
    serve({
      'GET /organizations': page([acme, globex]),
      [`GET /organizations/${ORG_A}`]: () => json(200, acme),
    })

    renderAt('/')

    await expectPath(`/orgs/${ORG_A}`)
    expect(
      await screen.findByRole('heading', { name: 'Acme Labs' }),
    ).toBeInTheDocument()
  })

  it('shows a slug conflict on the form', async () => {
    serve({
      'GET /organizations': page([]),
      'POST /organizations': () =>
        problem(409, 'Conflict', { detail: 'That slug is already in use.' }),
    })
    const user = userEvent.setup()

    renderAt('/orgs/new')
    await user.type(
      await screen.findByLabelText('Organization name'),
      'Acme Labs',
    )
    await user.click(screen.getByRole('button', { name: 'Create organization' }))

    const slug = screen.getByLabelText('Short name')
    await waitFor(() =>
      expect(slug).toHaveAccessibleDescription(/already in use/i),
    )
    expect(slug).toHaveAttribute('aria-invalid', 'true')
    await expectPath('/orgs/new')
  })

  it('shows server validation messages on their fields', async () => {
    serve({
      'GET /organizations': page([]),
      'POST /organizations': () =>
        problem(422, 'Unprocessable', {
          errors: { name: ['Name is not allowed.'] },
        }),
    })
    const user = userEvent.setup()

    renderAt('/orgs/new')
    await user.type(await screen.findByLabelText('Organization name'), 'Acme')
    await user.click(screen.getByRole('button', { name: 'Create organization' }))

    await waitFor(() =>
      expect(
        screen.getByLabelText('Organization name'),
      ).toHaveAccessibleDescription('Name is not allowed.'),
    )
  })

  it('suggests a short name from the name until the user edits it', async () => {
    serve({ 'GET /organizations': page([]) })
    const user = userEvent.setup()

    renderAt('/orgs/new')
    const name = await screen.findByLabelText('Organization name')
    const slug = screen.getByLabelText('Short name')
    await user.type(name, 'Café & Co.  Labs')
    expect(slug).toHaveValue('cafe-co-labs')

    await user.clear(slug)
    await user.type(slug, 'my-team')
    await user.type(name, ' Two')
    expect(slug).toHaveValue('my-team')
  })

  it('does not submit an invalid short name', async () => {
    const fetchFake = serve({ 'GET /organizations': page([]) })
    const user = userEvent.setup()

    renderAt('/orgs/new')
    await user.type(await screen.findByLabelText('Organization name'), 'Acme')
    const slug = screen.getByLabelText('Short name')
    await user.clear(slug)
    await user.type(slug, 'a-')
    await user.click(screen.getByRole('button', { name: 'Create organization' }))

    expect(slug).toHaveAttribute('aria-invalid', 'true')
    expect(
      fetchFake.mock.calls.some(([request]) => request.method === 'POST'),
    ).toBe(false)
  })

  it('opens a new organization after creating it', async () => {
    const organizations: unknown[] = []
    const fetchFake = serve({
      'GET /organizations': () => page(organizations)(),
      'POST /organizations': () => {
        organizations.push(acme)
        return json(201, acme)
      },
      [`GET /organizations/${ORG_A}`]: () => json(200, acme),
    })
    const user = userEvent.setup()

    renderAt('/orgs/new')
    await user.type(
      await screen.findByLabelText('Organization name'),
      'Acme Labs',
    )
    await user.click(screen.getByRole('button', { name: 'Create organization' }))

    await expectPath(`/orgs/${ORG_A}`)
    expect(
      await screen.findByRole('option', { name: 'Acme Labs' }),
    ).toBeInTheDocument()
    const post = fetchFake.mock.calls
      .map(([request]) => request)
      .find((request) => request.method === 'POST')
    expect(post).toBeDefined()
  })

  it.each([
    ['inaccessible', ORG_B],
    ['malformed', 'not-a-uuid'],
  ])('shows not found for an %s organization', async (_label, orgId) => {
    serve({
      'GET /organizations': page([acme]),
      [`GET /organizations/not-a-uuid`]: () => problem(400, 'Bad Request'),
    })

    renderAt(`/orgs/${orgId}`)

    expect(
      await screen.findByRole('heading', { name: 'Organization not found' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to your organizations' }))
      .toHaveAttribute('href', '/')
  })

  it('offers a retry when an organization fails to load', async () => {
    let attempts = 0
    serve({
      'GET /organizations': page([acme]),
      [`GET /organizations/${ORG_A}`]: () => {
        attempts += 1
        return attempts === 1
          ? problem(500, 'Server Error')
          : json(200, acme)
      },
    })
    const user = userEvent.setup()

    renderAt(`/orgs/${ORG_A}`)
    await user.click(await screen.findByRole('button', { name: 'Try again' }))

    expect(
      await screen.findByRole('heading', { name: 'Acme Labs' }),
    ).toBeInTheDocument()
  })

  it('shows audit navigation to an admin', async () => {
    const admin = { ...acme, role: 'ADMIN' }
    serve({
      'GET /organizations': page([admin]),
      [`GET /organizations/${ORG_A}`]: () => json(200, admin),
    })

    renderAt(`/orgs/${ORG_A}`)

    expect(await screen.findByRole('link', { name: 'Audit log' })).toHaveAttribute(
      'href',
      `/orgs/${ORG_A}/audit`,
    )
  })

  it('hides audit navigation from a member', async () => {
    const memberOrg = { ...acme, role: 'MEMBER' }
    serve({
      'GET /organizations': page([memberOrg]),
      [`GET /organizations/${ORG_A}`]: () => json(200, memberOrg),
    })

    renderAt(`/orgs/${ORG_A}`)

    expect(await screen.findByRole('link', { name: 'Projects' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Audit log' })).toBeNull()
  })

  it('shows the My reviews count in the sidebar', async () => {
    serve({
      'GET /organizations': page([acme]),
      [`GET /organizations/${ORG_A}`]: () => json(200, acme),
      [`GET /organizations/${ORG_A}/reviews/mine`]: () =>
        json(200, { items: [], page: 0, size: 1, totalItems: 3 }),
    })

    renderAt(`/orgs/${ORG_A}`)

    const link = await screen.findByRole('link', { name: 'My reviews, 3 waiting' })
    expect(link).toHaveAttribute('href', `/orgs/${ORG_A}/reviews`)
  })

  it('switches organizations from the sidebar', async () => {
    serve({
      'GET /organizations': page([acme, globex]),
      [`GET /organizations/${ORG_A}`]: () => json(200, acme),
      [`GET /organizations/${ORG_B}`]: () => json(200, globex),
    })
    const user = userEvent.setup()

    renderAt(`/orgs/${ORG_A}`)
    await screen.findByRole('option', { name: 'Globex' })
    await user.selectOptions(
      screen.getByLabelText('Organization'),
      'Globex',
    )

    await expectPath(`/orgs/${ORG_B}`)
    expect(
      await screen.findByRole('heading', { name: 'Globex' }),
    ).toBeInTheDocument()
  })

  it("does not show the previous user's organization after a user change", async () => {
    const routes: Routes = {
      'GET /organizations': page([acme]),
      [`GET /organizations/${ORG_A}`]: () => json(200, acme),
    }
    serve(routes)

    renderAt(`/orgs/${ORG_A}`)
    await screen.findByRole('heading', { name: 'Acme Labs' })

    routes['GET /organizations'] = page([])
    routes[`GET /organizations/${ORG_A}`] = () => problem(404, 'Not Found')
    const onChange = auth.onAuthStateChange.mock.calls[0][0]
    act(() => onChange('SIGNED_IN', sessionFor('user-b')))

    expect(
      await screen.findByRole('heading', { name: 'Organization not found' }),
    ).toBeInTheDocument()
    expect(screen.queryByText('Acme Labs')).not.toBeInTheDocument()
  })
})
