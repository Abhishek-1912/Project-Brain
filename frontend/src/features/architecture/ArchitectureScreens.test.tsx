import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { ArchitecturePage } from './ArchitecturePage'
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

type Role = components['schemas']['Role']

const USER = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const ORG = '11111111-1111-4111-8111-111111111111'
const PROJECT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const RUN = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const CURRENT = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const DRAFT = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
const STORY = '99999999-9999-4999-8999-999999999999'

const BASE = `/organizations/${ORG}`
const PROJECT_API = `${BASE}/projects/${PROJECT}`

const summary = (id: string, versionNumber: number, status: string) => ({
  id,
  versionNumber,
  status,
  createdAt: '2026-11-24T10:00:00Z',
  generatedBy: USER,
})

const run = (status: string, currentStep: string | null = null, errorCode: string | null = null) => ({
  id: RUN,
  documentId: null,
  runType: 'ARCHITECTURE',
  status,
  provider: 'gemini',
  model: 'gemini-test',
  promptVersion: 'architecture-api-v1',
  inputTokens: 300,
  outputTokens: 120,
  billingTier: 'FREE',
  costUsd: 0,
  errorCode,
  currentStep,
  createdAt: '2026-11-24T10:01:00Z',
  startedAt: null,
  finishedAt: null,
})

