import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../api/schema'
import { CurrentOrgContext } from '../organizations/useOrganizations'
import { TaskListPage } from '../tasks/TaskListPage'
import { MyReviewsPage } from './MyReviewsPage'

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
const BACKEND = '77777777-7777-4777-8777-777777777777'
const QA = '88888888-8888-4888-8888-888888888888'
const LEGACY = '99999999-9999-4999-8999-999999999999'
const BASE = `/organizations/${ORG}`
const TASK_PATH = `${BASE}/tasks/${TASK}`
const LIST = `GET ${BASE}/projects/${PROJECT}/tasks`
const CREATE = `POST ${BASE}/projects/${PROJECT}/tasks`
const MINE = `GET ${BASE}/reviews/mine`

const task = (overrides = {}) => ({
  id: TASK,
  organizationId: ORG,
  projectId: PROJECT,
  title: 'Add invite API',
  description: null,
  status: 'IN_PROGRESS',
  priority: 'MEDIUM',
  assigneeId: null,
  dueDate: null,
  responsibleJobRoleId: BACKEND,
  createdBy: ASHA,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-21T10:00:00Z',
  version: 4,
  ...overrides,
})

const jobRole = (id: string, name: string, active = true) => ({
  id,
  organizationId: ORG,
  name,
  active,
  createdAt: '2026-11-20T10:00:00Z',
  updatedAt: '2026-11-20T10:00:00Z',
  version: 0,
})

const queueItem = (overrides = {}) => ({
  task: task({ status: 'IN_REVIEW' }),
  projectName: 'Customer portal',
  jobRoleName: 'Backend',
  submittedBy: ASHA,
  submittedByName: 'Asha',
  submittedAt: '2026-11-22T10:00:00Z',
  approvalBasis: 'ROLE_HOLDER',
  selfApproval: false,
  ...overrides,
})

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const page = (items: unknown[]) =>
  json(200, { items, page: 0, size: 100, totalItems: items.length })

type Handler = (request: Request) => Response

function serve(routes: Record<string, Handler>) {
  const calls: { key: string; body: unknown }[] = []
  const all: Record<string, Handler> = {
    [`GET ${BASE}/projects/${PROJECT}`]: () =>
      json(200, {
        id: PROJECT,
        organizationId: ORG,
        name: 'Customer portal',
        description: null,
        status: 'ACTIVE',
        createdBy: ASHA,
        createdAt: '2026-11-20T10:00:00Z',
        updatedAt: '2026-11-21T10:00:00Z',
        version: 1,
      }),
    [`GET ${BASE}/members`]: () =>
      page([{ userId: ASHA, email: 'asha@example.com', displayName: 'Asha', role: 'OWNER', version: 0 }]),
    [`GET ${BASE}/job-roles`]: () =>
      page([jobRole(BACKEND, 'Backend'), jobRole(LEGACY, 'Legacy', false), jobRole(QA, 'QA')]),
    [`GET ${TASK_PATH}/reviews`]: () => page([]),
    [MINE]: () => page([]),
    ...routes,
  }
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const { pathname } = new URL(request.url)
      const key = `${request.method} ${pathname.replace('/api/v1', '')}`
      const text = await request.clone().text()
      calls.push({ key, body: text ? JSON.parse(text) : undefined })
      const handler = all[key]
      return handler ? handler(request) : json(404, { title: 'Not Found', status: 404 })
    }),
  )
  return calls
}

function renderApp(path: string, role: Role = 'MEMBER', allowSelfApproval = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const org = {
    id: ORG,
    name: 'Acme Labs',
    slug: 'acme-labs',
    allowSelfApproval,
    role,
    createdBy: ASHA,
    createdAt: '2026-11-20T10:00:00Z',
    updatedAt: '2026-11-20T10:00:00Z',
    version: 0,
  }
  render(
    <QueryClientProvider client={client}>
      <CurrentOrgContext.Provider value={org}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/orgs/:orgId/projects/:projectId" element={<TaskListPage />} />
            <Route path="/orgs/:orgId/reviews" element={<MyReviewsPage />} />
          </Routes>
        </MemoryRouter>
      </CurrentOrgContext.Provider>
    </QueryClientProvider>,
  )
}

