import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { TaskListPage } from './TaskListPage'

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
const PROJECT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const TASK = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
const ASHA = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd'
const RAVI = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
const PROJECT_PATH = `GET /organizations/${ORG}/projects/${PROJECT}`
const MEMBERS = `GET /organizations/${ORG}/members`
const LIST = `GET /organizations/${ORG}/projects/${PROJECT}/tasks`
const CREATE = `POST /organizations/${ORG}/projects/${PROJECT}/tasks`
const GET_TASK = `GET /organizations/${ORG}/tasks/${TASK}`
const UPDATE = `PATCH /organizations/${ORG}/tasks/${TASK}`
const DELETE = `DELETE /organizations/${ORG}/tasks/${TASK}`

const task = (overrides = {}) => ({
  id: TASK,
  organizationId: ORG,
  projectId: PROJECT,
  title: 'Write docs',
  description: 'First draft',
  status: 'TODO',
  priority: 'HIGH',
  assigneeId: ASHA,
  dueDate: '2026-12-01',
  createdBy: ASHA,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z',
  version: 2,
  ...overrides,
})

const projectBody = (status = 'ACTIVE') => ({
  id: PROJECT,
  organizationId: ORG,
  name: 'Customer portal',
  description: null,
  status,
  createdBy: ASHA,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z',
  version: 1,
})

const member = (userId: string, displayName: string) => ({
  userId,
  email: `${displayName.toLowerCase()}@example.com`,
  displayName,
  role: 'MEMBER',
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const page = (items: unknown[], number = 0, totalItems = items.length) =>
  json(200, { items, page: number, size: 20, totalItems })

type Handler = (request: Request) => Response

function serve(routes: Record<string, Handler>, projectStatus = 'ACTIVE') {
  const calls: { key: string; url: URL; body: unknown }[] = []
  const all: Record<string, Handler> = {
    [PROJECT_PATH]: () => json(200, projectBody(projectStatus)),
    [MEMBERS]: () => page([member(ASHA, 'Asha'), member(RAVI, 'Ravi')]),
    ...routes,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url)
      const key = `${request.method} ${url.pathname.replace('/api/v1', '')}`
      const text = await request.clone().text()
      calls.push({ key, url, body: text ? JSON.parse(text) : undefined })
      const handler = all[key]
      return handler
        ? handler(request)
        : json(404, { title: 'Not Found', status: 404 })
    }),
  )
  return calls
}

function CurrentSearch() {
  return <output data-testid="search">{useLocation().search}</output>
}

