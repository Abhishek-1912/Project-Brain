import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { InlineAlert } from '../../components/InlineAlert'
import { LoadingState } from '../../components/LoadingState'
import { useCurrentOrg } from '../organizations/useOrganizations'
import { primaryButton, quietButton, TestModeBanner } from '../requirements/TestModeBanner'
import { ArchitectureReview } from './ArchitectureReview'
import { normalizeSection, type SectionValue } from './sectionContent'
import { SectionEditor } from './SectionEditor'
import {
  SECTION_LABELS,
  VERSION_STATUS,
  sectionsOf,
  useArchitectureVersion,
  useUpdateArchitectureSection,
  type SectionKey,
  type ApiDesign,
  type ArchitectureVersion,
  type DataModel,
  type Field,
  type Overview,
} from './useArchitecture'

export function ArchitectureVersionView() {
  const { org } = useCurrentOrg()
  const { projectId = '', versionId = '' } = useParams()
  return <VersionPage key={`${org.id}:${versionId}`} projectId={projectId} versionId={versionId} />
}

interface Editing {
  section: SectionKey
  original: SectionValue
  draft: SectionValue
}

function VersionPage({ projectId, versionId }: { projectId: string; versionId: string }) {
  const { org } = useCurrentOrg()
  const version = useArchitectureVersion(org.id, versionId)
  const update = useUpdateArchitectureSection(org.id, projectId)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const basePath = `/orgs/${org.id}/projects/${projectId}/architecture`

  function startEditing(section: SectionKey, data: ArchitectureVersion) {
    const value = sectionsOf(data)[section] as SectionValue
    // A deep copy, so editing never changes the cached server copy.
    const copy = JSON.parse(JSON.stringify(value)) as SectionValue
    setEditing({ section, original: copy, draft: copy })
    setError(null)
    setConflict(false)
  }

  function save(data: ArchitectureVersion) {
    if (!editing) return
    setError(null)
    update.mutate(
      {
        versionId: data.id,
        section: editing.section,
        content: normalizeSection(editing.section, editing.draft),
        version: data.version,
      },
      {
        onSuccess: () => {
          setEditing(null)
          setConflict(false)
        },
        onError: (cause) => {
          setConflict(cause instanceof ApiError && cause.status === 409)
          setError(cause instanceof ApiError ? cause.message : 'That didn’t save. Check your connection, then try again.')
        },
      },
    )
  }

  async function reload() {
    const latest = await version.refetch()
    if (latest.data && editing) startEditing(editing.section, latest.data)
  }

  if (version.isPending) return <LoadingState label="Loading version" />
  if (!version.data) {
    const missing = version.error instanceof ApiError && [400, 404].includes(version.error.status)
    return (
      <InlineAlert
        title={missing ? 'Version not found' : 'Version didn’t load'}
        description={missing ? 'It doesn’t exist in this organization.' : 'Check your connection, then try again.'}
      >
        <Link to={basePath}>Back to architecture</Link>
      </InlineAlert>
    )
  }

  const data = version.data
  const status = VERSION_STATUS[data.status]
  return (
    <section>
      <Link to={basePath} className="text-sm">
        Back to architecture
      </Link>
      <div className="mt-3 mb-4 flex min-h-9 flex-wrap items-center gap-3">
        <h2 className="text-lg font-semibold text-text-primary">Version {data.versionNumber}</h2>
        <span className={`rounded px-1.5 py-0.5 text-xs ${status.className}`}>{status.label}</span>
      </div>
      <TestModeBanner />
      <VersionSections
        version={data}
        editing={editing?.section ?? null}
        editor={
          editing && (
            <div>
              <SectionEditor
                section={editing.section}
                value={editing.draft}
                onChange={(draft) => setEditing({ ...editing, draft })}
              />
              {error && (
                <div role="alert" className="mt-3 text-sm text-error-text">
                  <p>{error}</p>
                  {conflict && (
                    <button type="button" className={`${quietButton} mt-1`} onClick={() => void reload()}>
                      Reload latest
                    </button>
                  )}
                </div>
              )}
              <div className="mt-4 flex gap-2">
                <button type="button" className={primaryButton} disabled={update.isPending} onClick={() => save(data)}>
                  Save {SECTION_LABELS[editing.section]}
                </button>
                <button type="button" className={quietButton} onClick={() => setEditing(null)}>
                  Cancel
                </button>
              </div>
            </div>
          )
        }
        actions={
          data.canEdit && !editing
            ? {
                overview: <EditButton section="overview" onClick={() => startEditing('overview', data)} />,
                dataModel: <EditButton section="dataModel" onClick={() => startEditing('dataModel', data)} />,
                apiDesign: <EditButton section="apiDesign" onClick={() => startEditing('apiDesign', data)} />,
              }
            : undefined
        }
      />
      <ArchitectureReview orgId={org.id} version={data} editing={Boolean(editing)} />
    </section>
  )
}

