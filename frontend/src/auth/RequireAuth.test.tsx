import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider } from './AuthProvider'
import { RequireAuth } from './RequireAuth'

const auth = vi.hoisted(() => ({
  getSession: vi.fn(),
  onAuthStateChange: vi.fn(),
  signOut: vi.fn(),
}))

vi.mock('./supabase', () => ({ supabase: { auth } }))

const session = {
  access_token: 'token',
  user: { id: 'user-1', email: 'dev@example.com' },
}

function renderProtectedRoute() {
  render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/projects']}>
        <Routes>
          <Route path="/login" element={<p>Login screen</p>} />
          <Route
            path="/projects"
            element={
              <RequireAuth>
                <p>Protected content</p>
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('RequireAuth', () => {
  beforeEach(() => {
    auth.onAuthStateChange.mockReturnValue({
      data: { subscription: { unsubscribe: vi.fn() } },
    })
  })

  it('redirects a signed-out user to /login', async () => {
    auth.getSession.mockResolvedValue({ data: { session: null }, error: null })

    renderProtectedRoute()

    expect(await screen.findByText('Login screen')).toBeInTheDocument()
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument()
  })

  it('renders protected content for a signed-in user', async () => {
    auth.getSession.mockResolvedValue({ data: { session }, error: null })

    renderProtectedRoute()

    expect(await screen.findByText('Protected content')).toBeInTheDocument()
    expect(screen.queryByText('Login screen')).not.toBeInTheDocument()
  })

  it('does not redirect while the session is loading', () => {
    auth.getSession.mockReturnValue(new Promise(() => {}))

    renderProtectedRoute()

    expect(screen.queryByText('Login screen')).not.toBeInTheDocument()
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument()
  })
})
