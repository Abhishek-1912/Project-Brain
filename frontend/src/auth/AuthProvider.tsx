/* eslint-disable react-refresh/only-export-components */
import type { Session, User } from '@supabase/supabase-js'
import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { clearPendingInvite } from '../features/team/pendingInvite'
import { supabase } from './supabase'

interface AuthState {
  user: User | null
  session: Session | null
  loading: boolean
  signOut(): Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

async function signOut() {
  const { error } = await supabase.auth.signOut({ scope: 'local' })
  if (error) throw error
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true

    const { data } = supabase.auth.onAuthStateChange((event, next) => {
      // An invite in progress must not carry into the next account in this tab.
      if (event === 'SIGNED_OUT') clearPendingInvite()
      if (!active) return
      setSession(next)
      setLoading(false)
    })

    supabase.auth
      .getSession()
      .then(({ data: current }) => {
        if (active) setSession(current.session)
      })
      .catch(() => {
        if (active) setSession(null)
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
      data.subscription.unsubscribe()
    }
  }, [])

  const value = useMemo(
    () => ({ user: session?.user ?? null, session, loading, signOut }),
    [session, loading],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
