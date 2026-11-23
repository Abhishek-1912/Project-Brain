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
const quietButton = 'text-sm text-text-secondary hover:text-text-primary'

export function InviteAcceptPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const { user, loading, signOut } = useAuth()
  const [token] = useState(() => {
    const fromLink = tokenFromHash(location.hash)
    if (fromLink) savePendingInvite(fromLink)
    return fromLink ?? readPendingInvite()
  })
  const [accepting, setAccepting] = useState(false)
  const [rejectedFor, setRejectedFor] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const rejected = Boolean(user && rejectedFor === user.id)

  // Removes the token from the address bar and browser history entry.
  useEffect(() => {
    if (location.hash) {
      navigate(
        { pathname: location.pathname, search: location.search, hash: '' },
        { replace: true, state: location.state },
      )
    }
  }, [location, navigate])

  // Signing out from this page means switching accounts, so the invite stays available.
  useEffect(() => {
    if (token && !user && !loading) savePendingInvite(token)
  }, [token, user, loading])

  async function accept() {
    if (!token || !user) return
    setError(null)
    setAccepting(true)
    try {
      const { data } = await api.POST('/invites/accept', { body: { token } })
      clearPendingInvite()
      navigate(`/orgs/${data!.organizationId}`, { replace: true })
    } catch (caught) {
      setAccepting(false)
      if (caught instanceof ApiError && caught.status === 404) {
        setRejectedFor(user.id)
      } else {
        setError('The invite wasn’t accepted. Check your connection and try again.')
      }
    }
  }

  function leave() {
    clearPendingInvite()
    navigate('/', { replace: true })
  }

  if (loading) return <LoadingState label="Loading invite" />

  if (!token) {
    return (
      <ErrorState
        title="This invite isn’t valid"
        description="The link is incomplete. Ask for a new invite."
        action={<Link to="/">Go to your organizations</Link>}
      />
    )
  }

  if (rejected) {
    return (
      <ErrorState
        title="This invite isn’t valid"
        description={`You’re signed in as ${user?.email}. If you were invited with another email, use that account. Otherwise the invite may have expired or been used, so ask for a new one.`}
        action={
          <div className="flex flex-wrap gap-4">
            <button type="button" className={quietButton} onClick={() => void signOut()}>
              Use a different account
            </button>
            <button type="button" className={quietButton} onClick={leave}>
              Not now
            </button>
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
          <div className="mt-4 flex justify-between gap-4">
            <button type="button" className={quietButton} onClick={() => void signOut()}>
              Use a different account
            </button>
            <button type="button" className={quietButton} onClick={leave}>
              Not now
            </button>
          </div>
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
