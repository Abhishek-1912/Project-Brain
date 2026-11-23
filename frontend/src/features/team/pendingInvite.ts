const STORAGE_KEY = 'project-brain.pending-invite'

// Session storage keeps the token through sign-in, and it ends with this browser tab.
export function readPendingInvite() {
  try {
    return sessionStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

export function savePendingInvite(token: string) {
  try {
    sessionStorage.setItem(STORAGE_KEY, token)
  } catch {
    // The invite still works on this page when storage is blocked.
  }
}

export function clearPendingInvite() {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // Nothing was stored.
  }
}

export function tokenFromHash(hash: string) {
  return new URLSearchParams(hash.replace(/^#/, '')).get('token')
}
