import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { ErrorState } from '../../components/ErrorState'
import { LoadingState } from '../../components/LoadingState'
import {
  clearPendingInvite,
  readPendingInvite,
  savePendingInvite,
  tokenFromHash,
} from './pendingInvite'

const primaryButton =
  'rounded-md bg-primary px-4 py-2.5 text-center font-medium text-on-primary hover:bg-primary-hover hover:no-underline disabled:opacity-60'

export function InviteAcceptPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const { user, loading, signOut } = useAuth()
  const [token, setToken] = useState(() => {
    const fromLink = tokenFromHash(location.hash)
    if (fromLink) savePendingInvite(fromLink)
    return fromLink ?? readPendingInvite()
  })
  const [accepting, setAccepting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Removes the token from the address bar and browser history entry.
  useEffect(() => {
    if (location.hash) {
      navigate(
        { pathname: location.pathname, search: location.search, hash: '' },
        { replace: true, state: location.state },
      )
    }
  }, [location, navigate])

  async function accept() {
    if (!token) return
    setError(null)
    setAccepting(true)
    try {
      const { data } = await api.POST('/invites/accept', { body: { token } })
      clearPendingInvite()
      navigate(`/orgs/${data!.organizationId}`, { replace: true })
    } catch (caught) {
      setAccepting(false)
      if (caught instanceof ApiError && caught.status === 404) {
        clearPendingInvite()
        setToken(null)
      } else {
        setError('The invite wasn’t accepted. Check your connection and try again.')
      }
    }
  }

  if (loading) return <LoadingState label="Loading invite" />

  if (!token) {
    return (
      <ErrorState
        title="This invite isn’t valid"
        description={`It may have expired, been used or cancelled, or be for another email.${
          user ? ` You’re signed in as ${user.email}.` : ''
        } Ask for a new invite.`}
        action={
          <div className="flex gap-4">
            <Link to="/">Go to your organizations</Link>
            {user && (
              <button type="button" className="text-text-secondary hover:text-text-primary" onClick={() => void signOut()}>
                Sign out
              </button>
            )}
          </div>
        }
      />
    )
  }

  return (
    <main className="mx-auto flex min-h-svh w-full max-w-md flex-col justify-center px-4 py-10">
      <p className="mb-8 text-sm font-semibold text-primary">Project Brain</p>
      <h1 className="text-3xl font-semibold tracking-tight text-text-primary">
        You’ve been invited
      </h1>
      {user ? (
        <>
          <p className="mt-2 text-sm leading-6">
            You’ll join as {user.email}. The invite must be for this email.
          </p>
          {error && (
            <p role="alert" className="mt-4 text-sm text-error-text">
              {error}
            </p>
          )}
          <button
            type="button"
            disabled={accepting}
            className={`${primaryButton} mt-6`}
            onClick={() => void accept()}
          >
            Accept invite
          </button>
          <button
            type="button"
            className="mt-4 text-sm text-text-secondary hover:text-text-primary"
            onClick={() => void signOut()}
          >
            Use a different account
          </button>
        </>
      ) : (
        <>
          <p className="mt-2 text-sm leading-6">
            Sign in or create an account with the email that was invited. If you confirm a new
            account by email, open this invite link again afterward.
          </p>
          <div className="mt-6 flex flex-col gap-3">
            <Link to="/login" state={{ from: { pathname: '/invite' } }} className={primaryButton}>
              Sign in
            </Link>
            <Link to="/signup" className="text-center text-sm">
              Create account
            </Link>
          </div>
        </>
      )}
    </main>
  )
}
