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
const NOTE = '77777777-7777-4777-8777-777777777777'

const BASE = `/organizations/${ORG}`
const PROJECT_API = `${BASE}/projects/${PROJECT}`
const NOTE_API = `${BASE}/knowledge/notes/${NOTE}`

const note = (title = 'Release checklist', body = 'Deploy on Fridays only.\nTell support first.', version = 0) => ({
  id: NOTE,
  projectId: PROJECT,
  title,
  body,
  createdBy: USER,
  updatedBy: USER,
  createdAt: '2026-11-26T10:00:00Z',
  updatedAt: '2026-11-26T11:00:00Z',
  version,
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
    [`GET ${PROJECT_API}/knowledge/sources`]: () => json(200, page([])),
    [`GET ${PROJECT_API}/knowledge/notes`]: () => json(200, page([note()])),
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

async function notesRegion() {
  return screen.findByRole('region', { name: 'Notes' })
}

describe('knowledge notes', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('lists notes with their full text', async () => {
    serve({})
    renderPage('VIEWER')

    const notes = await notesRegion()
    const item = await within(notes).findByRole('article', { name: 'Release checklist' })
    expect(item).toHaveAttribute('id', `note-${NOTE}`)
    expect(within(item).getByText(/Deploy on Fridays only\.\s+Tell support first\./)).toBeInTheDocument()
  })

  it('creates a note and refreshes the list', async () => {
    const user = userEvent.setup()
    let sent: unknown
    let notes = [note()]
    const calls = serve({
      [`GET ${PROJECT_API}/knowledge/notes`]: () => json(200, page(notes)),
      [`POST ${PROJECT_API}/knowledge/notes`]: async (request) => {
        sent = await request.json()
        notes = [...notes, { ...note('On-call', 'Page the lead.'), id: crypto.randomUUID() }]
        return json(201, notes[1])
      },
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'New note' }))
    const dialog = screen.getByRole('dialog', { name: 'New note' })
    await user.type(within(dialog).getByLabelText('Title'), '  On-call  ')
    await user.type(within(dialog).getByLabelText('Note'), 'Page the lead.')
    await user.click(within(dialog).getByRole('button', { name: 'Save note' }))

    expect(await within(await notesRegion()).findByRole('article', { name: 'On-call' })).toBeInTheDocument()
    expect(sent).toEqual({ title: 'On-call', body: 'Page the lead.' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(calls.filter((call) => call === `GET ${PROJECT_API}/knowledge/sources`).length).toBeGreaterThan(1)
  })

  it('explains what is missing before saving', async () => {
    const user = userEvent.setup()
    const calls = serve({})
    renderPage()

    await user.click(await screen.findByRole('button', { name: 'New note' }))
    const dialog = screen.getByRole('dialog', { name: 'New note' })
    await user.type(within(dialog).getByLabelText('Title'), '   ')
    await user.click(within(dialog).getByRole('button', { name: 'Save note' }))

    expect(within(dialog).getByText('Enter a title.')).toBeInTheDocument()
    expect(within(dialog).getByText('Write the note.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Title')).toHaveAttribute('maxLength', '200')
    expect(within(dialog).getByLabelText('Note')).toHaveAttribute('maxLength', '20000')
    expect(calls).not.toContain(`POST ${PROJECT_API}/knowledge/notes`)
  })

  it('edits with the version it loaded and explains a stale edit', async () => {
    const user = userEvent.setup()
    let sent: unknown
    serve({
      [`PATCH ${NOTE_API}`]: async (request) => {
        sent = await request.json()
        return json(409, { title: 'Conflict', status: 409, detail: 'This note changed. Reload to see the latest version.' })
      },
    })
    renderPage('ADMIN')

    const item = await within(await notesRegion()).findByRole('article', { name: 'Release checklist' })
    await user.click(within(item).getByRole('button', { name: 'Edit' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit note' })
    const body = within(dialog).getByLabelText('Note')
    await user.clear(body)
    await user.type(body, 'Deploy on Mondays.')
    await user.click(within(dialog).getByRole('button', { name: 'Save note' }))

    expect(await within(dialog).findByText('This note changed. Reload to see the latest version.')).toBeInTheDocument()
    expect(sent).toEqual({ title: 'Release checklist', body: 'Deploy on Mondays.', version: 0 })
  })

  it('deletes a note after confirmation', async () => {
    const user = userEvent.setup()
    let notes = [note()]
    const calls = serve({
      [`GET ${PROJECT_API}/knowledge/notes`]: () => json(200, page(notes)),
      [`DELETE ${NOTE_API}`]: () => {
        notes = []
        return new Response(null, { status: 204 })
      },
    })
    renderPage()

    const item = await within(await notesRegion()).findByRole('article', { name: 'Release checklist' })
    await user.click(within(item).getByRole('button', { name: 'Delete' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete note?' })
    expect(within(dialog).getByText(/removed from search/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Delete note' }))

    await waitFor(() =>
      expect(screen.queryByRole('article', { name: 'Release checklist' })).not.toBeInTheDocument(),
    )
    expect(calls).toContain(`DELETE ${NOTE_API}`)
  })

  it('is read-only for members and viewers', async () => {
    serve({})
    renderPage('MEMBER')

    const item = await within(await notesRegion()).findByRole('article', { name: 'Release checklist' })
    expect(screen.queryByRole('button', { name: 'New note' })).not.toBeInTheDocument()
    expect(within(item).queryByRole('button')).not.toBeInTheDocument()
  })

  it('is read-only in an archived project', async () => {
    serve({}, 'ARCHIVED')
    renderPage()

    const item = await within(await notesRegion()).findByRole('article', { name: 'Release checklist' })
    expect(screen.queryByRole('button', { name: 'New note' })).not.toBeInTheDocument()
    expect(within(item).queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows note text as plain text', async () => {
    serve({
      [`GET ${PROJECT_API}/knowledge/notes`]: () =>
        json(200, page([note('Script', '<script>alert(1)</script><b>bold</b>')])),
    })
    renderPage()

    expect(await screen.findByText('<script>alert(1)</script><b>bold</b>')).toBeInTheDocument()
    expect(document.querySelector('article b')).toBeNull()
  })

  it('invites owners and admins to write the first note', async () => {
    serve({ [`GET ${PROJECT_API}/knowledge/notes`]: () => json(200, page([])) })
    renderPage()

    expect(await screen.findByText('No notes yet')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'New note' })).toBeInTheDocument()
  })
})
