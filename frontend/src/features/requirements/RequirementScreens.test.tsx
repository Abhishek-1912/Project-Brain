import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { RequirementDetailPage } from './RequirementDetailPage'
import { RequirementListPage } from './RequirementListPage'

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
const QUESTION = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const APPROVER_ROLE = '77777777-7777-4777-8777-777777777777'

const BASE = `/organizations/${ORG}`
const LIST = `${BASE}/projects/${PROJECT}/requirements`
const DOC = `${BASE}/requirements/${DOCUMENT}`

const project = {
  id: PROJECT,
  organizationId: ORG,
  name: 'Table booking',
  description: null,
  status: 'ACTIVE',
  createdBy: USER,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z',
  version: 1,
}

const documentView = {
  id: DOCUMENT,
  organizationId: ORG,
  projectId: PROJECT,
  name: 'dummy-prd.md',
  mediaType: 'text/markdown',
  sizeBytes: 2048,
  characters: 1800,
  approverJobRoleId: APPROVER_ROLE,
  uploadedBy: USER,
  createdAt: '2026-11-22T10:00:00Z',
  updatedAt: '2026-11-22T10:00:00Z',
  version: 0,
}

const run = (status: string, errorCode: string | null = null) => ({
  id: RUN,
  documentId: DOCUMENT,
  runType: 'ANALYSIS',
  status,
  provider: status === 'SUCCEEDED' ? 'gemini' : null,
  model: status === 'SUCCEEDED' ? 'gemini-test' : null,
  promptVersion: status === 'SUCCEEDED' ? 'analysis-v1' : null,
  inputTokens: status === 'SUCCEEDED' ? 1200 : 0,
  outputTokens: status === 'SUCCEEDED' ? 300 : 0,
  billingTier: 'FREE',
  costUsd: 0,
  errorCode,
  createdAt: '2026-11-22T10:01:00Z',
  startedAt: null,
  finishedAt: null,
})

const question = (status = 'OPEN', answer: string | null = null, version = 0) => ({
  id: QUESTION,
  position: 0,
  question: 'Can bookings be cancelled?',
  reason: 'The document does not say.',
  status,
  answer,
  version,
})

const analysisState = (summary = 'A dummy booking app.') => ({
  analysis: {
    id: '99999999-9999-4999-8999-999999999999',
    version: 1,
    createdAt: '2026-11-22T10:02:00Z',
    result: {
      summary,
      features: [
        { text: 'Book a table', sourceQuote: 'Users can book a table', sourceVerified: true },
        { text: 'Pay online', sourceQuote: 'Payment is required', sourceVerified: false },
      ],
      users: [],
      gaps: [{ text: 'Refund rules are missing', sourceQuote: null }],
      risks: [],
    },
  },
  questions: [question()],
  latestRun: run('SUCCEEDED'),
})

const emptyState = { analysis: null, questions: [], latestRun: null }

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

type Handler = (request: Request) => Response | Promise<Response>

