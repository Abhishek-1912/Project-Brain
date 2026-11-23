import { useState } from 'react'
import { ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { Dialog } from '../../components/Dialog'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg, type Role } from '../organizations/useOrganizations'
import { memberName, useMembers, type Member } from '../tasks/useTasks'
import { InviteDialog } from './InviteDialog'
import {
  useCancelInvite,
  useInvites,
  useRemoveMember,
  useUpdateMember,
  type Invite,
} from './useTeam'

const primaryButton =
  'rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60'
const quietButton =
  'rounded px-2 py-1 text-sm text-text-secondary hover:bg-surface-bright hover:text-text-primary disabled:text-text-disabled'

const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
}
const ROLES = Object.keys(ROLE_LABELS) as Role[]
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' })

function apiMessage(error: unknown, fallback: string) {
  return error instanceof ApiError && error.problem.detail ? error.problem.detail : fallback
}

function RemoveMemberDialog({
  orgId,
  member,
  onClose,
}: {
  orgId: string
  member: Member
  onClose(): void
}) {
  const removeMember = useRemoveMember(orgId)
  const [error, setError] = useState<string | null>(null)

  async function remove() {
    setError(null)
    try {
      await removeMember.mutateAsync(member.userId)
      onClose()
    } catch (caught) {
      setError(apiMessage(caught, 'The member wasn’t removed. Check your connection and try again.'))
    }
  }

  return (
    <Dialog title={`Remove ${memberName(member)}?`} onClose={onClose}>
      <p className="mt-2 text-sm leading-6">
        They lose access to this organization, and their tasks become unassigned. Their review and audit history stays.
      </p>
      {error && (
        <p role="alert" className="mt-3 text-sm text-error-text">
          {error}
        </p>
      )}
      <div className="mt-5 flex justify-end gap-3">
        <button type="button" className="rounded-md px-3 py-2 text-sm text-text-secondary hover:text-text-primary" onClick={onClose}>
          Keep member
        </button>
        <button
          type="button"
          disabled={removeMember.isPending}
          className="rounded-md bg-error px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
          onClick={() => void remove()}
        >
          Remove member
        </button>
      </div>
    </Dialog>
  )
}

function InviteRow({
  orgId,
  invite,
  canCancel,
  onError,
}: {
  orgId: string
  invite: Invite
  canCancel: boolean
  onError(message: string): void
}) {
  const cancelInvite = useCancelInvite(orgId)

  return (
    <tr className="border-b border-hairline last:border-b-0">
      <td className="px-4 py-3 break-all text-text-primary">{invite.email}</td>
      <td className="px-4 py-3">{ROLE_LABELS[invite.role]}</td>
      <td className="px-4 py-3">{invite.status === 'EXPIRED' ? 'Expired' : 'Pending'}</td>
      <td className="hidden px-4 py-3 font-mono text-xs whitespace-nowrap text-text-meta sm:table-cell">
        {dateFormat.format(new Date(invite.expiresAt))}
      </td>
      <td className="px-4 py-2 text-right whitespace-nowrap">
        {canCancel && (
          <button
            type="button"
            aria-label={`Cancel invite for ${invite.email}`}
            disabled={cancelInvite.isPending}
            className={quietButton}
            onClick={() =>
              cancelInvite.mutate(invite.id, {
                onError: (error) =>
                  onError(apiMessage(error, 'The invite wasn’t cancelled. Check your connection and try again.')),
              })
            }
          >
            Cancel
          </button>
        )}
      </td>
    </tr>
  )
}

export function TeamPage() {
  const { org } = useCurrentOrg()
  return <OrganizationTeam key={org.id} />
}