function EditButton({ section, onClick }: { section: SectionKey; onClick(): void }) {
  return (
    <button type="button" className={quietButton} onClick={onClick}>
      Edit {SECTION_LABELS[section]}
    </button>
  )
}

const SECTION_TITLES: Record<SectionKey, string> = { overview: 'Overview', dataModel: 'Data model', apiDesign: 'API design' }

function VersionSections({
  version,
  editing,
  editor,
  actions,
}: {
  version: ArchitectureVersion
  editing: SectionKey | null
  editor: ReactNode
  actions?: Partial<Record<SectionKey, ReactNode>>
}) {
  const { overview, dataModel, apiDesign } = sectionsOf(version)
  const titles = new Map(version.stories.map((story) => [story.id, story.title]))
  const editingView = (section: SectionKey) => (
    <section>
      <SectionHeading title={SECTION_TITLES[section]} />
      {editor}
    </section>
  )
  return (
    <div className="space-y-10">
      {editing === 'overview' ? editingView('overview') : (
        <OverviewSection overview={overview} action={actions?.overview} />
      )}
      {editing === 'dataModel' ? editingView('dataModel') : (
        <DataModelSection dataModel={dataModel} titles={titles} action={actions?.dataModel} />
      )}
      {editing === 'apiDesign' ? editingView('apiDesign') : (
        <ApiSection apiDesign={apiDesign} titles={titles} action={actions?.apiDesign} />
      )}
    </div>
  )
}

function SectionHeading({ title, action }: { title: string; action?: ReactNode }) {
  return (
    <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
      <h3 className="text-base font-semibold text-text-primary">{title}</h3>
      {action}
    </div>
  )
}

function StoryLinks({ ids, titles }: { ids: string[]; titles: Map<string, string> }) {
  const linked = ids.map((id) => titles.get(id)).filter((title): title is string => Boolean(title))
  if (linked.length === 0) {
    return <span className="rounded bg-warning-tint px-1.5 py-0.5 text-xs text-warning">Not linked to a story</span>
  }
  return (
    <ul className="flex flex-wrap gap-2">
      {linked.map((title) => (
        <li key={title} className="rounded bg-secondary/15 px-1.5 py-0.5 text-xs text-secondary-light">
          {title}
        </li>
      ))}
    </ul>
  )
}