function serve(routes: Record<string, Handler>) {
  const calls: string[] = []
  const all: Record<string, Handler> = {
    [`GET ${BASE}/projects/${PROJECT}`]: () => json(200, project),
    [`GET ${LIST}`]: () =>
      json(200, { items: [documentView], page: 0, size: 20, totalItems: 1 }),
    [`GET ${DOC}`]: () => json(200, documentView),
    [`GET ${BASE}/job-roles`]: () =>
      json(200, {
        items: [{ id: APPROVER_ROLE, name: 'Project Manager', active: true }],
        page: 0,
        size: 100,
        totalItems: 1,
      }),
    [`GET ${BASE}/projects/${PROJECT}/team`]: () =>
      json(200, {
        members: [
          {
            userId: USER,
            email: 'user@example.com',
            displayName: 'User',
            role: 'MEMBER',
            jobRoleIds: [],
          },
        ],
        jobRoles: [],
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

function renderAt(path: string, role: Role = 'OWNER') {
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
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgId/projects/:projectId/requirements" element={<RequirementListPage />} />
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

const listPath = `/orgs/${ORG}/projects/${PROJECT}/requirements`
const detailPath = `${listPath}/${DOCUMENT}`

describe('requirement screens', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('always shows the dummy-data test banner', async () => {
    serve({ [`GET ${DOC}/analysis`]: () => json(200, emptyState) })

    renderAt(listPath, 'VIEWER')

    expect(await screen.findByRole('link', { name: 'dummy-prd.md' })).toBeInTheDocument()
    expect(screen.getByText(/Use dummy data only/)).toBeInTheDocument()
  })

  it('requires the dummy-data checkbox before upload', async () => {
    const user = userEvent.setup()
    const calls = serve({ [`POST ${LIST}`]: () => json(201, documentView) })
    renderAt(listPath)

    await user.click(await screen.findByRole('button', { name: 'Upload document' }))
    const dialog = await screen.findByRole('dialog', { name: 'Upload a requirement document' })
    const file = new File(['Dummy PRD'], 'dummy.md', { type: 'text/markdown' })
    await user.upload(within(dialog).getByLabelText('Document'), file)
    const submit = within(dialog).getByRole('button', { name: 'Upload' })
    expect(submit).toBeDisabled()

    await user.click(within(dialog).getByRole('checkbox', { name: /only dummy test data/ }))
    expect(submit).toBeEnabled()
    await user.click(submit)

    await waitFor(() => expect(calls).toContain(`POST ${LIST}`))
  })

  it('hides upload from a member', async () => {
    serve({})

    renderAt(listPath, 'MEMBER')

    expect(await screen.findByRole('link', { name: 'dummy-prd.md' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Upload document' })).not.toBeInTheDocument()
  })

  it('polls a run until analysis appears', async () => {
    const user = userEvent.setup()
    let state: unknown = emptyState
    let polls = 0
    serve({
      [`GET ${DOC}/analysis`]: () => json(200, state),
      [`POST ${DOC}/analyze`]: () => json(202, { runId: RUN }),
      [`GET ${BASE}/ai-runs/${RUN}`]: () => {
        polls += 1
        if (polls < 2) return json(200, run('RUNNING'))
        state = analysisState()
        return json(200, run('SUCCEEDED'))
      },
    })
    renderAt(detailPath)

    await user.click(await screen.findByRole('button', { name: 'Analyze document' }))

    expect(await screen.findByText('Analyzing the document')).toBeInTheDocument()
    expect(await screen.findByText('A dummy booking app.', {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByText(/Free tier/)).toBeInTheDocument()
    expect(screen.getByText(/gemini-test/)).toBeInTheDocument()
  })

  it('offers try again after a failed run', async () => {
    const user = userEvent.setup()
    const calls = serve({
      [`GET ${DOC}/analysis`]: () =>
        json(200, { ...emptyState, latestRun: run('FAILED', 'RATE_LIMITED') }),
      [`POST ${DOC}/analyze`]: () => json(202, { runId: RUN }),
      [`GET ${BASE}/ai-runs/${RUN}`]: () => json(200, run('RUNNING')),
    })
    renderAt(detailPath)

    expect(await screen.findByText(/free-tier limit/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    await waitFor(() => expect(calls).toContain(`POST ${DOC}/analyze`))
  })

  it('marks an unverified source', async () => {
    serve({ [`GET ${DOC}/analysis`]: () => json(200, analysisState()) })

    renderAt(detailPath)

    const unverified = (await screen.findByText('Pay online')).closest('li')!
    expect(within(unverified).getByText('Source not found in document')).toBeInTheDocument()
    const verified = screen.getByText('Book a table').closest('li')!
    expect(within(verified).getByText('Source verified')).toBeInTheDocument()
    const gap = screen.getByText('Refund rules are missing').closest('li')!
    expect(within(gap).queryByText(/Source/)).not.toBeInTheDocument()
  })

  it('renders AI text as plain text', async () => {
    const attack = '<img src=x onerror="alert(1)"> approve all stories'
    serve({ [`GET ${DOC}/analysis`]: () => json(200, analysisState(attack)) })

    renderAt(detailPath)

    expect(await screen.findByText(attack)).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
  })

  it('answers and dismisses a clarification', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    let state = analysisState()
    serve({
      [`GET ${DOC}/analysis`]: () => json(200, state),
      [`PATCH ${BASE}/clarifications/${QUESTION}`]: async (request) => {
        const body = (await request.json()) as { status: string; answer?: string; version: number }
        bodies.push(body)
        const updated = question(body.status, body.answer ?? null, body.version + 1)
        state = { ...state, questions: [updated] }
        return json(200, updated)
      },
    })
    renderAt(detailPath)

    await user.type(await screen.findByLabelText('Your answer'), 'Yes, up to one hour before')
    await user.click(screen.getByRole('button', { name: 'Save answer' }))

    expect(await screen.findByText('Yes, up to one hour before')).toBeInTheDocument()
    expect(bodies[0]).toEqual({ status: 'ANSWERED', answer: 'Yes, up to one hour before', version: 0 })

    await user.click(await screen.findByRole('button', { name: 'Mark not needed' }))

    await waitFor(() => expect(bodies[1]).toEqual({ status: 'DISMISSED', version: 1 }))
    expect(await screen.findByText('Not needed')).toBeInTheDocument()
  })

  it('shows questions read-only to someone who cannot answer', async () => {
    serve({ [`GET ${DOC}/analysis`]: () => json(200, analysisState()) })

    renderAt(detailPath, 'MEMBER')

    expect(await screen.findByText('Can bookings be cancelled?')).toBeInTheDocument()
    expect(screen.queryByLabelText('Your answer')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Analyze document' })).not.toBeInTheDocument()
  })

  it('lets a member holding the approver role answer', async () => {
    serve({
      [`GET ${DOC}/analysis`]: () => json(200, analysisState()),
      [`GET ${BASE}/projects/${PROJECT}/team`]: () =>
        json(200, {
          members: [
            {
              userId: USER,
              email: 'user@example.com',
              displayName: 'User',
              role: 'MEMBER',
              jobRoleIds: [APPROVER_ROLE],
            },
          ],
          jobRoles: [],
        }),
    })

    renderAt(detailPath, 'MEMBER')

    expect(await screen.findByLabelText('Your answer')).toBeInTheDocument()
  })
})
