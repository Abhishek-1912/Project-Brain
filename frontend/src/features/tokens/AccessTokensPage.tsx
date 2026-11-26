import { useState } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { EmptyState } from '../../components/EmptyState'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { primaryButton, quietButton } from '../requirements/TestModeBanner'
import { CreatedTokenDialog, CreateTokenDialog } from './CreateTokenDialog'
import {
  SCOPE_LABELS,
  TOKEN_STATUS,
  useAccessTokens,
  useRevokeAccessToken,
  type AccessToken,
  type CreatedAccessToken,
} from './useAccessTokens'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

type Panel = { kind: 'create' } | { kind: 'created'; token: CreatedAccessToken } | { kind: 'revoke'; token: AccessToken }

export function AccessTokensPage() {
  const { org } = useCurrentOrg()
  const tokens = useAccessTokens(org.id)
  const [panel, setPanel] = useState<Panel | null>(null)
  const close = () => setPanel(null)

  return (
    <section>
      <div className="mb-2 flex min-h-9 flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-text-primary">Access tokens</h2>
        <button type="button" className={primaryButton} onClick={() => setPanel({ kind: 'create' })}>
          Create token
        </button>
      </div>
      <p className="mb-5 max-w-prose text-sm leading-6">
        Personal access tokens let MCP tools such as Claude Code act as you in {org.name}. Each token works only here,
        with your current role, until it expires or you revoke it.
      </p>
      {tokens.isPending ? (
        <LoadingState label="Loading tokens" />
      ) : !tokens.data ? (
        <InlineAlert title="Tokens didn’t load" description="Check your connection, then try again." />
      ) : tokens.data.length === 0 ? (
        <EmptyState
          title="No access tokens yet"
          description="Create a token to connect Claude Code, Claude Desktop, or Cursor to this organization’s projects."
        />
      ) : (
        <ul className="divide-y divide-border border-y border-border">
          {tokens.data.map((token) => (
            <TokenRow key={token.id} token={token} onRevoke={() => setPanel({ kind: 'revoke', token })} />
          ))}
        </ul>
      )}
      {panel?.kind === 'create' && (
        <CreateTokenDialog
          orgId={org.id}
          onClose={close}
          onCreated={(token) => setPanel({ kind: 'created', token })}
        />
      )}
      {panel?.kind === 'created' && <CreatedTokenDialog token={panel.token} onClose={close} />}
      {panel?.kind === 'revoke' && <RevokeToken orgId={org.id} token={panel.token} onClose={close} />}
    </section>
  )
}

function TokenRow({ token, onRevoke }: { token: AccessToken; onRevoke(): void }) {
  const status = TOKEN_STATUS[token.status]
  return (
    <li className="flex flex-wrap items-center justify-between gap-3 py-3">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <span className="font-medium break-words text-text-primary">{token.name}</span>
          <span className="text-xs text-text-meta">{SCOPE_LABELS[token.scope]}</span>
          <span className={`rounded px-1.5 py-0.5 text-xs ${status.className}`}>{status.label}</span>
        </div>
        <p className="mt-0.5 font-mono text-xs text-text-meta">
          {token.displayPrefix}…, created {dateFormat.format(new Date(token.createdAt))}, expires{' '}
          {dateFormat.format(new Date(token.expiresAt))},{' '}
          {token.lastUsedAt ? `last used ${dateFormat.format(new Date(token.lastUsedAt))}` : 'never used'}
        </p>
      </div>
      {token.status === 'ACTIVE' && (
        <button type="button" className={quietButton} onClick={onRevoke}>
          Revoke
        </button>
      )}
    </li>
  )
}

function RevokeToken({ orgId, token, onClose }: { orgId: string; token: AccessToken; onClose(): void }) {
  const revoke = useRevokeAccessToken(orgId)
  const failure = revoke.error
    ? revoke.error instanceof ApiError
      ? revoke.error.message
      : 'The token wasn’t revoked. Check your connection, then try again.'
    : null

  return (
    <Dialog title="Revoke token?" onClose={onClose}>
      <p className="mt-3 text-sm leading-6">
        “{token.name}” will stop working immediately. Any tool using it will need a new token.
      </p>
      {failure && (
        <p role="alert" className="mt-3 text-sm text-error-text">
          {failure}
        </p>
      )}
      <div className="mt-5 flex justify-end gap-3">
        <button type="button" className={quietButton} onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="rounded-md bg-error px-3 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
          disabled={revoke.isPending}
          onClick={() => revoke.mutate(token.id, { onSuccess: onClose })}
        >
          Revoke token
        </button>
      </div>
    </Dialog>
  )
}
