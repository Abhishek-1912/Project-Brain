import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { SignUpPage } from './SignUpPage'

const auth = vi.hoisted(() => ({ signUp: vi.fn() }))

vi.mock('./supabase', () => ({ supabase: { auth } }))

function renderSignUp() {
  render(
    <MemoryRouter initialEntries={['/signup']}>
      <Routes>
        <Route path="/signup" element={<SignUpPage />} />
        <Route path="/" element={<p>Home screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

async function submit() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), 'dev@example.com')
  await user.type(screen.getByLabelText('Password'), 'test-password')
  await user.click(screen.getByRole('button', { name: 'Create account' }))
}

describe('SignUpPage', () => {
  beforeEach(() => auth.signUp.mockReset())

  it('opens the app when sign-up returns a session', async () => {
    auth.signUp.mockResolvedValue({
      data: { session: { access_token: 'token' } },
      error: null,
    })

    renderSignUp()
    await submit()

    expect(await screen.findByText('Home screen')).toBeInTheDocument()
  })

  it('asks the user to confirm their email when there is no session', async () => {
    auth.signUp.mockResolvedValue({ data: { session: null }, error: null })

    renderSignUp()
    await submit()

    expect(await screen.findByRole('status')).toHaveTextContent(/check your email/i)
    expect(screen.queryByText('Home screen')).not.toBeInTheDocument()
  })

  it('shows the sign-up error', async () => {
    auth.signUp.mockResolvedValue({
      data: { session: null },
      error: { message: 'Password is too weak' },
    })

    renderSignUp()
    await submit()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Password is too weak',
    )
  })
})