function renderPage(role: Role = 'MEMBER', search = '') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const org = {
    id: ORG,
    name: 'Acme Labs',
    slug: 'acme-labs',
    role,
    createdBy: ASHA,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <QueryClientProvider client={client}>
      <CurrentOrgContext.Provider value={org}>
        <MemoryRouter initialEntries={[`/orgs/${ORG}/projects/${PROJECT}${search}`]}>
          <Routes>
            <Route path="/orgs/:orgId/projects/:projectId" element={<TaskListPage />} />
          </Routes>
          <CurrentSearch />
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

const lastList = (calls: ReturnType<typeof serve>) =>
  calls.filter((call) => call.key === LIST).at(-1)

describe('TaskListPage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('filters by status and assignee through the URL', async () => {
    const calls = serve({ [LIST]: () => page([task()]) })
    const user = userEvent.setup()

    renderPage('MEMBER', '?page=2')
    await screen.findByRole('option', { name: 'Ravi' })
    await user.selectOptions(screen.getByLabelText('Filter by status'), 'In progress')
    await user.selectOptions(screen.getByLabelText('Filter by assignee'), 'Ravi')

    await waitFor(() => {
      const params = new URLSearchParams(screen.getByTestId('search').textContent)
      expect(params.get('status')).toBe('IN_PROGRESS')
      expect(params.get('assignee')).toBe(RAVI)
      expect(params.get('page')).toBeNull()
    })
    await waitFor(() => {
      const params = lastList(calls)?.url.searchParams
      expect(params?.get('status')).toBe('IN_PROGRESS')
      expect(params?.get('assigneeId')).toBe(RAVI)
      expect(params?.get('page')).toBe('0')
    })
  })

  it('loads filters from the URL and ignores invalid values', async () => {
    const calls = serve({ [LIST]: () => page([task()]) })

    renderPage('MEMBER', `?status=NOPE&assignee=${RAVI}`)
    await screen.findByRole('button', { name: 'Write docs' })

    const params = lastList(calls)?.url.searchParams
    expect(params?.has('status')).toBe(false)
    expect(params?.get('assigneeId')).toBe(RAVI)
  })

  it('hides editing controls for a viewer', async () => {
    serve({ [LIST]: () => page([task()]) })
    const user = userEvent.setup()

    renderPage('VIEWER')
    await user.click(await screen.findByRole('button', { name: 'Write docs' }))
    const dialog = screen.getByRole('dialog', { name: 'Task details' })

    expect(within(dialog).getByText('First draft')).toBeInTheDocument()
    expect(within(dialog).queryByRole('textbox')).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Save changes' })).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Delete task' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'New task' })).toBeNull()
  })

  it('creates a task with trimmed text', async () => {
    const calls = serve({
      [LIST]: () => page([]),
      [CREATE]: () => json(201, task()),
    })
    const user = userEvent.setup()

    renderPage()
    await screen.findByRole('heading', { name: 'No tasks yet' })
    await user.click(screen.getByRole('button', { name: 'New task' }))
    const dialog = screen.getByRole('dialog', { name: 'New task' })
    await user.type(within(dialog).getByLabelText('Title'), '  Ship it  ')
    await user.click(within(dialog).getByRole('button', { name: 'Create task' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls.find((call) => call.key === CREATE)?.body).toEqual({
      title: 'Ship it',
      description: null,
      status: 'BACKLOG',
      priority: 'MEDIUM',
      assigneeId: null,
      dueDate: null,
    })
  })

  it('does not submit a blank title', async () => {
    const calls = serve({ [LIST]: () => page([]) })
    const user = userEvent.setup()

    renderPage()
    await screen.findByRole('heading', { name: 'No tasks yet' })
    await user.click(screen.getByRole('button', { name: 'New task' }))
    const dialog = screen.getByRole('dialog', { name: 'New task' })
    await user.click(within(dialog).getByRole('button', { name: 'Create task' }))

    expect(within(dialog).getByLabelText('Title')).toHaveAttribute('aria-invalid', 'true')
    expect(calls.some((call) => call.key === CREATE)).toBe(false)
  })

  it('sends null when the assignee is cleared', async () => {
    const calls = serve({
      [LIST]: () => page([task()]),
      [UPDATE]: () => json(200, task({ assigneeId: null, version: 3 })),
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Write docs' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit task' })
    await within(dialog).findByRole('option', { name: 'Ravi' })
    await user.selectOptions(within(dialog).getByLabelText('Assignee'), 'Unassigned')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls.find((call) => call.key === UPDATE)?.body).toEqual({
      assigneeId: null,
      version: 2,
    })
  })

  it('keeps typed text and offers reload on 409', async () => {
    serve({
      [LIST]: () => page([task()]),
      [UPDATE]: () =>
        json(409, {
          title: 'Conflict',
          status: 409,
          detail: 'The task was changed by someone else. Reload and try again.',
        }),
      [GET_TASK]: () => json(200, task({ title: 'Their title', version: 5 })),
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Write docs' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit task' })
    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'My unsaved title')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/changed by someone else/i)
    expect(title).toHaveValue('My unsaved title')

    await user.click(within(dialog).getByRole('button', { name: 'Reload latest' }))

    await waitFor(() => expect(title).toHaveValue('Their title'))
    expect(within(dialog).queryByRole('alert')).toBeNull()
  })

  it('keeps typed text when the project is archived during an edit', async () => {
    let projectStatus = 'ACTIVE'
    serve({
      [PROJECT_PATH]: () => json(200, projectBody(projectStatus)),
      [LIST]: () => page([task()]),
      [UPDATE]: () => {
        projectStatus = 'ARCHIVED'
        return json(409, {
          title: 'Conflict',
          status: 409,
          detail: 'The project is archived.',
        })
      },
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Write docs' }))
    const dialog = screen.getByRole('dialog', { name: 'Edit task' })
    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'My unsaved title')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await screen.findByText(/Its tasks are read-only/i)
    expect(await screen.findByRole('alert')).toHaveTextContent('The project is archived.')
    expect(screen.getByLabelText('Title')).toHaveValue('My unsaved title')
  })

  it('confirms before permanent delete', async () => {
    let tasks = [task()]
    const calls = serve({
      [LIST]: () => page(tasks),
      [DELETE]: () => {
        tasks = []
        return new Response(null, { status: 204 })
      },
    })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Write docs' }))
    await user.click(screen.getByRole('button', { name: 'Delete task' }))
    const confirm = screen.getByRole('dialog', { name: 'Delete Write docs?' })
    expect(confirm).toHaveTextContent(/can’t be undone/i)
    expect(calls.some((call) => call.key === DELETE)).toBe(false)

    await user.click(within(confirm).getByRole('button', { name: 'Delete permanently' }))

    expect(await screen.findByRole('heading', { name: 'No tasks yet' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(calls.filter((call) => call.key === DELETE)).toHaveLength(1)
  })

  it('does not delete when confirmation is cancelled', async () => {
    const calls = serve({ [LIST]: () => page([task()]) })
    const user = userEvent.setup()

    renderPage()
    await user.click(await screen.findByRole('button', { name: 'Write docs' }))
    await user.click(screen.getByRole('button', { name: 'Delete task' }))
    const confirm = screen.getByRole('dialog', { name: 'Delete Write docs?' })
    await user.click(within(confirm).getByRole('button', { name: 'Keep task' }))

    expect(screen.queryByRole('dialog', { name: 'Delete Write docs?' })).toBeNull()
    expect(screen.getByRole('dialog', { name: 'Edit task' })).toBeInTheDocument()
    expect(calls.some((call) => call.key === DELETE)).toBe(false)
  })

  it('disables task changes in an archived project', async () => {
    serve({ [LIST]: () => page([task()]) }, 'ARCHIVED')
    const user = userEvent.setup()

    renderPage('OWNER')

    expect(await screen.findByText(/project is archived/i)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'New task' })).toBeDisabled()
    await user.click(await screen.findByRole('button', { name: 'Write docs' }))
    const dialog = screen.getByRole('dialog', { name: 'Task details' })
    expect(within(dialog).queryByRole('textbox')).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Save changes' })).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Delete task' })).toBeNull()
  })

  it('moves back a page when the current page is empty', async () => {
    const calls = serve({
      [LIST]: (request) =>
        new URL(request.url).searchParams.get('page') === '1'
          ? page([], 1, 20)
          : page([task()], 0, 20),
    })

    renderPage('MEMBER', '?page=2')

    expect(await screen.findByRole('button', { name: 'Write docs' })).toBeInTheDocument()
    expect(lastList(calls)?.url.searchParams.get('page')).toBe('0')
  })

  it('shows not found for a missing project', async () => {
    serve({
      [PROJECT_PATH]: () => json(404, { title: 'Not Found', status: 404 }),
      [LIST]: () => json(404, { title: 'Not Found', status: 404 }),
    })

    renderPage()

    expect(
      await screen.findByRole('heading', { name: 'Project not found' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to projects' })).toHaveAttribute(
      'href',
      `/orgs/${ORG}`,
    )
  })
})