function OrganizationTeam() {
  const { org, can } = useCurrentOrg()
  const { user } = useAuth()
  const canInvite = can('INVITE_MEMBERS')
  const canManage = can('MANAGE_MEMBERS')
  const members = useMembers(org.id)
  const invites = useInvites(org.id, canInvite)
  const updateMember = useUpdateMember(org.id)
  const [inviting, setInviting] = useState(false)
  const [removing, setRemoving] = useState<Member | null>(null)
  const [error, setError] = useState<string | null>(null)
  const ownerCount = members.data?.filter((member) => member.role === 'OWNER').length ?? 0

  function changeRole(member: Member, role: Role) {
    setError(null)
    updateMember.mutate(
      { userId: member.userId, body: { role, version: member.version } },
      {
        onError: (caught) =>
          setError(apiMessage(caught, 'The role wasn’t changed. Check your connection and try again.')),
      },
    )
  }

  return (
    <section>
      <div className="mb-4 flex min-h-9 items-center justify-between gap-3">
        <h2 className="text-lg font-semibold text-text-primary">Team</h2>
        {canInvite && (
          <button type="button" className={primaryButton} onClick={() => setInviting(true)}>
            Invite person
          </button>
        )}
      </div>

      {error && (
        <p role="alert" className="mb-4 rounded-md bg-error-tint px-3 py-2 text-sm text-error-text">
          {error}
        </p>
      )}

      {members.isPending ? (
        <LoadingState label="Loading team" />
      ) : !members.data ? (
        <InlineAlert title="Team didn’t load" description="Check your connection, then try again.">
          <button type="button" className={primaryButton} onClick={() => void members.refetch()}>
            Try again
          </button>
        </InlineAlert>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-surface">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border text-xs text-text-meta">
              <tr>
                <th scope="col" className="px-4 py-2.5 font-medium">Name</th>
                <th scope="col" className="hidden px-4 py-2.5 font-medium sm:table-cell">Email</th>
                <th scope="col" className="px-4 py-2.5 font-medium">Access role</th>
                {canManage && <th scope="col" className="px-4 py-2.5 text-right font-medium">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {members.data.map((member) => {
                const name = memberName(member)
                const lastOwner = member.role === 'OWNER' && ownerCount <= 1
                return (
                  <tr key={member.userId} className="border-b border-hairline last:border-b-0">
                    <td className="px-4 py-3 font-medium break-words text-text-primary">
                      {name}
                      {member.userId === user?.id && (
                        <span className="ml-2 text-xs font-normal text-text-meta">You</span>
                      )}
                    </td>
                    <td className="hidden px-4 py-3 break-all text-text-meta sm:table-cell">{member.email}</td>
                    <td className="px-4 py-2">
                      {canManage ? (
                        <select
                          aria-label={`Access role for ${name}`}
                          title={lastOwner ? 'An organization must keep at least one owner.' : undefined}
                          disabled={lastOwner || updateMember.isPending}
                          className="rounded-md border border-border bg-surface-low px-2 py-1.5 text-sm text-text-primary disabled:text-text-disabled"
                          value={member.role}
                          onChange={(event) => changeRole(member, event.target.value as Role)}
                        >
                          {ROLES.map((role) => (
                            <option key={role} value={role}>
                              {ROLE_LABELS[role]}
                            </option>
                          ))}
                        </select>
                      ) : (
                        ROLE_LABELS[member.role]
                      )}
                    </td>
                    {canManage && (
                      <td className="px-4 py-2 text-right whitespace-nowrap">
                        {member.userId !== user?.id && !lastOwner && (
                          <button
                            type="button"
                            aria-label={`Remove ${name}`}
                            className={quietButton}
                            onClick={() => setRemoving(member)}
                          >
                            Remove
                          </button>
                        )}
                      </td>
                    )}
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {canInvite && (
        <div className="mt-8">
          <h3 className="mb-3 text-base font-semibold text-text-primary">Pending invites</h3>
          {invites.isPending ? (
            <LoadingState label="Loading invites" />
          ) : !invites.data ? (
            <InlineAlert title="Invites didn’t load" description="Check your connection, then try again.">
              <button type="button" className={primaryButton} onClick={() => void invites.refetch()}>
                Try again
              </button>
            </InlineAlert>
          ) : invites.data.length === 0 ? (
            <p className="text-sm text-text-meta">No pending invites.</p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border bg-surface">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-border text-xs text-text-meta">
                  <tr>
                    <th scope="col" className="px-4 py-2.5 font-medium">Email</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Role</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Status</th>
                    <th scope="col" className="hidden px-4 py-2.5 font-medium sm:table-cell">Expires</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {invites.data.map((invite) => (
                    <InviteRow
                      key={invite.id}
                      orgId={org.id}
                      invite={invite}
                      canCancel={invite.role !== 'ADMIN' || canManage}
                      onError={setError}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {inviting && (
        <InviteDialog orgId={org.id} canInviteAdmin={canManage} onClose={() => setInviting(false)} />
      )}
      {removing && (
        <RemoveMemberDialog orgId={org.id} member={removing} onClose={() => setRemoving(null)} />
      )}
    </section>
  )
}
