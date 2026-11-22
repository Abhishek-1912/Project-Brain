import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { useAuth } from '../../auth/AuthProvider'
import { useCreateOrganization, useOrganizations } from './useOrganizations'

const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/
const SLUG_HINT = 'Lowercase letters, numbers and hyphens. You can’t change it later.'
const inputClass =
  'w-full rounded-md border bg-surface-low px-3 py-2 text-text-primary aria-invalid:border-error'

interface FieldErrors {
  name?: string
  slug?: string
  form?: string
}

function toSlug(name: string) {
  return name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 63)
    .replace(/-+$/, '')
}

function validate(name: string, slug: string): FieldErrors {
  const errors: FieldErrors = {}
  if (!name) errors.name = 'Enter an organization name.'
  else if (name.length > 120) errors.name = 'Use 120 characters or fewer.'
  if (slug.length < 3 || slug.length > 63 || !SLUG_PATTERN.test(slug)) {
    errors.slug = `Use 3 to 63 characters. ${SLUG_HINT}`
  }
  return errors
}

function toFieldErrors(error: unknown): FieldErrors {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return { slug: 'That short name is already in use. Try another.' }
    }
    const name = error.problem.errors?.name?.[0]
    const slug = error.problem.errors?.slug?.[0]
    if (name || slug) return { name, slug }
  }
  return { form: 'The organization wasn’t created. Check your connection and try again.' }
}

export function CreateOrganizationPage() {
  const navigate = useNavigate()
  const { signOut } = useAuth()
  const { data: organizations } = useOrganizations()
  const createOrganization = useCreateOrganization()
  const [name, setName] = useState('')
  const [customSlug, setCustomSlug] = useState<string | null>(null)
  const [errors, setErrors] = useState<FieldErrors>({})
  const slug = customSlug ?? toSlug(name)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmedName = name.trim()
    const nextErrors = validate(trimmedName, slug)
    setErrors(nextErrors)
    if (nextErrors.name || nextErrors.slug) return

    try {
      const created = await createOrganization.mutateAsync({
        name: trimmedName,
        slug,
      })
      navigate(`/orgs/${created.id}`, { replace: true })
    } catch (error) {
      setErrors(toFieldErrors(error))
    }
  }

  return (
    <main className="mx-auto flex min-h-svh w-full max-w-md flex-col justify-center px-4 py-10">
      <p className="mb-8 text-sm font-semibold text-primary">Project Brain</p>
      <h1 className="text-3xl font-semibold tracking-tight text-text-primary">
        Create your organization
      </h1>
      <p className="mt-2 text-sm leading-6">
        Projects, tasks and your team’s activity live inside it.
      </p>

      <form className="mt-8 flex flex-col gap-5" noValidate onSubmit={handleSubmit}>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="org-name" className="text-sm font-medium text-text-primary">
            Organization name
          </label>
          <input
            id="org-name"
            className={`${inputClass} border-border`}
            autoComplete="organization"
            autoFocus
            maxLength={120}
            value={name}
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={errors.name ? 'org-name-error' : undefined}
            onChange={(event) => {
              setName(event.target.value)
              setErrors((current) => ({ ...current, name: undefined }))
            }}
          />
          {errors.name && (
            <p id="org-name-error" className="text-sm text-error-text">
              {errors.name}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="org-slug" className="text-sm font-medium text-text-primary">
            Short name
          </label>
          <input
            id="org-slug"
            className={`${inputClass} border-border font-mono text-sm`}
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            maxLength={63}
            value={slug}
            aria-invalid={errors.slug ? true : undefined}
            aria-describedby="org-slug-message"
            onChange={(event) => {
              setCustomSlug(event.target.value)
              setErrors((current) => ({ ...current, slug: undefined }))
            }}
          />
          <p
            id="org-slug-message"
            className={errors.slug ? 'text-sm text-error-text' : 'text-sm text-text-meta'}
          >
            {errors.slug ?? SLUG_HINT}
          </p>
        </div>

        {errors.form && (
          <p role="alert" className="text-sm text-error-text">
            {errors.form}
          </p>
        )}

        <button
          type="submit"
          disabled={createOrganization.isPending}
          className="rounded-md bg-primary px-4 py-2.5 font-medium text-on-primary hover:bg-primary-hover disabled:opacity-60"
        >
          Create organization
        </button>
      </form>

      <nav className="mt-6 flex justify-between gap-4 text-sm">
        {organizations && organizations.length > 0 ? (
          <Link to="/">Back to your organizations</Link>
        ) : (
          <span />
        )}
        <button
          type="button"
          className="text-text-secondary hover:text-text-primary"
          onClick={() => void signOut()}
        >
          Sign out
        </button>
      </nav>
    </main>
  )
}
