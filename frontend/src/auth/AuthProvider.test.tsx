import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth } from './AuthProvider'

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

function Probe() {
  const { user, loading, signOut } = useAuth()
  if (loading) return <p>Loading</p>
  return (
    <>
      <p>{user?.email ?? 'Signed out'}</p>
      <button onClick={() => void signOut()}>Sign out</button>
    </>
  )
}

describe('AuthProvider', () => {
  const unsubscribe = vi.fn()

  beforeEach(() => {
    unsubscribe.mockReset()
    auth.signOut.mockReset()
    auth.signOut.mockResolvedValue({ error: null })
    auth.getSession.mockResolvedValue({ data: { session }, error: null })
    auth.onAuthStateChange.mockReturnValue({
      data: { subscription: { unsubscribe } },
    })
  })

  it('follows auth state changes', async () => {
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    expect(await screen.findByText('dev@example.com')).toBeInTheDocument()

    const onChange = auth.onAuthStateChange.mock.calls[0][0]
    act(() => onChange('SIGNED_OUT', null))

    expect(screen.getByText('Signed out')).toBeInTheDocument()
  })

  it('signs out of this device only', async () => {
    const user = userEvent.setup()
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )

    await user.click(await screen.findByRole('button', { name: 'Sign out' }))

    expect(auth.signOut).toHaveBeenCalledWith({ scope: 'local' })
  })

  it('stops listening on unmount', async () => {
    const { unmount } = render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    await screen.findByText('dev@example.com')

    unmount()

    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })
})
