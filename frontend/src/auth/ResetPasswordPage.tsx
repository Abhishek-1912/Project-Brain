import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { supabase, usePasswordRecovery } from './supabase'

const inputClass =
  'rounded border border-border bg-surface-low p-2 text-text-primary focus:outline-2 focus:outline-border-focus'
const buttonClass =
  'rounded bg-primary p-2 font-medium text-on-primary hover:bg-primary-hover focus:outline-2 focus:outline-border-focus disabled:opacity-60'

function RequestResetForm() {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    const result = await supabase.auth.resetPasswordForEmail(email, {
      redirectTo: `${window.location.origin}/reset-password`,
    })
    setSubmitting(false)
    if (result.error) setError(result.error.message)
    else setSent(true)
  }

  if (sent) {
    return (
      <p role="status">
        Check your email. If an account exists, a reset link is on its way.
      </p>
    )
  }

  return (
    <form className="flex flex-col gap-3" onSubmit={handleSubmit}>
      <label className="flex flex-col gap-1">
        Email
        <input
          className={inputClass}
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </label>
      {error && (
        <p role="alert" className="text-sm text-error-text">
          {error}
        </p>
      )}
      <button className={buttonClass} type="submit" disabled={submitting}>
        Send reset link
      </button>
    </form>
  )
}

function NewPasswordForm() {
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    const result = await supabase.auth.updateUser({ password })
    setSubmitting(false)
    if (result.error) setError(result.error.message)
    else navigate('/', { replace: true })
  }

  return (
    <form className="flex flex-col gap-3" onSubmit={handleSubmit}>
      <label className="flex flex-col gap-1">
        New password
        <input
          className={inputClass}
          type="password"
          autoComplete="new-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </label>
      {error && (
        <p role="alert" className="text-sm text-error-text">
          {error}
        </p>
      )}
      <button className={buttonClass} type="submit" disabled={submitting}>
        Update password
      </button>
    </form>
  )
}

export function ResetPasswordPage() {
  const recovering = usePasswordRecovery()

  return (
    <main className="mx-auto mt-24 w-full max-w-sm rounded-lg border border-border bg-surface p-6">
      <h1 className="mb-4 text-xl font-semibold text-text-primary">
        Reset password
      </h1>
      {recovering ? <NewPasswordForm /> : <RequestResetForm />}
      <nav className="mt-4 text-sm">
        <Link to="/login">Back to sign in</Link>
      </nav>
    </main>
  )
}
