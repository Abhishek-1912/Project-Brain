import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { primaryButton, quietButton } from '../requirements/TestModeBanner'
import {
  SCOPE_LABELS,
  claudeCommand,
  useCreateAccessToken,
  type CreatedAccessToken,
  type TokenScope,
} from './useAccessTokens'

const fieldClass =
  'w-full rounded-md border border-border bg-surface-low px-3 py-2 text-text-primary aria-invalid:border-error'

interface CreateTokenDialogProps {
  orgId: string
  onCreated(token: CreatedAccessToken): void
  onClose(): void
}

export function CreateTokenDialog({ orgId, onCreated, onClose }: CreateTokenDialogProps) {
  const create = useCreateAccessToken(orgId)
  const [name, setName] = useState('')
  const [scope, setScope] = useState<TokenScope>('READ')
  const [days, setDays] = useState<7 | 30 | 90>(30)
  const [nameError, setNameError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) {
      setNameError('Enter a name.')
      return
    }
    setNameError(null)
    setFormError(null)
    try {
      onCreated(await create.mutateAsync({ name: trimmed, scope, expiresInDays: days }))
    } catch (error) {
      setFormError(
        error instanceof ApiError ? error.message : 'The token wasn’t created. Check your connection, then try again.',
      )
    }
  }

  return (
    <Dialog title="Create token" onClose={onClose}>
      <form className="mt-5 flex flex-col gap-4" noValidate onSubmit={submit}>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="token-name" className="text-sm font-medium text-text-primary">
            Name
          </label>
          <input
            id="token-name"
            className={fieldClass}
            autoFocus
            maxLength={100}
            placeholder="Claude Code on my laptop"
            value={name}
            aria-invalid={nameError ? true : undefined}
            aria-describedby={nameError ? 'token-name-error' : undefined}
            onChange={(event) => {
              setName(event.target.value)
              setNameError(null)
            }}
          />
          {nameError && (
            <p id="token-name-error" className="text-sm text-error-text">
              {nameError}
            </p>
          )}
        </div>
        <fieldset className="flex flex-col gap-1.5">
          <legend className="text-sm font-medium text-text-primary">Access</legend>
          {(Object.keys(SCOPE_LABELS) as TokenScope[]).map((value) => (
            <label key={value} className="flex items-center gap-2 text-sm">
              <input type="radio" name="token-scope" checked={scope === value} onChange={() => setScope(value)} />
              {SCOPE_LABELS[value]}
            </label>
          ))}
          <p className="text-xs text-text-meta">
            Read and write lets the tool create tasks and add notes, within your role’s permissions.
          </p>
        </fieldset>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="token-expiry" className="text-sm font-medium text-text-primary">
            Expires after
          </label>
          <select
            id="token-expiry"
            className={fieldClass}
            value={days}
            onChange={(event) => setDays(Number(event.target.value) as 7 | 30 | 90)}
          >
            <option value={7}>7 days</option>
            <option value={30}>30 days</option>
            <option value={90}>90 days</option>
          </select>
        </div>
        {formError && (
          <p role="alert" className="text-sm text-error-text">
            {formError}
          </p>
        )}
        <div className="mt-1 flex justify-end gap-3">
          <button type="button" className={quietButton} onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className={primaryButton} disabled={create.isPending}>
            Create token
          </button>
        </div>
      </form>
    </Dialog>
  )
}

export function CreatedTokenDialog({ token, onClose }: { token: CreatedAccessToken; onClose(): void }) {
  const [copied, setCopied] = useState<'token' | 'command' | null>(null)
  const command = claudeCommand(token.token)

  async function copy(text: string, what: 'token' | 'command') {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(what)
    } catch {
      setCopied(null)
    }
  }

  return (
    <Dialog title="Copy your token" onClose={onClose}>
      <p className="mt-3 text-sm leading-6">
        Copy it now. It won’t be shown again. Anyone with this token can act as you in this organization until it
        expires or you revoke it.
      </p>
      <code className="mt-3 block rounded-md bg-surface-low px-3 py-2 font-mono text-sm break-all text-text-primary">
        {token.token}
      </code>
      <h3 className="mt-4 text-sm font-medium text-text-primary">Add Project Brain to Claude Code</h3>
      <code className="mt-1 block rounded-md bg-surface-low px-3 py-2 font-mono text-xs break-all text-text-primary">
        {command}
      </code>
      <p aria-live="polite" className="mt-2 min-h-5 text-xs text-info">
        {copied === 'token' ? 'Token copied.' : copied === 'command' ? 'Command copied.' : ''}
      </p>
      <div className="mt-3 flex flex-wrap justify-end gap-3">
        <button type="button" className={quietButton} onClick={() => copy(token.token, 'token')}>
          Copy token
        </button>
        <button type="button" className={quietButton} onClick={() => copy(command, 'command')}>
          Copy command
        </button>
        <button type="button" className={primaryButton} onClick={onClose}>
          Done
        </button>
      </div>
    </Dialog>
  )
}
