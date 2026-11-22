import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Link, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { ApiError } from './api/client'
import { useAuth } from './auth/AuthProvider'
import { LoginPage } from './auth/LoginPage'
import { RequireAuth } from './auth/RequireAuth'
import { ResetPasswordPage } from './auth/ResetPasswordPage'
import { SignUpPage } from './auth/SignUpPage'
import { AppLayout } from './components/AppLayout'
import { EmptyState } from './components/EmptyState'
import { ErrorState } from './components/ErrorState'
import { LoadingState } from './components/LoadingState'
import { CreateOrganizationPage } from './features/organizations/CreateOrganizationPage'
import { useOrganizations } from './features/organizations/useOrganizations'

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        // API errors are answers. Only a dropped connection is worth retrying.
        retry: (failures, error) => !(error instanceof ApiError) && failures < 1,
      },
    },
  })
}

function UserQueryClient({ children }: { children: ReactNode }) {
  const [client] = useState(createQueryClient)
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

// A new client per user keeps one account's data out of the next session.
function SignedInApp() {
  const { user } = useAuth()
  return (
    <UserQueryClient key={user?.id}>
      <Outlet />
    </UserQueryClient>
  )
}

function OrganizationIndex() {
  const { data, isPending, isFetching, refetch } = useOrganizations()

  if (isPending) return <LoadingState label="Loading organizations" />
  if (!data) {
    return (
      <ErrorState
        title="Organizations didn’t load"
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
  return <Navigate to={data[0] ? `/orgs/${data[0].id}` : '/orgs/new'} replace />
}

function OrganizationHome() {
  return (
    <EmptyState
      title="No project screens yet"
      description="Project lists and tasks will open here once they’re added."
    />
  )
}

function PageNotFound() {
  return (
    <ErrorState
      title="Page not found"
      description="This address doesn’t match a page in Project Brain."
      action={<Link to="/">Go to your organizations</Link>}
    />
  )
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignUpPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route
        element={
          <RequireAuth>
            <SignedInApp />
          </RequireAuth>
        }
      >
        <Route index element={<OrganizationIndex />} />
        <Route path="orgs/new" element={<CreateOrganizationPage />} />
        <Route path="orgs/:orgId" element={<AppLayout />}>
          <Route index element={<OrganizationHome />} />
        </Route>
        <Route path="*" element={<PageNotFound />} />
      </Route>
    </Routes>
  )
}
