import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, type Location } from 'react-router-dom'
import { supabase } from './supabase'

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)
    const result = await supabase.auth.signInWithPassword({ email, password })
    setSubmitting(false)
    if (result.error) {
      setError(result.error.message)
      return
    }
    const from = (location.state as { from?: Location } | null)?.from
    navigate(from ?? '/', { replace: true })
  }

  return (
    <main className="mx-auto mt-24 w-full max-w-sm rounded-lg border border-border bg-surface p-6">
      <h1 className="mb-4 text-xl font-semibold text-text-primary">Sign in</h1>
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
            autoComplete="current-password"
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
          Sign in
        </button>
      </form>
      <nav className="mt-4 flex justify-between text-sm">
        <Link to="/signup">Create account</Link>
        <Link to="/reset-password">Forgot password?</Link>
      </nav>
    </main>
  )
}
