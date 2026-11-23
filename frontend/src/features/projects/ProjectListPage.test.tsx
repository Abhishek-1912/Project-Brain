import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { ProjectListPage } from './ProjectListPage'

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
const ALPHA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const BETA = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const LIST = `GET /organizations/${ORG}/projects`
const CREATE = `POST /organizations/${ORG}/projects`
const UPDATE_ALPHA = `PATCH /organizations/${ORG}/projects/${ALPHA}`

const project = (id: string, name: string, overrides = {}) => ({
  id,
  organizationId: ORG,
  name,
  description: null,
  status: 'ACTIVE',
  createdBy: '99999999-9999-4999-8999-999999999999',
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z',
  version: 3,
  ...overrides,
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const page = (items: unknown[], number = 0, totalItems = items.length) =>
  json(200, { items, page: number, size: 20, totalItems })

const conflict = (detail: string) =>
  json(409, { title: 'Conflict', status: 409, detail })

type Handler = (request: Request) => Response | Promise<Response>

function serve(routes: Record<string, Handler>) {
  const calls: { key: string; url: URL; body: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url)
      const key = `${request.method} ${url.pathname.replace('/api/v1', '')}`
      const text = await request.clone().text()
      calls.push({ key, url, body: text ? JSON.parse(text) : undefined })
      const handler = routes[key]
      return handler
        ? handler(request)
        : json(404, { title: 'Not Found', status: 404 })
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
    createdBy: '99999999-9999-4999-8999-999999999999',
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <QueryClientProvider client={client}>
      <CurrentOrgContext.Provider value={org}>
        <MemoryRouter>
          <ProjectListPage />
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

describe('ProjectListPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows empty state', async () => {
    serve({ [LIST]: () => page([]) })

    renderPage()

    expect(
      await screen.findByRole('heading', { name: 'No projects yet' }),
    ).toBeInTheDocument()
    expect(
      screen.getAllByRole('button', { name: 'Create project' }).length,
    ).toBeGreaterThan(0)
  })

  it.each<Role>(['MEMBER', 'VIEWER'])('hides create for a %s', async (role) => {
    serve({ [LIST]: () => page([project(ALPHA, 'Alpha')]) })

    renderPage(role)

    expect(await screen.findByText('Alpha')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create project' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Edit Alpha' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Archive Alpha' })).toBeNull()
  })

  it('links each project to its tasks', async () => {
    serve({ [LIST]: () => page([project(ALPHA, 'Alpha')]) })

    renderPage('VIEWER')

    expect(await screen.findByRole('link', { name: 'Alpha' })).toHaveAttribute(
      'href',
      `/orgs/${ORG}/projects/${ALPHA}`,
    )
  })

  it('tells a member who can create the first project', async () => {
    serve({ [LIST]: () => page([]) })

    renderPage('MEMBER')

    expect(await screen.findByText(/owner or admin/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Create project' })).toBeNull()
  })

  it('shows duplicate-name error', async () => {
    serve({
      [LIST]: () => page([project(ALPHA, 'Alpha')]),
      [CREATE]: () => conflict('A project with that name already exists.'),
    })
    const user = userEvent.setup()

    renderPage('ADMIN')
    await user.click(await screen.findByRole('button', { name: 'Create project' }))
    const dialog = screen.getByRole('dialog', { name: 'Create project' })
    await user.type(within(dialog).getByLabelText('Project name'), 'alpha')
    await user.click(within(dialog).getByRole('button', { name: 'Create project' }))

    const name = within(dialog).getByLabelText('Project name')
    await waitFor(() =>
      expect(name).toHaveAccessibleDescription(/already exists/i),
    )
    expect(name).toHaveAttribute('aria-invalid', 'true')
  })

  it('creates a project and refreshes the list', async () => {
    const projects: unknown[] = []
    const calls = serve({
      [LIST]: () => page([...projects]),
      [CREATE]: () => {
        const created = project(BETA, 'Beta')
        projects.push(created)
        return json(201, created)
      },
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByRole('heading', { name: 'No projects yet' })
    await user.click(screen.getAllByRole('button', { name: 'Create project' })[0])
    const dialog = screen.getByRole('dialog', { name: 'Create project' })
    await user.type(within(dialog).getByLabelText('Project name'), '  Beta  ')
    await user.click(within(dialog).getByRole('button', { name: 'Create project' }))

    expect(await screen.findByText('Beta')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls.find((call) => call.key === CREATE)?.body).toEqual({
      name: 'Beta',
      description: null,
    })
  })

  it('does not submit a blank name', async () => {
    const calls = serve({ [LIST]: () => page([]) })
    const user = userEvent.setup()

    renderPage()
    await screen.findByRole('heading', { name: 'No projects yet' })
    await user.click(screen.getAllByRole('button', { name: 'Create project' })[0])
    const dialog = screen.getByRole('dialog', { name: 'Create project' })
    await user.type(within(dialog).getByLabelText('Project name'), '   ')
    await user.click(within(dialog).getByRole('button', { name: 'Create project' }))

    expect(within(dialog).getByLabelText('Project name')).toHaveAttribute(
      'aria-invalid',
      'true',
    )
    expect(calls.some((call) => call.key === CREATE)).toBe(false)
  })

  it('edits a project with its current version', async () => {
    let current = project(ALPHA, 'Alpha', { description: 'Old notes' })
    const calls = serve({
      [LIST]: () => page([current]),
      [UPDATE_ALPHA]: () => {
        current = project(ALPHA, 'Alpha Two', {
          description: 'Old notes',
          version: 4,
        })
        return json(200, current)
      },
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Edit Alpha' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit project' })
    const name = within(dialog).getByLabelText('Project name')
    expect(within(dialog).getByLabelText('Description')).toHaveValue('Old notes')
    await user.clear(name)
    await user.type(name, 'Alpha Two')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await screen.findByText('Alpha Two')).toBeInTheDocument()
    expect(calls.find((call) => call.key === UPDATE_ALPHA)?.body).toEqual({
      name: 'Alpha Two',
      description: 'Old notes',
      version: 3,
    })
  })

  it('shows a stale edit as a form error', async () => {
    serve({
      [LIST]: () => page([project(ALPHA, 'Alpha')]),
      [UPDATE_ALPHA]: () =>
        conflict('The project was changed by someone else. Reload and try again.'),
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Edit Alpha' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit project' })
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      /changed by someone else/i,
    )
    expect(within(dialog).getByLabelText('Project name')).not.toHaveAttribute(
      'aria-invalid',
    )
  })

  it('archives a project after confirmation', async () => {
    let current = project(ALPHA, 'Alpha')
    const calls = serve({
      [LIST]: () => page([current]),
      [UPDATE_ALPHA]: () => {
        current = project(ALPHA, 'Alpha', { status: 'ARCHIVED', version: 4 })
        return json(200, current)
      },
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Archive Alpha' }))
    const dialog = screen.getByRole('dialog', { name: 'Archive Alpha?' })
    expect(calls.some((call) => call.key === UPDATE_ALPHA)).toBe(false)
    await user.click(within(dialog).getByRole('button', { name: 'Archive project' }))

    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Archive Alpha' })).toBeNull()
    expect(calls.find((call) => call.key === UPDATE_ALPHA)?.body).toEqual({
      status: 'ARCHIVED',
      version: 3,
    })
  })

  it('does not archive when confirmation is cancelled', async () => {
    const calls = serve({ [LIST]: () => page([project(ALPHA, 'Alpha')]) })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Archive Alpha' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls.some((call) => call.key === UPDATE_ALPHA)).toBe(false)
  })

  it('loads the next page', async () => {
    const calls = serve({
      [LIST]: (request) => {
        const number = Number(new URL(request.url).searchParams.get('page'))
        return number === 0
          ? page([project(ALPHA, 'Alpha')], 0, 21)
          : page([project(BETA, 'Beta')], 1, 21)
      },
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByText('Alpha')
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Next page' }))

    expect(await screen.findByText('Beta')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled()
    expect(calls.at(-1)?.url.searchParams.get('page')).toBe('1')
  })

  it('starts at page one without old projects after an organization switch', async () => {
    const OTHER_ORG = '22222222-2222-4222-8222-222222222222'
    const calls = serve({
      [LIST]: (request) => {
        const number = Number(new URL(request.url).searchParams.get('page'))
        return number === 0
          ? page([project(ALPHA, 'Alpha')], 0, 21)
          : page([project(BETA, 'Beta')], 1, 21)
      },
      [`GET /organizations/${OTHER_ORG}/projects`]: () =>
        new Promise<Response>(() => {}),
    })
    const user = userEvent.setup()
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    const organization = (id: string) => ({
      id,
      name: id === ORG ? 'Acme Labs' : 'Globex',
      slug: id === ORG ? 'acme-labs' : 'globex',
      allowSelfApproval: true,
      role: 'OWNER' as const,
      createdBy: '99999999-9999-4999-8999-999999999999',
      createdAt: '2026-11-20T10:00:00Z',
      updatedAt: '2026-11-20T10:00:00Z',
      version: 0,
    })
    const tree = (id: string) => (
      <QueryClientProvider client={client}>
        <CurrentOrgContext.Provider value={organization(id)}>
          <MemoryRouter>
            <ProjectListPage />
          </MemoryRouter>
        </CurrentOrgContext.Provider>
      </QueryClientProvider>
    )

    const { rerender } = render(tree(ORG))
    await screen.findByText('Alpha')
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    await screen.findByText('Beta')
    rerender(tree(OTHER_ORG))

    expect(screen.queryByText('Beta')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(calls.at(-1)?.key).toBe(`GET /organizations/${OTHER_ORG}/projects`),
    )
    expect(calls.at(-1)?.url.searchParams.get('page')).toBe('0')
  })

  it('offers a retry when projects fail to load', async () => {
    let attempts = 0
    serve({
      [LIST]: () => {
        attempts += 1
        return attempts === 1
          ? json(500, { title: 'Server Error', status: 500 })
          : page([project(ALPHA, 'Alpha')])
      },
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('Alpha')).toBeInTheDocument()
  })
})