function OverviewSection({ overview, action }: { overview: Overview; action?: ReactNode }) {
  return (
    <section>
      <SectionHeading title="Overview" action={action} />
      <p className="max-w-prose text-sm leading-6 whitespace-pre-wrap">{overview.summary}</p>
      <div className="mt-5 grid gap-4 lg:grid-cols-2">
        <Card title="Components">
          <ul className="space-y-2 text-sm">
            {overview.components.map((component, index) => (
              <li key={`${index}:${component.name}`}>
                <span className="font-medium text-text-primary">{component.name}</span>
                {component.technology && (
                  <span className="ml-2 font-mono text-xs text-text-meta">{component.technology}</span>
                )}
                <p className="leading-6">{component.responsibility}</p>
              </li>
            ))}
          </ul>
        </Card>
        <Card title="Tech stack">
          <table className="w-full text-left text-sm">
            <tbody>
              {overview.techStack.map((choice, index) => (
                <tr key={`${index}:${choice.area}`} className="border-b border-hairline last:border-b-0 align-top">
                  <th scope="row" className="py-2 pr-3 font-medium text-text-meta">
                    {choice.area}
                  </th>
                  <td className="py-2">
                    <span className="text-text-primary">{choice.choice}</span>
                    <p className="leading-6">{choice.reason}</p>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <Card title="Key decisions">
          <ul className="space-y-3 text-sm">
            {overview.decisions.map((decision, index) => (
              <li key={`${index}:${decision.title}`}>
                <p className="font-medium text-text-primary">{decision.title}</p>
                <p className="leading-6">{decision.decision}</p>
                <p className="leading-6 text-text-meta">Why: {decision.reason}</p>
                {decision.alternatives.length > 0 && (
                  <p className="leading-6 text-text-meta">Considered: {decision.alternatives.join(', ')}</p>
                )}
              </li>
            ))}
          </ul>
        </Card>
        <Card title="Risks">
          <ul className="list-disc space-y-1 pl-5 text-sm leading-6">
            {overview.risks.map((risk, index) => (
              <li key={`${index}:${risk.text}`}>{risk.text}</li>
            ))}
          </ul>
        </Card>
      </div>
    </section>
  )
}

function DataModelSection({
  dataModel,
  titles,
  action,
}: {
  dataModel: DataModel
  titles: Map<string, string>
  action?: ReactNode
}) {
  return (
    <section>
      <SectionHeading title="Data model" action={action} />
      {dataModel.tables.length === 0 && <p className="text-sm text-text-meta">No tables.</p>}
      <div className="space-y-4">
        {dataModel.tables.map((table) => (
          <section
            key={table.name}
            aria-labelledby={`table-${table.name}`}
            className="rounded-lg border border-border bg-surface px-4 py-3"
          >
            <h4 id={`table-${table.name}`} className="font-mono text-sm font-semibold text-text-primary">
              {table.name}
            </h4>
            <p className="mt-1 text-sm leading-6">{table.description}</p>
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="text-xs text-text-meta">
                  <tr>
                    <th scope="col" className="py-1.5 pr-3 font-medium">Column</th>
                    <th scope="col" className="py-1.5 pr-3 font-medium">Type</th>
                    <th scope="col" className="py-1.5 pr-3 font-medium">Nullable</th>
                    <th scope="col" className="py-1.5 font-medium">Description</th>
                  </tr>
                </thead>
                <tbody>
                  {table.columns.map((column) => (
                    <tr key={column.name} className="border-t border-hairline">
                      <td className="py-1.5 pr-3 font-mono text-xs text-text-primary">{column.name}</td>
                      <td className="py-1.5 pr-3 font-mono text-xs">{column.type}</td>
                      <td className="py-1.5 pr-3">{column.nullable ? 'Yes' : 'No'}</td>
                      <td className="py-1.5">{column.description}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {table.relations.length > 0 && (
              <ul className="mt-3 space-y-1 font-mono text-xs">
                {table.relations.map((relation) => (
                  <li key={`${relation.column}:${relation.referencesTable}`}>
                    {relation.column} references {relation.referencesTable}.{relation.referencesColumn}
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-3">
              <StoryLinks ids={table.storyIds} titles={titles} />
            </div>
          </section>
        ))}
      </div>
    </section>
  )
}

function FieldList({ title, fields }: { title: string; fields: Field[] }) {
  if (fields.length === 0) return null
  return (
    <div className="mt-2">
      <p className="text-xs text-text-meta">{title}</p>
      <ul className="mt-1 space-y-1 text-sm">
        {fields.map((field) => (
          <li key={field.name}>
            <span className="font-mono text-xs text-text-primary">{field.name}</span>{' '}
            <span className="font-mono text-xs">{field.type}</span>
            {field.required ? '' : ' (optional)'}: {field.description}
          </li>
        ))}
      </ul>
    </div>
  )
}

function ApiSection({
  apiDesign,
  titles,
  action,
}: {
  apiDesign: ApiDesign
  titles: Map<string, string>
  action?: ReactNode
}) {
  return (
    <section>
      <SectionHeading title="API design" action={action} />
      {apiDesign.endpoints.length === 0 && <p className="text-sm text-text-meta">No endpoints.</p>}
      <ul className="space-y-3">
        {apiDesign.endpoints.map((endpoint) => (
          <li
            key={`${endpoint.method} ${endpoint.path}`}
            className="rounded-lg border border-border bg-surface px-4 py-3"
          >
            <p className="flex flex-wrap items-center gap-2">
              <span className="rounded bg-primary-dim px-1.5 py-0.5 font-mono text-xs text-primary">
                {endpoint.method}
              </span>
              <span className="font-mono text-sm text-text-primary">{endpoint.path}</span>
            </p>
            <p className="mt-1 text-sm leading-6">{endpoint.summary}</p>
            <p className="text-xs text-text-meta">
              Allowed: {endpoint.roles.length > 0 ? endpoint.roles.join(', ') : 'Any signed-in user'}
            </p>
            <FieldList title="Request" fields={endpoint.requestFields} />
            <FieldList title="Response" fields={endpoint.responseFields} />
            <div className="mt-3">
              <StoryLinks ids={endpoint.storyIds} titles={titles} />
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-border bg-surface px-4 py-3">
      <h4 className="mb-2 text-sm font-semibold text-text-primary">{title}</h4>
      {children}
    </section>
  )
}
