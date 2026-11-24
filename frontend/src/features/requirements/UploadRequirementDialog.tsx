import { useId, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import { useJobRoles } from '../team/useProjectTeam'
import { primaryButton, quietButton } from './TestModeBanner'
import { MAX_UPLOAD_BYTES, useUploadRequirement } from './useRequirements'

interface UploadRequirementDialogProps {
  orgId: string
  projectId: string
  onClose(): void
}

const fieldClass =
  'mt-1 w-full rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary'

export function UploadRequirementDialog({ orgId, projectId, onClose }: UploadRequirementDialogProps) {
  const fileId = useId()
  const approverId = useId()
  const navigate = useNavigate()
  const upload = useUploadRequirement(orgId, projectId)
  const jobRoles = useJobRoles(orgId)
  const [file, setFile] = useState<File | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [approver, setApprover] = useState('')

  const tooLarge = Boolean(file && file.size > MAX_UPLOAD_BYTES)
  const failure = upload.error
    ? upload.error instanceof ApiError
      ? upload.error.message
      : 'The upload didn’t finish. Check your connection, then try again.'
    : null

  function submit() {
    if (!file || !confirmed || tooLarge) return
    upload.mutate(
      { file, approverJobRoleId: approver || undefined },
      {
        onSuccess: (document) =>
          navigate(`/orgs/${orgId}/projects/${projectId}/requirements/${document.id}`),
      },
    )
  }

  return (
    <Dialog title="Upload a requirement document" onClose={onClose}>
      <form
        className="mt-4 space-y-4"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <div>
          <label htmlFor={fileId} className="text-sm font-medium text-text-primary">
            Document
          </label>
          <input
            id={fileId}
            type="file"
            accept=".pdf,.docx,.md,.markdown,.txt"
            className={fieldClass}
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
          <p className="mt-1 text-xs text-text-meta">PDF, Word, Markdown, or plain text, up to 10 MB.</p>
          {tooLarge && <p className="mt-1 text-sm text-error-text">The file must be 10 MB or smaller.</p>}
        </div>
        <div>
          <label htmlFor={approverId} className="text-sm font-medium text-text-primary">
            Requirements approver
          </label>
          <select
            id={approverId}
            value={approver}
            className={fieldClass}
            onChange={(event) => setApprover(event.target.value)}
          >
            <option value="">Project Manager (default)</option>
            {jobRoles.data
              ?.filter((role) => role.active)
              .map((role) => (
                <option key={role.id} value={role.id}>
                  {role.name}
                </option>
              ))}
          </select>
        </div>
        <label className="flex items-start gap-2 text-sm text-text-primary">
          <input
            type="checkbox"
            className="mt-1"
            checked={confirmed}
            onChange={(event) => setConfirmed(event.target.checked)}
          />
          <span>This file contains only dummy test data, with no client or personal information.</span>
        </label>
        {failure && (
          <p role="alert" className="text-sm text-error-text">
            {failure}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" className={quietButton} onClick={onClose}>
            Cancel
          </button>
          <button
            type="submit"
            className={primaryButton}
            disabled={!file || !confirmed || tooLarge || upload.isPending}
          >
            {upload.isPending ? 'Uploading' : 'Upload'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
