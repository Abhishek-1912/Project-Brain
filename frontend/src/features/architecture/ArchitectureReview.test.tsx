import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { ArchitectureVersionView } from './ArchitectureVersionView'

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

const USER = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const ORG = '11111111-1111-4111-8111-111111111111'
const PROJECT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const VERSION = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const STORY = '99999999-9999-4999-8999-999999999999'

const BASE = `/organizations/${ORG}`
const VERSION_API = `${BASE}/architecture-versions/${VERSION}`

type Version = ReturnType<typeof version>

function version(overrides: Record<string, unknown> = {}) {
  return {
    id: VERSION,
    projectId: PROJECT,
    versionNumber: 2,
    status: 'DRAFT',
    basedOnVersionId: null,
    approverJobRoleId: null,
    overview: { summary: 'A booking app.', components: [], techStack: [], decisions: [], risks: [] },
    dataModel: {
      tables: [
        {
          name: 'bookings',
          description: 'Reservations',
          columns: [{ name: 'id', type: 'uuid', nullable: false, description: 'Key' }],
          relations: [],
          storyIds: [STORY],
        },
      ],
    },
    apiDesign: {
      endpoints: [
        {
          method: 'POST',
          path: '/bookings',
          summary: 'Create a booking',
          requestFields: [],
          responseFields: [],
          roles: ['Diner'],
          storyIds: [STORY],
        },
      ],
    },
    storyIds: [STORY],
    stories: [{ id: STORY, title: 'Book a table', status: 'APPROVED' }],
    generatedBy: USER,
    createdAt: '2026-11-24T10:00:00Z',
    updatedAt: '2026-11-24T10:00:00Z',
    version: 0,
    canEdit: true,
    canReview: true,
    ...overrides,
  }
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Handler = (request: Request) => Response | Promise<Response>

function serve(routes: Record<string, Handler>) {
  const calls: string[] = []
  const all: Record<string, Handler> = {
    [`GET ${VERSION_API}/reviews`]: () => json(200, { items: [], page: 0, size: 100, totalItems: 0 }),
    [`GET ${BASE}/members`]: () =>
      json(200, {
        items: [{ userId: USER, email: 'asha@example.com', displayName: 'Asha', role: 'OWNER' }],
        page: 0,
        size: 100,
        totalItems: 1,
      }),
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

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const org = {
    id: ORG,
    name: 'Acme Labs',
    slug: 'acme-labs',
    allowSelfApproval: true,
    role: 'OWNER' as const,
    createdBy: USER,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <QueryClientProvider client={client}>
      <CurrentOrgContext.Provider value={org}>
        <MemoryRouter initialEntries={[`/orgs/${ORG}/projects/${PROJECT}/architecture/versions/${VERSION}`]}>
          <Routes>
            <Route
              path="/orgs/:orgId/projects/:projectId/architecture/versions/:versionId"
              element={<ArchitectureVersionView />}
            />
          </Routes>
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

describe('architecture review screens', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('edits a table and sends the section with its version', async () => {
    const user = userEvent.setup()
    let sent: { content: { tables: { description: string }[] }; version: number } | undefined
    serve({
      [`GET ${VERSION_API}`]: () => json(200, version({ version: 3 })),
      [`PATCH ${VERSION_API}/data-model`]: async (request) => {
        sent = (await request.json()) as typeof sent
        return json(200, version({ version: 4, dataModel: sent!.content }))
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Edit data model' }))
    const table = screen.getByRole('group', { name: 'Table bookings' })
    const description = within(table).getByLabelText('Table description')
    await user.clear(description)
    await user.type(description, 'Table reservations')
    await user.click(screen.getByRole('button', { name: 'Save data model' }))

    await waitFor(() => expect(sent?.version).toBe(3))
    expect(sent?.content.tables[0].description).toBe('Table reservations')
    expect(await screen.findByText('Table reservations')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save data model' })).not.toBeInTheDocument()
  })

  it('adds and removes columns and endpoints', async () => {
    const user = userEvent.setup()
    const bodies: { content: Record<string, unknown[]> }[] = []
    let current: Version = version()
    serve({
      [`GET ${VERSION_API}`]: () => json(200, current),
      [`PATCH ${VERSION_API}/data-model`]: async (request) => {
        const body = (await request.json()) as { content: Record<string, unknown[]> }
        bodies.push(body)
        current = version({ version: 1, dataModel: body.content })
        return json(200, current)
      },
      [`PATCH ${VERSION_API}/api-design`]: async (request) => {
        const body = (await request.json()) as { content: Record<string, unknown[]> }
        bodies.push(body)
        current = { ...current, version: 2, apiDesign: body.content as never }
        return json(200, current)
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Edit data model' }))
    await user.click(within(screen.getByRole('group', { name: 'Table bookings' })).getByRole('button', { name: 'Add column' }))
    const added = screen.getByRole('group', { name: 'Column 2' })
    await user.type(within(added).getByLabelText('Column name'), 'party_size')
    await user.type(within(added).getByLabelText('Column description'), 'People')
    await user.click(screen.getByRole('button', { name: 'Save data model' }))
    await waitFor(() => expect(bodies).toHaveLength(1))

    await user.click(await screen.findByRole('button', { name: 'Edit API design' }))
    await user.click(within(screen.getByRole('group', { name: 'Endpoint POST /bookings' })).getByRole('button', {
      name: 'Remove endpoint',
    }))
    await user.click(screen.getByRole('button', { name: 'Save API design' }))
    await waitFor(() => expect(bodies).toHaveLength(2))

    const tables = bodies[0].content.tables as { columns: { name: string; type: string }[] }[]
    expect(tables[0].columns.map((column) => column.name)).toEqual(['id', 'party_size'])
    expect(tables[0].columns[1].type).toBe('text')
    expect(bodies[1].content.endpoints).toEqual([])
  })

  it('keeps typed text and offers reload on 409', async () => {
    const user = userEvent.setup()
    let latest = version()
    serve({
      [`GET ${VERSION_API}`]: () => json(200, latest),
      [`PATCH ${VERSION_API}/overview`]: () => {
        latest = version({
          version: 1,
          overview: { summary: 'Changed by someone else', components: [], techStack: [], decisions: [], risks: [] },
        })
        return json(409, { title: 'Conflict', status: 409, detail: 'This version was changed by someone else.' })
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Edit overview' }))
    const summary = screen.getByLabelText('Summary')
    await user.clear(summary)
    await user.type(summary, 'My unsaved summary')
    await user.click(screen.getByRole('button', { name: 'Save overview' }))

    expect(await screen.findByText(/changed by someone else/)).toBeInTheDocument()
    expect(summary).toHaveValue('My unsaved summary')
    await user.click(screen.getByRole('button', { name: 'Reload latest' }))

    await waitFor(() => expect(screen.getByLabelText('Summary')).toHaveValue('Changed by someone else'))
  })

  it('requires a comment before rejecting', async () => {
    const user = userEvent.setup()
    let sent: unknown
    serve({
      [`GET ${VERSION_API}`]: () => json(200, version()),
      [`POST ${VERSION_API}/reject`]: async (request) => {
        sent = await request.json()
        return json(200, version({ status: 'REJECTED', version: 1, canEdit: false, canReview: false }))
      },
    })
    renderPage()

    const reject = await screen.findByRole('button', { name: 'Reject version' })
    expect(reject).toBeDisabled()
    await user.type(screen.getByLabelText('Rejection comment'), 'Rename bookings')
    await user.click(reject)

    await waitFor(() => expect(sent).toEqual({ version: 0, comment: 'Rename bookings' }))
    expect(await screen.findByText('Rejected')).toBeInTheDocument()
  })

  it('shows approve only when canReview is true', async () => {
    serve({ [`GET ${VERSION_API}`]: () => json(200, version({ canReview: false, canEdit: false })) })
    renderPage()

    expect(await screen.findByRole('heading', { name: /Version 2/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve version' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject version' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument()
  })

  it('blocks review while edits are unsaved or a run is active', async () => {
    const user = userEvent.setup()
    serve({ [`GET ${VERSION_API}`]: () => json(200, version()) })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Edit overview' }))
    await user.type(screen.getByLabelText('Summary'), ' More')

    expect(screen.getByRole('button', { name: 'Approve version' })).toBeDisabled()
    expect(screen.getByText(/Save or cancel your edits before deciding/)).toBeInTheDocument()
  })

  it('approves and shows the decision in history', async () => {
    const user = userEvent.setup()
    let reviewed = false
    serve({
      [`GET ${VERSION_API}`]: () => json(200, version(reviewed ? { status: 'APPROVED', version: 1, canReview: false, canEdit: false } : {})),
      [`POST ${VERSION_API}/approve`]: () => {
        reviewed = true
        return json(200, version({ status: 'APPROVED', version: 1, canReview: false, canEdit: false }))
      },
      [`GET ${VERSION_API}/reviews`]: () =>
        json(200, {
          items: reviewed
            ? [
                {
                  id: STORY,
                  contentVersion: 0,
                  action: 'APPROVED',
                  actorId: USER,
                  approvalBasis: 'OWNER_FALLBACK',
                  selfApproved: true,
                  comment: null,
                  createdAt: '2026-11-24T11:00:00Z',
                },
              ]
            : [],
          page: 0,
          size: 100,
          totalItems: reviewed ? 1 : 0,
        }),
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Approve version' }))

    expect(await screen.findByText(/Asha approved their own version/)).toBeInTheDocument()
  })

  it('downloads the version as Markdown', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.fn(() => 'blob:architecture')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }))
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const calls = serve({
      [`GET ${VERSION_API}`]: () => json(200, version()),
      [`GET ${VERSION_API}/markdown`]: () =>
        new Response('# Architecture version 2', { status: 200, headers: { 'Content-Type': 'text/markdown' } }),
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Download Markdown' }))

    await waitFor(() => expect(click).toHaveBeenCalled())
    expect(calls).toContain(`GET ${VERSION_API}/markdown`)
    expect(createObjectURL).toHaveBeenCalled()
    click.mockRestore()
  })
})
