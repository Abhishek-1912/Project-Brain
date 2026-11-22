import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthProvider'
import { LoginPage } from './auth/LoginPage'
import { RequireAuth } from './auth/RequireAuth'
import { ResetPasswordPage } from './auth/ResetPasswordPage'
import { SignUpPage } from './auth/SignUpPage'

// Placeholder until the app shell lands in Task 10.
function Home() {
  const { user, signOut } = useAuth()
  return (
    <main className="p-6">
      <p>Signed in as {user?.email}</p>
      <button type="button" onClick={() => void signOut()}>
        Sign out
      </button>
    </main>
  )
}

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignUpPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route
            path="*"
            element={
              <RequireAuth>
                <Home />
              </RequireAuth>
            }
          />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
