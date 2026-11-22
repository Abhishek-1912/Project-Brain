import { Link, Outlet, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { OrganizationSwitcher } from '../features/organizations/OrganizationSwitcher'
import {
  CurrentOrgContext,
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
