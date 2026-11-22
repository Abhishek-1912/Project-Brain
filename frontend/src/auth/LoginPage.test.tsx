import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LoginPage } from './LoginPage'

const auth = vi.hoisted(() => ({ signInWithPassword: vi.fn() }))

vi.mock('./supabase', () => ({ supabase: { auth } }))

function renderLogin() {
  render(
    <MemoryRouter
      initialEntries={[
        { pathname: '/login', state: { from: { pathname: '/projects' } } },
      ]}
    >
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/projects" element={<p>Projects screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

async function submit() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), 'dev@example.com')
  await user.type(screen.getByLabelText('Password'), 'test-password')
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('LoginPage', () => {
  beforeEach(() => auth.signInWithPassword.mockReset())

  it('returns to the requested page after signing in', async () => {
    auth.signInWithPassword.mockResolvedValue({ error: null })

    renderLogin()
    await submit()

    expect(await screen.findByText('Projects screen')).toBeInTheDocument()
    expect(auth.signInWithPassword).toHaveBeenCalledWith({
      email: 'dev@example.com',
      password: 'test-password',
    })
  })

  it('shows an error and stays on the page when sign-in fails', async () => {
    auth.signInWithPassword.mockResolvedValue({
      error: { message: 'Invalid login credentials' },
    })

    renderLogin()
    await submit()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid login credentials',
    )
    expect(screen.queryByText('Projects screen')).not.toBeInTheDocument()
  })
})
