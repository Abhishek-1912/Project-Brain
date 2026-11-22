import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { Dialog } from '../../components/Dialog'
import {
  useCreateProject,
  useUpdateProject,
  type Project,
} from './useProjects'

const fieldClass =
  'w-full rounded-md border border-border bg-surface-low px-3 py-2 text-text-primary aria-invalid:border-error'

interface FormErrors {
  name?: string
  description?: string
  form?: string
}

function toFormErrors(error: unknown): FormErrors {
  if (error instanceof ApiError) {
    const detail = error.problem.detail
    if (error.status === 409) {
      return /name already exists/i.test(detail ?? '')
        ? { name: 'A project with that name already exists.' }
        : { form: detail ?? 'The project changed. Close this form and try again.' }
    }
    if (error.status === 403) {
      return { form: 'Your role can’t manage projects.' }
    }
    const name = error.problem.errors?.name?.[0]
    const description = error.problem.errors?.description?.[0]
    if (name || description) return { name, description }
  }
  return { form: 'The project wasn’t saved. Check your connection and try again.' }
}

interface ProjectFormDialogProps {
  orgId: string
  project?: Project
  onClose(): void
}

export function ProjectFormDialog({ orgId, project, onClose }: ProjectFormDialogProps) {
  const createProject = useCreateProject(orgId)
  const updateProject = useUpdateProject(orgId)
  const [name, setName] = useState(project?.name ?? '')
  const [description, setDescription] = useState(project?.description ?? '')
  const [errors, setErrors] = useState<FormErrors>({})
  const saving = createProject.isPending || updateProject.isPending
  const action = project ? 'Save changes' : 'Create project'

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmedName = name.trim()
    if (!trimmedName) {
      setErrors({ name: 'Enter a project name.' })
      return
    }
    setErrors({})
    const fields = {
      name: trimmedName,
      description: description.trim() || null,
    }
    try {
      if (project) {
        await updateProject.mutateAsync({
          projectId: project.id,
          body: { ...fields, version: project.version },
        })
      } else {
        await createProject.mutateAsync(fields)
      }
      onClose()
    } catch (error) {
      setErrors(toFormErrors(error))
    }
  }

  return (
    <Dialog title={project ? 'Edit project' : 'Create project'} onClose={onClose}>
      <form className="mt-5 flex flex-col gap-4" noValidate onSubmit={handleSubmit}>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="project-name" className="text-sm font-medium text-text-primary">
            Project name
          </label>
          <input
            id="project-name"
            className={fieldClass}
            autoFocus
            maxLength={120}
            value={name}
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={errors.name ? 'project-name-error' : undefined}
            onChange={(event) => {
              setName(event.target.value)
              setErrors((current) => ({ ...current, name: undefined }))
            }}
          />
          {errors.name && (
            <p id="project-name-error" className="text-sm text-error-text">
              {errors.name}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="project-description" className="text-sm font-medium text-text-primary">
            Description
          </label>
          <textarea
            id="project-description"
            className={`${fieldClass} min-h-24 resize-y`}
            maxLength={10000}
            value={description}
            aria-invalid={errors.description ? true : undefined}
            aria-describedby={errors.description ? 'project-description-error' : undefined}
            onChange={(event) => {
              setDescription(event.target.value)
              setErrors((current) => ({ ...current, description: undefined }))
            }}
          />
          {errors.description && (
            <p id="project-description-error" className="text-sm text-error-text">
              {errors.description}
            </p>
          )}
        </div>

        {errors.form && (
          <p role="alert" className="text-sm text-error-text">
            {errors.form}
          </p>
        )}

        <div className="mt-1 flex justify-end gap-3">
          <button
            type="button"
            className="rounded-md px-3 py-2 text-sm text-text-secondary hover:text-text-primary"
            onClick={onClose}
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={saving}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60"
          >
            {action}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
