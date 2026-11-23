import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { useCreateInvite, type InviteRole } from './useTeam'

const fieldClass =
  'w-full rounded-md border border-border bg-surface-low px-3 py-2 text-sm text-text-primary aria-invalid:border-error'
const labelClass = 'text-sm font-medium text-text-primary'
const quietButton = 'rounded-md px-3 py-2 text-sm text-text-secondary hover:text-text-primary'
const primaryButton =
  'rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60'

const ROLE_LABELS: Record<InviteRole, string> = {
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
}

interface InviteDialogProps {
  orgId: string
  canInviteAdmin: boolean
  onClose(): void
}

export function InviteDialog({ orgId, canInviteAdmin, onClose }: InviteDialogProps) {
  const createInvite = useCreateInvite(orgId)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<InviteRole>('MEMBER')
  const [emailError, setEmailError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [link, setLink] = useState<string | null>(null)
  const [copyMessage, setCopyMessage] = useState<string | null>(null)
  const roles: InviteRole[] = canInviteAdmin ? ['ADMIN', 'MEMBER', 'VIEWER'] : ['MEMBER', 'VIEWER']

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = email.trim()
    setFormError(null)
    if (!/^\S+@\S+$/.test(trimmed)) {
      setEmailError('Enter a valid email address.')
      return
    }
    setEmailError(null)
    try {
      const invite = await createInvite.mutateAsync({ email: trimmed, role })
      setLink(`${window.location.origin}/invite#token=${invite.token}`)
    } catch (error) {
      setFormError(
        error instanceof ApiError && error.problem.detail
          ? error.problem.detail
          : 'The invite wasn’t created. Check your connection and try again.',
      )
    }
  }

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(link ?? '')
      setCopyMessage('Copied')
    } catch {
      setCopyMessage('Select the link and copy it.')
    }
  }

  return (
    <Dialog title="Invite a person" onClose={onClose}>
      {link ? (
        <div className="mt-4 flex flex-col gap-3">
          <p className="text-sm leading-6">
            Share this link with {email.trim()}. It works once and expires in 7 days. You won’t see it again.
          </p>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="invite-link" className={labelClass}>
              Invite link
            </label>
            <input
              id="invite-link"
              readOnly
              className={`${fieldClass} font-mono text-xs`}
              value={link}
              onFocus={(event) => event.target.select()}
            />
          </div>
          {copyMessage && (
            <p role="status" className="text-sm text-text-meta">
              {copyMessage}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className={quietButton} onClick={() => void copyLink()}>
              Copy link
            </button>
            <button type="button" className={primaryButton} onClick={onClose}>
              Done
            </button>
          </div>
        </div>
      ) : (
        <form className="mt-4 flex flex-col gap-4" noValidate onSubmit={handleSubmit}>
          <p className="text-sm leading-6">
            You’ll get a link to share. It works once, for this email, and expires in 7 days.
          </p>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="invite-email" className={labelClass}>
              Email
            </label>
            <input
              id="invite-email"
              type="email"
              autoFocus
              maxLength={320}
              className={fieldClass}
              value={email}
              aria-invalid={emailError ? true : undefined}
              aria-describedby={emailError ? 'invite-email-error' : undefined}
              onChange={(event) => {
                setEmail(event.target.value)
                setEmailError(null)
              }}
            />
            {emailError && (
              <p id="invite-email-error" className="text-sm text-error-text">
                {emailError}
              </p>
            )}
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="invite-role" className={labelClass}>
              Access role
            </label>
            <select
              id="invite-role"
              className={fieldClass}
              value={role}
              onChange={(event) => setRole(event.target.value as InviteRole)}
            >
              {roles.map((item) => (
                <option key={item} value={item}>
                  {ROLE_LABELS[item]}
                </option>
              ))}
            </select>
          </div>
          {formError && (
            <p role="alert" className="text-sm text-error-text">
              {formError}
            </p>
          )}
          <div className="flex justify-end gap-2">
            <button type="button" className={quietButton} onClick={onClose}>
              Cancel
            </button>
            <button type="submit" disabled={createInvite.isPending} className={primaryButton}>
              Create invite link
            </button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
