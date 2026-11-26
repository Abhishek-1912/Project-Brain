import { Link, Outlet, useLocation, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { OrganizationSwitcher } from '../features/organizations/OrganizationSwitcher'
import { useMyReviews } from '../features/reviews/useReviews'
import {
  CurrentOrgContext,
  useCurrentOrg,
  useOrganization,
  type Role,
} from '../features/organizations/useOrganizations'
import { ErrorState } from './ErrorState'
import { LoadingState } from './LoadingState'

const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
}

function OrganizationNav() {
  const { org, can } = useCurrentOrg()
  const { pathname } = useLocation()
  const reviews = useMyReviews(org.id, 0, 1)
  const reviewCount = reviews.data?.totalItems ?? 0
  const reviewsPath = `/orgs/${org.id}/reviews`
  const teamPath = `/orgs/${org.id}/team`
  const auditPath = `/orgs/${org.id}/audit`
  const tokensPath = `/orgs/${org.id}/access-tokens`
  const section =
    pathname === auditPath
      ? 'audit'
      : pathname === tokensPath
        ? 'tokens'
      : pathname === teamPath
        ? 'team'
        : pathname === reviewsPath
          ? 'reviews'
          : 'projects'
  const itemProps = (name: string) => ({
    'aria-current': section === name ? ('page' as const) : undefined,
    className: `rounded-md px-2 py-1.5 text-sm ${
      section === name
        ? 'bg-primary-dim font-medium text-text-primary'
        : 'text-text-secondary hover:bg-surface hover:text-text-primary'
    }`,
  })

  return (
    <nav aria-label="Main" className="flex flex-row flex-wrap gap-1 md:flex-col">
      <Link to={`/orgs/${org.id}`} {...itemProps('projects')}>
        Projects
      </Link>
      <Link
        to={reviewsPath}
        aria-label={reviewCount > 0 ? `My reviews, ${reviewCount} waiting` : 'My reviews'}
        {...itemProps('reviews')}
      >
        <span className="flex items-center justify-between gap-2">
          My reviews
          {reviewCount > 0 && (
            <span className="rounded-full bg-primary px-1.5 text-xs font-bold text-on-primary">
              {reviewCount}
            </span>
          )}
        </span>
      </Link>
      <Link to={teamPath} {...itemProps('team')}>
        Team
      </Link>
      {can('VIEW_AUDIT') && (
        <Link to={auditPath} {...itemProps('audit')}>
          Audit log
        </Link>
      )}
      <Link to={tokensPath} {...itemProps('tokens')}>
        Access tokens
      </Link>
    </nav>
  )
}

export function AppLayout() {
  const { orgId = '' } = useParams()
  const { user, signOut } = useAuth()
  const { data: org, error, isPending, isFetching, refetch } = useOrganization(orgId)

  if (isPending) return <LoadingState label="Loading organization" />

  if (error || !org) {
    // A malformed ID gets 400, and an inaccessible organization gets 404.
    if (error instanceof ApiError && [400, 404].includes(error.status)) {
      return (
        <ErrorState
          title="Organization not found"
          description="It doesn’t exist, or your account isn’t a member."
          action={<Link to="/">Go to your organizations</Link>}
        />
      )
    }
    return (
      <ErrorState
        title="Organization didn’t load"
        description="Check your connection, then try again."
        action={
          <button
            type="button"
            disabled={isFetching}
            className="rounded-md bg-primary px-3 py-2 font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60"
            onClick={() => void refetch()}
          >
            Try again
          </button>
        }
      />
    )
  }

  return (
    <CurrentOrgContext.Provider value={org}>
      <div className="flex min-h-svh flex-col md:grid md:grid-cols-[16rem_minmax(0,1fr)]">
        <aside className="flex flex-col gap-5 border-b border-border bg-surface-low p-4 md:sticky md:top-0 md:h-svh md:border-r md:border-b-0">
          <p className="text-sm font-semibold text-primary">Project Brain</p>
          <OrganizationSwitcher />
          <OrganizationNav />
          <Link to="/orgs/new" className="text-sm">
            Create organization
          </Link>
          <div className="flex items-center justify-between gap-3 border-t border-border pt-4 text-sm md:mt-auto">
            <span className="min-w-0 truncate text-text-meta" title={user?.email}>
              {user?.email}
            </span>
            <button
              type="button"
              className="shrink-0 text-text-secondary hover:text-text-primary"
              onClick={() => void signOut()}
            >
              Sign out
            </button>
          </div>
        </aside>

        <main className="min-w-0 flex-1 bg-surface-dim px-4 py-8 md:px-10 md:py-10">
          <header className="mb-8 flex flex-wrap items-baseline gap-x-3 gap-y-1">
            <h1 className="text-3xl font-semibold tracking-tight break-words text-text-primary">
              {org.name}
            </h1>
            <span className="text-sm text-text-meta">
              Your role: {ROLE_LABELS[org.role]}
            </span>
          </header>
          <Outlet />
        </main>
      </div>
    </CurrentOrgContext.Provider>
  )
}
