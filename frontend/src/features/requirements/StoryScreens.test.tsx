import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { RequirementDetailPage } from './RequirementDetailPage'

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

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ user: { id: USER } }),
}))

type Role = components['schemas']['Role']

const USER = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const ORG = '11111111-1111-4111-8111-111111111111'
const PROJECT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const DOCUMENT = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const RUN = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const STORY = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const STORY_TWO = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const ROLE = '77777777-7777-4777-8777-777777777777'

const BASE = `/organizations/${ORG}`
const DOC = `${BASE}/requirements/${DOCUMENT}`

const story = (overrides: Record<string, unknown> = {}) => ({
  id: STORY,
  documentId: DOCUMENT,
  analysisId: '99999999-9999-4999-8999-999999999999',
  title: 'Book a table',
  asA: 'diner',
  iWant: 'to book',
  soThat: 'I get a seat',
  acceptanceCriteria: ['Booking is saved'],
  priority: 'HIGH',
  jobRoleId: ROLE,
  sourceQuotes: [{ quote: 'Users can book a table', verified: true }],
  status: 'DRAFT',
  taskId: null,
  generatedBy: USER,
  createdAt: '2026-11-22T10:00:00Z',
  updatedAt: '2026-11-22T10:00:00Z',
  version: 0,
  canReview: true,
  ...overrides,
})

const run = (status: string) => ({
  id: RUN,
  documentId: DOCUMENT,
  runType: 'STORIES',
  status,
  provider: 'gemini',
  model: 'gemini-test',
  promptVersion: 'stories-v1',
  inputTokens: 100,
  outputTokens: 50,
  billingTier: 'FREE',
  costUsd: 0,
  errorCode: null,
  createdAt: '2026-11-22T10:01:00Z',
  startedAt: null,
  finishedAt: null,
})

const analysisState = {
  analysis: {
    id: '99999999-9999-4999-8999-999999999999',
    version: 1,
    createdAt: '2026-11-22T10:02:00Z',
    result: { summary: 'A dummy booking app.', features: [], users: [], gaps: [], risks: [] },
  },
  questions: [],
  latestRun: null,
  latestStoryRun: null,
}

const page = (items: unknown[]) => ({ items, page: 0, size: 100, totalItems: items.length })

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

type Handler = (request: Request) => Response | Promise<Response>

