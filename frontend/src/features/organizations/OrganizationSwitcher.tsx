import { useNavigate } from 'react-router-dom'
import { useCurrentOrg, useOrganizations } from './useOrganizations'

export function OrganizationSwitcher() {
  const navigate = useNavigate()
  const { org } = useCurrentOrg()
  const { data } = useOrganizations()
  const organizations = data?.some((item) => item.id === org.id)
    ? data
    : [org, ...(data ?? [])]

  return (
    <label className="flex min-w-0 flex-col gap-1 text-xs text-text-secondary">
      Organization
      <select
        className="w-full truncate rounded-md border border-border bg-surface-bright px-2 py-2 text-sm font-medium text-text-primary"
        value={org.id}
        onChange={(event) => navigate(`/orgs/${event.target.value}`)}
      >
        {organizations.map((item) => (
          <option key={item.id} value={item.id}>
            {item.name}
          </option>
        ))}
      </select>
    </label>
  )
}
