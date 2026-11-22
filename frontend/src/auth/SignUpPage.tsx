import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { supabase } from './supabase'

export function SignUpPage() {
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    const result = await supabase.auth.signUp({ email, password })
    setSubmitting(false)
    if (result.error) {
      setError(result.error.message)
    } else if (result.data.session) {
      navigate('/', { replace: true })
    } else {
      setConfirming(true)
    }
  }

  return (
    <main className="mx-auto mt-24 w-full max-w-sm rounded-lg border border-border bg-surface p-6">
      <h1 className="mb-4 text-xl font-semibold text-text-primary">
        Create account
      </h1>
      {confirming ? (
        <p role="status">Check your email to confirm your account.</p>
      ) : (
        <form className="flex flex-col gap-3" onSubmit={handleSubmit}>
          <label className="flex flex-col gap-1">
            Email
            <input
              className="rounded border border-border bg-surface-low p-2 text-text-primary focus:outline-2 focus:outline-border-focus"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1">
            Password
            <input
              className="rounded border border-border bg-surface-low p-2 text-text-primary focus:outline-2 focus:outline-border-focus"
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
          <button
            className="rounded bg-primary p-2 font-medium text-on-primary hover:bg-primary-hover focus:outline-2 focus:outline-border-focus disabled:opacity-60"
            type="submit"
            disabled={submitting}
          >
            Create account
          </button>
        </form>
      )}
      <nav className="mt-4 text-sm">
        <Link to="/login">Back to sign in</Link>
      </nav>
    </main>
  )
}
