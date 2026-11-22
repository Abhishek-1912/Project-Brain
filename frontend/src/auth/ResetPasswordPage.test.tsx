import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ResetPasswordPage } from './ResetPasswordPage'

const { auth, recovery } = vi.hoisted(() => ({
  auth: { resetPasswordForEmail: vi.fn(), updateUser: vi.fn() },
  recovery: { active: false },
}))

vi.mock('./supabase', () => ({
  supabase: { auth },
  usePasswordRecovery: () => recovery.active,
}))

function renderReset() {
  render(
    <MemoryRouter initialEntries={['/reset-password']}>
      <Routes>
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/" element={<p>Home screen</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    recovery.active = false
    auth.resetPasswordForEmail.mockReset()
    auth.updateUser.mockReset()
  })

  it('sends a reset link that returns to this page', async () => {
    auth.resetPasswordForEmail.mockResolvedValue({ error: null })
    const user = userEvent.setup()

    renderReset()
    await user.type(screen.getByLabelText('Email'), 'dev@example.com')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))

    expect(await screen.findByRole('status')).toHaveTextContent(/check your email/i)
    expect(auth.resetPasswordForEmail).toHaveBeenCalledWith('dev@example.com', {
      redirectTo: `${window.location.origin}/reset-password`,
    })
  })

  it('does not offer a password change without a recovery link', () => {
    renderReset()

    expect(screen.queryByLabelText('New password')).not.toBeInTheDocument()
  })

  it('updates the password during recovery', async () => {
    recovery.active = true
    auth.updateUser.mockResolvedValue({ error: null })
    const user = userEvent.setup()

    renderReset()
    await user.type(screen.getByLabelText('New password'), 'new-test-password')
    await user.click(screen.getByRole('button', { name: 'Update password' }))

    expect(await screen.findByText('Home screen')).toBeInTheDocument()
    expect(auth.updateUser).toHaveBeenCalledWith({
      password: 'new-test-password',
    })
  })

  it('shows an error when the password update fails', async () => {
    recovery.active = true
    auth.updateUser.mockResolvedValue({
      error: { message: 'Password is too weak' },
    })
    const user = userEvent.setup()

    renderReset()
    await user.type(screen.getByLabelText('New password'), 'weak')
    await user.click(screen.getByRole('button', { name: 'Update password' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Password is too weak',
    )
    expect(screen.queryByText('Home screen')).not.toBeInTheDocument()
  })
})
