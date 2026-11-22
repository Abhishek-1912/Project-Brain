import { createClient } from '@supabase/supabase-js'
import { useSyncExternalStore } from 'react'

export const supabase = createClient(
  import.meta.env.VITE_SUPABASE_URL,
  import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY,
)

let recovering = false
const listeners = new Set<() => void>()

function setRecovering(next: boolean) {
  if (recovering === next) return
  recovering = next
  listeners.forEach((listener) => listener())
}

// Subscribed at load because the recovery event can fire before any page mounts.
supabase.auth.onAuthStateChange((event) => {
  if (event === 'PASSWORD_RECOVERY') setRecovering(true)
  if (event === 'USER_UPDATED' || event === 'SIGNED_OUT') setRecovering(false)
})

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function usePasswordRecovery() {
  return useSyncExternalStore(subscribe, () => recovering)
}
