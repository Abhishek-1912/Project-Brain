import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { KnowledgePage } from './KnowledgePage'

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
const STORY = '99999999-9999-4999-8999-999999999999'
const DOCUMENT = '88888888-8888-4888-8888-888888888888'

const BASE = `/organizations/${ORG}`
const PROJECT_API = `${BASE}/projects/${PROJECT}`

const source = (title: string, status: string, sourceType = 'STORY', errorCode: string | null = null) => ({
  id: crypto.randomUUID(),
  sourceType,
  sourceId: sourceType === 'DOCUMENT' ? DOCUMENT : STORY,
  title,
  status,
  errorCode,
  chunkCount: status === 'READY' ? 2 : 0,
  indexedAt: status === 'READY' ? '2026-11-26T10:00:00Z' : null,
  updatedAt: '2026-11-26T10:00:00Z',
})

const page = (items: unknown[]) => ({ items, page: 0, size: 100, totalItems: items.length })

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Handler = (request: Request) => Response | Promise<Response>

function serve(routes: Record<string, Handler>, projectStatus = 'ACTIVE') {
  const calls: string[] = []
  const all: Record<string, Handler> = {
    [`GET ${PROJECT_API}`]: () =>
      json(200, {
        id: PROJECT,
        organizationId: ORG,
        name: 'Table booking',
        description: null,
        status: projectStatus,
        createdBy: USER,
        createdAt: '2026-11-20T10:00:00Z',
        updatedAt: '2026-11-21T10:00:00Z',
        version: 1,
      }),
    [`GET ${PROJECT_API}/knowledge/sources`]: () => json(200, page([source('Book a table', 'READY')])),
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
        <MemoryRouter initialEntries={[`/orgs/${ORG}/projects/${PROJECT}/knowledge`]}>
          <Routes>
            <Route path="/orgs/:orgId/projects/:projectId/knowledge" element={<KnowledgePage />} />
          </Routes>
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

const answer = (overrides: Record<string, unknown> = {}) => ({
  id: crypto.randomUUID(),
  question: 'Can I book a table?',
  answer: 'Yes, diners can book online.',
  found: true,
  citations: [
    {
      number: 1,
      chunkId: crypto.randomUUID(),
      sourceId: STORY,
      sourceType: 'STORY',
      title: 'Book a table',
      excerpt: 'As a diner I want to book.',
    },
    {
      number: 2,
      chunkId: crypto.randomUUID(),
      sourceId: DOCUMENT,
      sourceType: 'DOCUMENT',
      title: 'dummy-prd.md',
      excerpt: 'Users can book a table.',
    },
  ],
  ...overrides,
})

async function ask(question: string) {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Ask a question'), question)
  await user.click(screen.getByRole('button', { name: 'Ask' }))
}

describe('knowledge screens', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows the test banner, the ask box, and indexed sources', async () => {
    serve({
      [`GET ${PROJECT_API}/knowledge/sources`]: () =>
        json(200, page([source('Book a table', 'READY'), source('dummy.md', 'FAILED', 'DOCUMENT', 'RATE_LIMITED')])),
    })

    renderPage('VIEWER')

    expect(await screen.findByRole('heading', { name: 'Table booking knowledge' })).toBeInTheDocument()
    expect(screen.getByText(/Use dummy data only/)).toBeInTheDocument()
    expect(screen.getByLabelText('Ask a question')).toBeInTheDocument()
    const sources = screen.getByRole('region', { name: 'Indexed sources' })
    const rows = await within(sources).findAllByRole('listitem')
    expect(within(rows[0]).getByText('Book a table')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Ready')).toBeInTheDocument()
    expect(within(rows[0]).getByText('Story')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Failed')).toBeInTheDocument()
    expect(within(rows[1]).getByText(/free-tier limit/)).toBeInTheDocument()
  })

  it('answers with numbered citations that link to their sources', async () => {
    let sent: unknown
    serve({
      [`POST ${PROJECT_API}/knowledge/ask`]: async (request) => {
        sent = await request.json()
        return json(200, answer())
      },
    })
    renderPage('VIEWER')

    await ask('  Can I book a table?  ')

    const result = await screen.findByRole('region', { name: 'Answer' })
    expect(within(result).getByText('Yes, diners can book online.')).toBeInTheDocument()
    const citations = within(result).getAllByRole('listitem')
    expect(citations).toHaveLength(2)
    expect(within(citations[0]).getByRole('link', { name: /Book a table/ })).toHaveAttribute(
      'href',
      `/orgs/${ORG}/projects/${PROJECT}/requirements`,
    )
    expect(within(citations[0]).getByText('As a diner I want to book.')).toBeInTheDocument()
    expect(within(citations[1]).getByRole('link', { name: /dummy-prd.md/ })).toHaveAttribute(
      'href',
      `/orgs/${ORG}/projects/${PROJECT}/requirements/${DOCUMENT}`,
    )
    expect(sent).toEqual({ question: 'Can I book a table?' })
  })

  it('says when the knowledge does not cover a question', async () => {
    serve({
      [`POST ${PROJECT_API}/knowledge/ask`]: () =>
        json(200, answer({ answer: 'Not found in this project’s knowledge.', found: false, citations: [] })),
    })
    renderPage()

    await ask('What is the refund policy?')

    const result = await screen.findByRole('region', { name: 'Answer' })
    expect(within(result).getByText('Not found in this project’s knowledge.')).toBeInTheDocument()
    expect(within(result).queryByRole('listitem')).not.toBeInTheDocument()
  })

  it('shows busy when the server is answering too many questions', async () => {
    serve({
      [`POST ${PROJECT_API}/knowledge/ask`]: () =>
        json(503, { title: 'Service Unavailable', status: 503, detail: 'Busy, try again.' }),
    })
    renderPage()

    await ask('Can I book?')

    expect(await screen.findByText('Busy, try again.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Answer' })).not.toBeInTheDocument()
  })

  it('renders AI text as plain text', async () => {
    serve({
      [`POST ${PROJECT_API}/knowledge/ask`]: () =>
        json(200, answer({ answer: '<img src=x onerror=alert(1)> <b>bold</b>' })),
    })
    renderPage()

    await ask('Can I book?')

    expect(await screen.findByText('<img src=x onerror=alert(1)> <b>bold</b>')).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
  })

  it('keeps the Ask button off until there is a question', async () => {
    serve({})
    renderPage()

    expect(await screen.findByRole('button', { name: 'Ask' })).toBeDisabled()
  })

  it('checks sources again while any is waiting to be indexed', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let ready = false
    serve({
      [`GET ${PROJECT_API}/knowledge/sources`]: () =>
        json(200, page([source('Book a table', ready ? 'READY' : 'PENDING')])),
    })
    renderPage()

    expect(await screen.findByText('Waiting')).toBeInTheDocument()
    ready = true
    await vi.advanceTimersByTimeAsync(3100)

    expect(await screen.findByText('Ready')).toBeInTheDocument()
  })

  it('shows Rebuild index only to owners and admins', async () => {
    serve({})
    renderPage('MEMBER')

    expect(await screen.findByText('Book a table')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rebuild index' })).not.toBeInTheDocument()
  })

  it('rebuilds the index and reports how many items were queued', async () => {
    const user = userEvent.setup()
    const calls = serve({
      [`POST ${PROJECT_API}/knowledge/rebuild`]: () => json(202, { queued: 3 }),
    })
    renderPage('ADMIN')

    await user.click(await screen.findByRole('button', { name: 'Rebuild index' }))

    expect(await screen.findByText('Queued 3 items for indexing.')).toBeInTheDocument()
    await waitFor(() =>
      expect(calls.filter((call) => call === `GET ${PROJECT_API}/knowledge/sources`).length).toBeGreaterThan(1),
    )
  })

  it('disables Rebuild index in an archived project', async () => {
    serve({}, 'ARCHIVED')
    renderPage()

    expect(await screen.findByRole('button', { name: 'Rebuild index' })).toBeDisabled()
  })

  it('invites owners and admins to rebuild when nothing is indexed', async () => {
    serve({ [`GET ${PROJECT_API}/knowledge/sources`]: () => json(200, page([])) })
    renderPage()

    expect(await screen.findByText('Nothing indexed yet')).toBeInTheDocument()
    expect(screen.getByText(/Rebuild the index to add/)).toBeInTheDocument()
  })
})