function serve(routes: Record<string, Handler>) {
  const calls: string[] = []
  const all: Record<string, Handler> = {
    [`GET ${BASE}/projects/${PROJECT}`]: () =>
      json(200, {
        id: PROJECT,
        organizationId: ORG,
        name: 'Table booking',
        description: null,
        status: 'ACTIVE',
        createdBy: USER,
        createdAt: '2026-11-20T10:00:00Z',
        updatedAt: '2026-11-21T10:00:00Z',
        version: 1,
      }),
    [`GET ${DOC}`]: () =>
      json(200, {
        id: DOCUMENT,
        organizationId: ORG,
        projectId: PROJECT,
        name: 'dummy-prd.md',
        mediaType: 'text/markdown',
        sizeBytes: 2048,
        characters: 1800,
        approverJobRoleId: ROLE,
        uploadedBy: USER,
        createdAt: '2026-11-22T10:00:00Z',
        updatedAt: '2026-11-22T10:00:00Z',
        version: 0,
      }),
    [`GET ${DOC}/analysis`]: () => json(200, analysisState),
    [`GET ${BASE}/job-roles`]: () =>
      json(200, page([{ id: ROLE, name: 'Project Manager', active: true }])),
    [`GET ${BASE}/members`]: () =>
      json(200, page([{ userId: USER, email: 'user@example.com', displayName: 'Asha', role: 'OWNER' }])),
    [`GET ${BASE}/projects/${PROJECT}/team`]: () => json(200, { members: [], jobRoles: [] }),
    [`GET ${BASE}/stories/${STORY}/reviews`]: () => json(200, page([])),
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

function renderPage(role: Role = 'OWNER') {
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
        <MemoryRouter initialEntries={[`/orgs/${ORG}/projects/${PROJECT}/requirements/${DOCUMENT}`]}>
          <Routes>
            <Route
              path="/orgs/:orgId/projects/:projectId/requirements/:documentId"
              element={<RequirementDetailPage />}
            />
          </Routes>
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

async function openStory(user: ReturnType<typeof userEvent.setup>, title = 'Book a table') {
  await user.click(await screen.findByRole('button', { name: title }))
  return screen.findByRole('dialog', { name: title })
}

describe('story screens', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('generates and lists story drafts', async () => {
    const user = userEvent.setup()
    let stories: unknown[] = []
    let polls = 0
    serve({
      [`GET ${DOC}/stories`]: () => json(200, page(stories)),
      [`POST ${DOC}/stories/generate`]: () => json(202, { runId: RUN }),
      [`GET ${BASE}/ai-runs/${RUN}`]: () => {
        polls += 1
        if (polls < 2) return json(200, run('RUNNING'))
        stories = [story()]
        return json(200, run('SUCCEEDED'))
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Generate stories' }))

    expect(await screen.findByText('Generating stories')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Book a table' }, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByText('Draft')).toBeInTheDocument()
  })

  it('warns before regeneration replaces drafts', async () => {
    const user = userEvent.setup()
    const calls = serve({
      [`GET ${DOC}/stories`]: () => json(200, page([story()])),
      [`POST ${DOC}/stories/generate`]: () => json(202, { runId: RUN }),
      [`GET ${BASE}/ai-runs/${RUN}`]: () => json(200, run('RUNNING')),
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Regenerate stories' }))
    const dialog = await screen.findByRole('dialog', { name: 'Replace 1 draft?' })
    expect(calls).not.toContain(`POST ${DOC}/stories/generate`)

    await user.click(within(dialog).getByRole('button', { name: 'Replace drafts' }))

    await waitFor(() => expect(calls).toContain(`POST ${DOC}/stories/generate`))
  })

  it('edits a story with its version', async () => {
    const user = userEvent.setup()
    let sent: Record<string, unknown> | undefined
    serve({
      [`GET ${DOC}/stories`]: () => json(200, page([story({ version: 4 })])),
      [`PATCH ${BASE}/stories/${STORY}`]: async (request) => {
        sent = (await request.json()) as Record<string, unknown>
        return json(200, story({ ...sent, version: 5 }))
      },
    })
    renderPage()
    const dialog = await openStory(user)

    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'Reserve a table')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(sent?.title).toBe('Reserve a table'))
    expect(sent?.version).toBe(4)
    expect(sent?.acceptanceCriteria).toEqual(['Booking is saved'])
  })

  it('requires a comment before rejecting', async () => {
    const user = userEvent.setup()
    let sent: unknown
    serve({
      [`GET ${DOC}/stories`]: () => json(200, page([story()])),
      [`POST ${BASE}/stories/${STORY}/reject`]: async (request) => {
        sent = await request.json()
        return json(200, story({ status: 'REJECTED', version: 1, canReview: false }))
      },
    })
    renderPage()
    const dialog = await openStory(user)

    const reject = within(dialog).getByRole('button', { name: 'Reject story' })
    expect(reject).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Rejection comment'), 'Too vague')
    await user.click(reject)

    await waitFor(() => expect(sent).toEqual({ version: 0, comment: 'Too vague' }))
  })

  it('shows approve only to an eligible reviewer', async () => {
    const user = userEvent.setup()
    serve({ [`GET ${DOC}/stories`]: () => json(200, page([story({ canReview: false })])) })
    renderPage()

    const dialog = await openStory(user)

    expect(within(dialog).queryByRole('button', { name: 'Approve story' })).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Reject story' })).not.toBeInTheDocument()
  })

  it('blocks approval while edits are unsaved or no role is chosen', async () => {
    const user = userEvent.setup()
    serve({ [`GET ${DOC}/stories`]: () => json(200, page([story({ jobRoleId: null })])) })
    renderPage()
    const dialog = await openStory(user)

    expect(within(dialog).getByRole('button', { name: 'Approve story' })).toBeDisabled()
    expect(within(dialog).getByText(/Choose a job role/)).toBeInTheDocument()
  })

  it('keeps typed text and offers reload on 409', async () => {
    const user = userEvent.setup()
    let latest = story({ version: 0 })
    serve({
      [`GET ${DOC}/stories`]: () => json(200, page([latest])),
      [`PATCH ${BASE}/stories/${STORY}`]: () => {
        latest = story({ title: 'Changed by someone else', version: 1 })
        return json(409, { title: 'Conflict', status: 409, detail: 'This story was changed by someone else.' })
      },
    })
    renderPage()
    const dialog = await openStory(user)
    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'My unsaved title')

    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByText(/changed by someone else/)).toBeInTheDocument()
    expect(title).toHaveValue('My unsaved title')

    await user.click(within(dialog).getByRole('button', { name: 'Reload latest' }))

    await waitFor(() => expect(within(dialog).getByLabelText('Title')).toHaveValue('Changed by someone else'))
  })

  it('creates tasks only from approved stories', async () => {
    const user = userEvent.setup()
    let stories = [
      story({ status: 'APPROVED', canReview: false }),
      story({ id: STORY_TWO, title: 'Pay online' }),
    ]
    const calls = serve({
      [`GET ${DOC}/stories`]: () => json(200, page(stories)),
      [`POST ${DOC}/create-tasks`]: () => {
        stories = [
          story({ status: 'CONVERTED', canReview: false, taskId: RUN, version: 2 }),
          story({ id: STORY_TWO, title: 'Pay online' }),
        ]
        return json(200, { created: [{ storyId: STORY, taskId: RUN }] })
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'Create 1 task' }))

    await waitFor(() => expect(calls).toContain(`POST ${DOC}/create-tasks`))
    expect(await screen.findByText('Created 1 task in the backlog.')).toBeInTheDocument()
    expect(screen.getByText('Task created')).toBeInTheDocument()
  })

  it('does not offer task creation twice for a converted story', async () => {
    const user = userEvent.setup()
    serve({
      [`GET ${DOC}/stories`]: () =>
        json(200, page([story({ status: 'CONVERTED', canReview: false, taskId: RUN })])),
    })
    renderPage()

    const dialog = await openStory(user)

    expect(screen.queryByRole('button', { name: /^Create \d+ task/ })).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
  })

  it('shows review history', async () => {
    const user = userEvent.setup()
    serve({
      [`GET ${DOC}/stories`]: () => json(200, page([story({ status: 'REJECTED', canReview: false })])),
      [`GET ${BASE}/stories/${STORY}/reviews`]: () =>
        json(
          200,
          page([
            {
              id: RUN,
              storyVersion: 0,
              action: 'REJECTED',
              actorId: USER,
              approvalBasis: 'ROLE_HOLDER',
              selfApproved: false,
              comment: 'Too vague',
              createdAt: '2026-11-22T11:00:00Z',
            },
          ]),
        ),
    })
    renderPage()

    const dialog = await openStory(user)

    expect(await within(dialog).findByText('Too vague')).toBeInTheDocument()
    expect(await within(dialog).findByText(/Asha rejected/)).toBeInTheDocument()
  })
})