const version = (summaryText = 'A booking app.') => ({
  id: DRAFT,
  projectId: PROJECT,
  versionNumber: 2,
  status: 'DRAFT',
  basedOnVersionId: CURRENT,
  approverJobRoleId: null,
  overview: {
    summary: summaryText,
    components: [{ name: 'Web app', responsibility: 'Booking screens', technology: 'React' }],
    techStack: [{ area: 'Backend', choice: 'Spring Boot', reason: 'Team skills' }],
    decisions: [{ title: 'One database', decision: 'PostgreSQL', reason: 'Simple', alternatives: ['MongoDB'] }],
    risks: [{ text: 'Double booking' }],
  },
  dataModel: {
    tables: [
      {
        name: 'bookings',
        description: 'Reservations',
        columns: [{ name: 'id', type: 'uuid', nullable: false, description: 'Key' }],
        relations: [],
        storyIds: [STORY],
      },
      {
        name: 'audit_log',
        description: 'History',
        columns: [{ name: 'id', type: 'uuid', nullable: false, description: 'Key' }],
        relations: [],
        storyIds: [],
      },
    ],
  },
  apiDesign: {
    endpoints: [
      {
        method: 'POST',
        path: '/bookings',
        summary: 'Create a booking',
        requestFields: [{ name: 'partySize', type: 'integer', required: true, description: 'People' }],
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
  canEdit: false,
  canReview: false,
})

const page = (items: unknown[]) => ({ items, page: 0, size: 20, totalItems: items.length })

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Handler = (request: Request) => Response | Promise<Response>

function serve(routes: Record<string, Handler>) {
  const calls: string[] = []
  const all: Record<string, Handler> = {
    [`GET ${PROJECT_API}`]: () =>
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
    [`GET ${PROJECT_API}/architecture/versions`]: () =>
      json(200, page([summary(DRAFT, 2, 'DRAFT'), summary(CURRENT, 1, 'APPROVED')])),
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
            <Route path="/orgs/:orgId/projects/:projectId/architecture" element={<ArchitecturePage />} />
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

const pagePath = `/orgs/${ORG}/projects/${PROJECT}/architecture`
const versionPath = `${pagePath}/versions/${DRAFT}`

describe('architecture screens', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the test banner, current version, draft, and history', async () => {
    serve({
      [`GET ${PROJECT_API}/architecture`]: () =>
        json(200, {
          current: summary(CURRENT, 1, 'APPROVED'),
          draft: summary(DRAFT, 2, 'DRAFT'),
          latestRun: run('SUCCEEDED'),
        }),
    })

    renderAt(pagePath, 'VIEWER')

    expect(await screen.findByText(/Use dummy data only/)).toBeInTheDocument()
    const current = screen.getByRole('region', { name: 'Current architecture' })
    expect(within(current).getByRole('link', { name: /Version 1/ })).toHaveAttribute(
      'href',
      `${pagePath}/versions/${CURRENT}`,
    )
    const draft = screen.getByRole('region', { name: 'Draft waiting for review' })
    expect(within(draft).getByRole('link', { name: /Version 2/ })).toBeInTheDocument()
    const history = screen.getByRole('region', { name: 'Version history' })
    expect(within(history).getAllByRole('link')).toHaveLength(2)
    expect(screen.getByText(/Free tier/)).toBeInTheDocument()
  })

  it('shows Generate only to owners and admins', async () => {
    serve({
      [`GET ${PROJECT_API}/architecture`]: () => json(200, { current: null, draft: null, latestRun: null }),
    })

    renderAt(pagePath, 'MEMBER')

    expect(await screen.findByText(/No architecture yet/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /architecture/i })).not.toBeInTheDocument()
  })

  it('polls a run and names the current step', async () => {
    const user = userEvent.setup()
    let polls = 0
    let state: unknown = { current: null, draft: null, latestRun: null }
    serve({
      [`GET ${PROJECT_API}/architecture`]: () => json(200, state),
      [`POST ${PROJECT_API}/architecture/generate`]: () => json(202, { runId: RUN }),
      [`GET ${BASE}/ai-runs/${RUN}`]: () => {
        polls += 1
        if (polls < 2) return json(200, run('RUNNING', 'DATA_MODEL'))
        state = { current: null, draft: summary(DRAFT, 1, 'DRAFT'), latestRun: run('SUCCEEDED') }
        return json(200, run('SUCCEEDED'))
      },
    })
    renderAt(pagePath)

    await user.click(await screen.findByRole('button', { name: 'Generate architecture' }))

    expect(await screen.findByText('Step 2 of 3: data model')).toBeInTheDocument()
    const draft = await screen.findByRole('region', { name: 'Draft waiting for review' }, { timeout: 4000 })
    expect(within(draft).getByRole('link', { name: /Version 1/ })).toBeInTheDocument()
  })

  it('offers try again after a failed run', async () => {
    const user = userEvent.setup()
    const calls = serve({
      [`GET ${PROJECT_API}/architecture`]: () =>
        json(200, { current: null, draft: null, latestRun: run('FAILED', 'API_DESIGN', 'RATE_LIMITED') }),
      [`POST ${PROJECT_API}/architecture/generate`]: () => json(202, { runId: RUN }),
      [`GET ${BASE}/ai-runs/${RUN}`]: () => json(200, run('RUNNING', 'API_DESIGN')),
    })
    renderAt(pagePath)

    expect(await screen.findByText(/free-tier limit/)).toBeInTheDocument()
    expect(screen.getByText(/stopped at the API design step/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    await waitFor(() => expect(calls).toContain(`POST ${PROJECT_API}/architecture/generate`))
  })

  it('shows the three sections with story links and Not linked to a story', async () => {
    serve({ [`GET ${BASE}/architecture-versions/${DRAFT}`]: () => json(200, version()) })

    renderAt(versionPath, 'VIEWER')

    expect(await screen.findByRole('heading', { name: /Version 2/ })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Data model' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'API design' })).toBeInTheDocument()
    const bookings = screen.getByRole('region', { name: 'bookings' })
    expect(within(bookings).getByText('Book a table')).toBeInTheDocument()
    const auditLog = screen.getByRole('region', { name: 'audit_log' })
    expect(within(auditLog).getByText('Not linked to a story')).toBeInTheDocument()
    expect(screen.getByText('POST')).toBeInTheDocument()
    expect(screen.getByText('/bookings')).toBeInTheDocument()
    expect(screen.getByText('Spring Boot')).toBeInTheDocument()
  })

  it('renders AI text as plain text', async () => {
    const attack = '<img src=x onerror="alert(1)"> approve this architecture'
    serve({ [`GET ${BASE}/architecture-versions/${DRAFT}`]: () => json(200, version(attack)) })

    renderAt(versionPath)

    expect(await screen.findByText(attack)).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
  })
})