const renderTasks = (role: Role = 'MEMBER') => renderApp(`/orgs/${ORG}/projects/${PROJECT}`, role)

async function openTask(user: ReturnType<typeof userEvent.setup>, name = 'Add invite API') {
  await user.click(await screen.findByRole('button', { name }))
  return screen.getByRole('dialog', { name: 'Edit task' })
}

describe('task review flow', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('requires a responsible role on a new task', async () => {
    const calls = serve({
      [LIST]: () => page([]),
      [CREATE]: () => json(201, task()),
    })
    const user = userEvent.setup()

    renderTasks()
    await user.click(await screen.findByRole('button', { name: 'New task' }))
    const dialog = screen.getByRole('dialog', { name: 'New task' })
    await within(dialog).findByRole('option', { name: 'Backend' })
    expect(within(dialog).queryByRole('option', { name: 'Legacy' })).toBeNull()
    await user.type(within(dialog).getByLabelText('Title'), 'Ship it')
    await user.click(within(dialog).getByRole('button', { name: 'Create task' }))

    expect(within(dialog).getByLabelText('Responsible role')).toHaveAttribute('aria-invalid', 'true')
    expect(calls.some((call) => call.key === CREATE)).toBe(false)

    await user.selectOptions(within(dialog).getByLabelText('Responsible role'), 'Backend')
    await user.click(within(dialog).getByRole('button', { name: 'Create task' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls.find((call) => call.key === CREATE)?.body).toEqual({
      title: 'Ship it',
      description: null,
      status: 'BACKLOG',
      priority: 'MEDIUM',
      assigneeId: null,
      dueDate: null,
      responsibleJobRoleId: BACKEND,
    })
  })

  it('hides review status options from a task with a role', async () => {
    serve({ [LIST]: () => page([task()]) })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    const options = within(within(dialog).getByLabelText('Status')).getAllByRole('option')

    expect(options.map((option) => option.textContent)).toEqual(['Backlog', 'To do', 'In progress'])
  })

  it('submits, withdraws and reopens a task', async () => {
    let current = task()
    const transition = (status: string) => () => {
      current = task({ status, version: current.version + 1 })
      return json(200, current)
    }
    const calls = serve({
      [LIST]: () => page([current]),
      [`POST ${TASK_PATH}/submit`]: transition('IN_REVIEW'),
      [`POST ${TASK_PATH}/withdraw`]: transition('IN_PROGRESS'),
      [`POST ${TASK_PATH}/reopen`]: transition('IN_PROGRESS'),
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    await user.click(within(dialog).getByRole('button', { name: 'Submit for review' }))
    await user.click(await within(dialog).findByRole('button', { name: 'Withdraw' }))
    await within(dialog).findByRole('button', { name: 'Submit for review' })

    expect(calls.find((call) => call.key === `POST ${TASK_PATH}/submit`)?.body).toEqual({ version: 4 })
    expect(calls.find((call) => call.key === `POST ${TASK_PATH}/withdraw`)?.body).toEqual({ version: 5 })
  })

  it('locks a done task until it is reopened', async () => {
    let current = task({ status: 'DONE' })
    serve({
      [LIST]: () => page([current]),
      [`POST ${TASK_PATH}/reopen`]: () => {
        current = task({ status: 'IN_PROGRESS', version: 5 })
        return json(200, current)
      },
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    expect(within(dialog).getByLabelText('Title')).toBeDisabled()
    expect(within(dialog).queryByRole('button', { name: 'Save changes' })).toBeNull()

    await user.click(within(dialog).getByRole('button', { name: 'Reopen' }))

    await waitFor(() => expect(within(dialog).getByLabelText('Title')).toBeEnabled())
  })

  it('asks to save edits before submitting', async () => {
    serve({ [LIST]: () => page([task()]) })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    await user.type(within(dialog).getByLabelText('Title'), ' v2')

    expect(within(dialog).getByRole('button', { name: 'Submit for review' })).toBeDisabled()
    expect(within(dialog).getByText(/save your changes/i)).toBeInTheDocument()
  })

  it('shows approve and reject only for a task in my queue', async () => {
    serve({ [LIST]: () => page([task({ status: 'IN_REVIEW' })]) })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)

    await within(dialog).findByRole('button', { name: 'Withdraw' })
    expect(within(dialog).queryByRole('button', { name: 'Approve' })).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Reject' })).toBeNull()
  })

  it('approves a task from my queue', async () => {
    let current = task({ status: 'IN_REVIEW' })
    const calls = serve({
      [LIST]: () => page([current]),
      [MINE]: () => page(current.status === 'IN_REVIEW' ? [queueItem({ task: current })] : []),
      [`POST ${TASK_PATH}/approve`]: () => {
        current = task({ status: 'DONE', version: 5 })
        return json(200, current)
      },
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    await user.click(await within(dialog).findByRole('button', { name: 'Approve' }))

    await within(dialog).findByRole('button', { name: 'Reopen' })
    expect(calls.find((call) => call.key === `POST ${TASK_PATH}/approve`)?.body).toEqual({ version: 4 })
  })

  it('requires a comment before rejecting', async () => {
    let current = task({ status: 'IN_REVIEW' })
    const calls = serve({
      [LIST]: () => page([current]),
      [MINE]: () => page(current.status === 'IN_REVIEW' ? [queueItem({ task: current })] : []),
      [`POST ${TASK_PATH}/reject`]: () => {
        current = task({ status: 'IN_PROGRESS', version: 5 })
        return json(200, current)
      },
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    await user.click(await within(dialog).findByRole('button', { name: 'Reject' }))

    expect(within(dialog).getByLabelText('Review comment')).toHaveAttribute('aria-invalid', 'true')
    expect(calls.some((call) => call.key === `POST ${TASK_PATH}/reject`)).toBe(false)

    await user.type(within(dialog).getByLabelText('Review comment'), 'Fix expiry')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))

    await within(dialog).findByRole('button', { name: 'Submit for review' })
    expect(calls.find((call) => call.key === `POST ${TASK_PATH}/reject`)?.body).toEqual({
      version: 4,
      comment: 'Fix expiry',
    })
  })

  it('keeps the typed comment and offers reload on 409', async () => {
    const reviewing = task({ status: 'IN_REVIEW' })
    let approveCount = 0
    const calls = serve({
      [LIST]: () => page([reviewing]),
      [MINE]: () => page([queueItem({ task: reviewing })]),
      [`GET ${TASK_PATH}`]: () => json(200, task({ status: 'IN_REVIEW', title: 'Edited by Ravi', version: 7 })),
      [`POST ${TASK_PATH}/approve`]: () => {
        approveCount += 1
        return approveCount === 1
          ? json(409, {
              title: 'Conflict',
              status: 409,
              detail: 'The task was changed by someone else. Reload and try again.',
            })
          : json(200, task({ status: 'DONE', version: 8 }))
      },
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    const comment = await within(dialog).findByLabelText('Review comment')
    await user.type(comment, 'Looks right')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/changed by someone else/i)
    expect(within(dialog).getByLabelText('Review comment')).toHaveValue('Looks right')

    await user.click(within(dialog).getByRole('button', { name: 'Reload latest' }))

    await waitFor(() => expect(within(dialog).getByLabelText('Title')).toHaveValue('Edited by Ravi'))
    expect(within(dialog).getByLabelText('Review comment')).toHaveValue('Looks right')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() =>
      expect(calls.filter((call) => call.key === `POST ${TASK_PATH}/approve`).at(-1)?.body).toEqual({
        version: 7,
        comment: 'Looks right',
      }),
    )
  })

  it('keeps a typed comment when another reviewer decides first', async () => {
    let decided = false
    const reviewing = task({ status: 'IN_REVIEW' })
    serve({
      [LIST]: () => page([reviewing]),
      [MINE]: () => page(decided ? [] : [queueItem({ task: reviewing })]),
      [`POST ${TASK_PATH}/reject`]: () => {
        decided = true
        return json(409, {
          title: 'Conflict',
          status: 409,
          detail: 'The task was changed by someone else. Reload and try again.',
        })
      },
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    await user.type(await within(dialog).findByLabelText('Review comment'), 'A long rejection note')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))

    await waitFor(() => expect(within(dialog).queryByRole('button', { name: 'Reject' })).toBeNull())
    expect(within(dialog).getByLabelText('Review comment')).toHaveValue('A long rejection note')
  })

  it('tells an owner when nobody can approve', async () => {
    const reviewing = task({ status: 'IN_REVIEW' })
    serve({
      [LIST]: () => page([reviewing]),
      [`GET ${TASK_PATH}/reviews`]: () =>
        page([
          {
            id: 'r1',
            taskId: TASK,
            taskVersion: 4,
            action: 'SUBMITTED',
            actorId: ASHA,
            actorName: 'Asha',
            jobRoleId: BACKEND,
            jobRoleName: 'Backend',
            approvalBasis: null,
            selfApproved: false,
            comment: null,
            createdAt: '2026-11-21T10:00:00Z',
          },
        ]),
      [`GET ${BASE}/projects/${PROJECT}/team`]: () =>
        json(200, {
          members: [
            { userId: ASHA, email: 'asha@example.com', displayName: 'Asha', role: 'OWNER', jobRoleIds: [BACKEND] },
          ],
          jobRoles: [{ id: BACKEND, name: 'Backend', active: true, approvalBasis: 'ROLE_HOLDER' }],
        }),
    })
    const user = userEvent.setup()

    renderApp(`/orgs/${ORG}/projects/${PROJECT}`, 'OWNER', false)
    const dialog = await openTask(user)

    expect(await within(dialog).findByText(/nobody can approve this task/i)).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: 'Add a reviewer' })).toHaveAttribute(
      'href',
      `/orgs/${ORG}/projects/${PROJECT}/team`,
    )
    expect(within(dialog).getByRole('link', { name: 'Change self-approval' })).toHaveAttribute(
      'href',
      `/orgs/${ORG}/team`,
    )
  })

  it('can decide a queue item beyond the first lookup page', async () => {
    const reviewing = task({ status: 'IN_REVIEW' })
    serve({
      [MINE]: (request) =>
        new URL(request.url).searchParams.get('size') === '100'
          ? json(200, { items: [], page: 0, size: 100, totalItems: 101 })
          : json(200, { items: [queueItem({ task: reviewing })], page: 0, size: 20, totalItems: 101 }),
    })
    const user = userEvent.setup()

    renderApp(`/orgs/${ORG}/reviews`)
    const dialog = await openTask(user)

    expect(await within(dialog).findByRole('button', { name: 'Approve' })).toBeInTheDocument()
  })

  it('does not treat stored outer whitespace as an unsaved edit', async () => {
    serve({ [LIST]: () => page([task({ status: 'DONE', title: '  Add invite API  ', description: ' Notes ' })]) })
    const user = userEvent.setup()

    renderTasks()
    await user.click(await screen.findByRole('button', { name: /Add invite API/ }))
    const dialog = screen.getByRole('dialog', { name: 'Edit task' })

    expect(within(dialog).getByRole('button', { name: 'Reopen' })).toBeEnabled()
  })

  it('shows review history with former members', async () => {
    serve({
      [LIST]: () => page([task({ status: 'IN_REVIEW' })]),
      [`GET ${TASK_PATH}/reviews`]: () =>
        page([
          {
            id: 'r1',
            taskId: TASK,
            taskVersion: 3,
            action: 'REJECTED',
            actorId: ASHA,
            actorName: null,
            jobRoleId: BACKEND,
            jobRoleName: 'Backend',
            approvalBasis: 'OWNER_FALLBACK',
            selfApproved: false,
            comment: 'Expired links still work',
            createdAt: '2026-11-21T10:00:00Z',
          },
        ]),
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)

    expect(await within(dialog).findByText('Expired links still work')).toBeInTheDocument()
    expect(within(dialog).getByText(/Former member rejected/)).toBeInTheDocument()
    expect(within(dialog).getByText('Owner fallback')).toBeInTheDocument()
  })

  it('shows role holder, Owner fallback and self-approval reasons', async () => {
    const taskId = (suffix: string) => `cccccccc-cccc-4ccc-8ccc-${suffix.repeat(12)}`
    serve({
      [MINE]: () =>
        page([
          queueItem({ task: task({ id: taskId('1'), title: 'Holder task', status: 'IN_REVIEW' }) }),
          queueItem({
            task: task({ id: taskId('2'), title: 'Fallback task', status: 'IN_REVIEW' }),
            approvalBasis: 'OWNER_FALLBACK',
          }),
          queueItem({
            task: task({ id: taskId('3'), title: 'Own task', status: 'IN_REVIEW' }),
            selfApproval: true,
            submittedByName: null,
          }),
        ]),
    })

    renderApp(`/orgs/${ORG}/reviews`)

    const row = async (name: string) => (await screen.findByRole('button', { name })).closest('tr')!
    expect(await row('Holder task')).toHaveTextContent('Role holder')
    expect(await row('Fallback task')).toHaveTextContent('Owner fallback')
    expect(await row('Own task')).toHaveTextContent('Self-approval')
    expect(await row('Own task')).toHaveTextContent('Former member')
  })

  it('opens the review panel from my queue', async () => {
    serve({ [MINE]: () => page([queueItem()]) })
    const user = userEvent.setup()

    renderApp(`/orgs/${ORG}/reviews`)
    const dialog = await openTask(user)

    expect(await within(dialog).findByRole('button', { name: 'Approve' })).toBeInTheDocument()
  })

  it('shows an empty queue', async () => {
    serve({})

    renderApp(`/orgs/${ORG}/reviews`)

    expect(await screen.findByRole('heading', { name: 'Nothing to review' })).toBeInTheDocument()
  })

  it('keeps Phase 1 tasks without a role working', async () => {
    const calls = serve({
      [LIST]: () => page([task({ responsibleJobRoleId: null })]),
      [`PATCH ${TASK_PATH}`]: () => json(200, task({ responsibleJobRoleId: null, status: 'DONE', version: 5 })),
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    expect(within(dialog).queryByRole('button', { name: 'Submit for review' })).toBeNull()
    await within(dialog).findByRole('option', { name: 'Backend' })
    await user.selectOptions(within(dialog).getByLabelText('Status'), 'Done')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls.find((call) => call.key === `PATCH ${TASK_PATH}`)?.body).toEqual({
      status: 'DONE',
      version: 4,
    })
  })

  it('removes direct done status when a Phase 1 task receives a role', async () => {
    const calls = serve({
      [LIST]: () => page([task({ responsibleJobRoleId: null })]),
      [`PATCH ${TASK_PATH}`]: () => json(200, task({ version: 5 })),
    })
    const user = userEvent.setup()

    renderTasks()
    const dialog = await openTask(user)
    await within(dialog).findByRole('option', { name: 'Backend' })
    await user.selectOptions(within(dialog).getByLabelText('Status'), 'Done')
    await user.selectOptions(within(dialog).getByLabelText('Responsible role'), 'Backend')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls.find((call) => call.key === `PATCH ${TASK_PATH}`)?.body).toEqual({
      responsibleJobRoleId: BACKEND,
      version: 4,
    })
  })
})
