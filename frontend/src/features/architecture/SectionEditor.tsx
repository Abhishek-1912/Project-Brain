import { useId, type ReactNode } from 'react'
import { quietButton } from '../requirements/TestModeBanner'
import type { SectionValue } from './sectionContent'
import type { ApiDesign, Column, DataModel, Endpoint, Field, Overview, SectionKey, Table } from './useArchitecture'

const COLUMN_TYPES = ['uuid', 'text', 'integer', 'bigint', 'numeric', 'boolean', 'date', 'timestamptz', 'jsonb']
const FIELD_TYPES = ['string', 'integer', 'number', 'boolean', 'uuid', 'date', 'datetime', 'object', 'array']
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

const inputClass =
  'mt-1 w-full rounded-md border border-border bg-surface-dim px-2.5 py-1.5 text-sm text-text-primary'
const groupClass = 'rounded-md border border-border px-3 py-2'


interface SectionEditorProps {
  section: SectionKey
  value: SectionValue
  onChange(value: SectionValue): void
}

export function SectionEditor({ section, value, onChange }: SectionEditorProps) {
  if (section === 'overview') return <OverviewForm value={value as Overview} onChange={onChange} />
  if (section === 'dataModel') return <DataModelForm value={value as DataModel} onChange={onChange} />
  return <ApiForm value={value as ApiDesign} onChange={onChange} />
}

function replace<T>(items: T[], index: number, item: T) {
  return items.map((current, position) => (position === index ? item : current))
}

function remove<T>(items: T[], index: number) {
  return items.filter((_, position) => position !== index)
}

function Input({
  label,
  value,
  onChange,
  multiline = false,
}: {
  label: string
  value: string
  onChange(value: string): void
  multiline?: boolean
}) {
  const id = useId()
  return (
    <div>
      <label htmlFor={id} className="text-xs font-medium text-text-secondary">
        {label}
      </label>
      {multiline ? (
        <textarea id={id} rows={2} value={value} className={inputClass} onChange={(event) => onChange(event.target.value)} />
      ) : (
        <input id={id} value={value} className={inputClass} onChange={(event) => onChange(event.target.value)} />
      )}
    </div>
  )
}

function Select({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: string
  options: string[]
  onChange(value: string): void
}) {
  const id = useId()
  return (
    <div>
      <label htmlFor={id} className="text-xs font-medium text-text-secondary">
        {label}
      </label>
      <select id={id} value={value} className={inputClass} onChange={(event) => onChange(event.target.value)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </div>
  )
}

function Check({ label, checked, onChange }: { label: string; checked: boolean; onChange(value: boolean): void }) {
  return (
    <label className="mt-5 flex items-center gap-2 text-xs font-medium text-text-secondary">
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
      {label}
    </label>
  )
}

function Group({ legend, children, onRemove, removeLabel }: {
  legend: string
  children: ReactNode
  onRemove(): void
  removeLabel: string
}) {
  return (
    <fieldset className={groupClass}>
      <legend className="px-1 text-xs font-medium text-text-meta">{legend}</legend>
      <div className="space-y-2">{children}</div>
      <button type="button" className={`${quietButton} mt-2`} onClick={onRemove}>
        {removeLabel}
      </button>
    </fieldset>
  )
}

function AddButton({ label, onClick }: { label: string; onClick(): void }) {
  return (
    <button type="button" className={quietButton} onClick={onClick}>
      {label}
    </button>
  )
}

function OverviewForm({ value, onChange }: { value: Overview; onChange(value: Overview): void }) {
  const set = (patch: Partial<Overview>) => onChange({ ...value, ...patch })
  return (
    <div className="space-y-4">
      <Input label="Summary" value={value.summary} multiline onChange={(summary) => set({ summary })} />
      <div className="space-y-2">
        {value.components.map((component, index) => (
          <Group
            key={index}
            legend={`Component ${index + 1}`}
            removeLabel="Remove component"
            onRemove={() => set({ components: remove(value.components, index) })}
          >
            <Input label="Component name" value={component.name}
              onChange={(name) => set({ components: replace(value.components, index, { ...component, name }) })} />
            <Input label="Responsibility" value={component.responsibility}
              onChange={(responsibility) =>
                set({ components: replace(value.components, index, { ...component, responsibility }) })} />
            <Input label="Technology" value={component.technology ?? ''}
              onChange={(technology) =>
                set({ components: replace(value.components, index, { ...component, technology: technology || null }) })} />
          </Group>
        ))}
        <AddButton label="Add component"
          onClick={() => set({ components: [...value.components, { name: '', responsibility: '', technology: null }] })} />
      </div>
      <div className="space-y-2">
        {value.techStack.map((choice, index) => (
          <Group key={index} legend={`Choice ${index + 1}`} removeLabel="Remove choice"
            onRemove={() => set({ techStack: remove(value.techStack, index) })}>
            <Input label="Area" value={choice.area}
              onChange={(area) => set({ techStack: replace(value.techStack, index, { ...choice, area }) })} />
            <Input label="Choice" value={choice.choice}
              onChange={(next) => set({ techStack: replace(value.techStack, index, { ...choice, choice: next }) })} />
            <Input label="Reason" value={choice.reason}
              onChange={(reason) => set({ techStack: replace(value.techStack, index, { ...choice, reason }) })} />
          </Group>
        ))}
        <AddButton label="Add tech choice"
          onClick={() => set({ techStack: [...value.techStack, { area: '', choice: '', reason: '' }] })} />
      </div>
      <div className="space-y-2">
        {value.decisions.map((decision, index) => (
          <Group key={index} legend={`Decision ${index + 1}`} removeLabel="Remove decision"
            onRemove={() => set({ decisions: remove(value.decisions, index) })}>
            <Input label="Title" value={decision.title}
              onChange={(title) => set({ decisions: replace(value.decisions, index, { ...decision, title }) })} />
            <Input label="Decision" value={decision.decision} multiline
              onChange={(next) => set({ decisions: replace(value.decisions, index, { ...decision, decision: next }) })} />
            <Input label="Reason for decision" value={decision.reason} multiline
              onChange={(reason) => set({ decisions: replace(value.decisions, index, { ...decision, reason }) })} />
            <Input label="Alternatives, one per line" value={decision.alternatives.join('\n')} multiline
              onChange={(text) =>
                set({ decisions: replace(value.decisions, index, { ...decision, alternatives: text.split('\n') }) })} />
          </Group>
        ))}
        <AddButton label="Add decision"
          onClick={() =>
            set({ decisions: [...value.decisions, { title: '', decision: '', reason: '', alternatives: [] }] })} />
      </div>
      <div className="space-y-2">
        {value.risks.map((risk, index) => (
          <Group key={index} legend={`Risk ${index + 1}`} removeLabel="Remove risk"
            onRemove={() => set({ risks: remove(value.risks, index) })}>
            <Input label="Risk" value={risk.text} multiline
              onChange={(text) => set({ risks: replace(value.risks, index, { text }) })} />
          </Group>
        ))}
        <AddButton label="Add risk" onClick={() => set({ risks: [...value.risks, { text: '' }] })} />
      </div>
    </div>
  )
}

const newColumn = (): Column => ({ name: '', type: 'text', nullable: true, description: '' })

function TableForm({ table, onChange, onRemove }: { table: Table; onChange(table: Table): void; onRemove(): void }) {
  const set = (patch: Partial<Table>) => onChange({ ...table, ...patch })
  return (
    <Group legend={`Table ${table.name}`} removeLabel="Remove table" onRemove={onRemove}>
      <Input label="Table name" value={table.name} onChange={(name) => set({ name })} />
      <Input label="Table description" value={table.description} onChange={(description) => set({ description })} />
      {table.columns.map((column, index) => (
        <Group key={index} legend={`Column ${index + 1}`} removeLabel="Remove column"
          onRemove={() => set({ columns: remove(table.columns, index) })}>
          <div className="grid gap-2 sm:grid-cols-2">
            <Input label="Column name" value={column.name}
              onChange={(name) => set({ columns: replace(table.columns, index, { ...column, name }) })} />
            <Select label="Column type" value={column.type} options={COLUMN_TYPES}
              onChange={(type) => set({ columns: replace(table.columns, index, { ...column, type }) })} />
          </div>
          <Input label="Column description" value={column.description}
            onChange={(description) => set({ columns: replace(table.columns, index, { ...column, description }) })} />
          <Check label="Nullable" checked={column.nullable}
            onChange={(nullable) => set({ columns: replace(table.columns, index, { ...column, nullable }) })} />
        </Group>
      ))}
      <AddButton label="Add column" onClick={() => set({ columns: [...table.columns, newColumn()] })} />
      {table.relations.map((relation, index) => (
        <Group key={index} legend={`Relation ${index + 1}`} removeLabel="Remove relation"
          onRemove={() => set({ relations: remove(table.relations, index) })}>
          <div className="grid gap-2 sm:grid-cols-3">
            <Input label="From column" value={relation.column}
              onChange={(column) => set({ relations: replace(table.relations, index, { ...relation, column }) })} />
            <Input label="References table" value={relation.referencesTable}
              onChange={(referencesTable) =>
                set({ relations: replace(table.relations, index, { ...relation, referencesTable }) })} />
            <Input label="References column" value={relation.referencesColumn}
              onChange={(referencesColumn) =>
                set({ relations: replace(table.relations, index, { ...relation, referencesColumn }) })} />
          </div>
        </Group>
      ))}
      <AddButton label="Add relation"
        onClick={() => set({ relations: [...table.relations, { column: '', referencesTable: '', referencesColumn: 'id' }] })} />
    </Group>
  )
}

function DataModelForm({ value, onChange }: { value: DataModel; onChange(value: DataModel): void }) {
  return (
    <div className="space-y-3">
      {value.tables.map((table, index) => (
        <TableForm key={index} table={table}
          onChange={(next) => onChange({ tables: replace(value.tables, index, next) })}
          onRemove={() => onChange({ tables: remove(value.tables, index) })} />
      ))}
      <AddButton label="Add table"
        onClick={() =>
          onChange({
            tables: [...value.tables, { name: '', description: '', columns: [newColumn()], relations: [], storyIds: [] }],
          })} />
    </div>
  )
}

function FieldsForm({ title, fields, onChange }: { title: string; fields: Field[]; onChange(fields: Field[]): void }) {
  return (
    <div className="space-y-2">
      {fields.map((field, index) => (
        <Group key={index} legend={`${title} field ${index + 1}`} removeLabel="Remove field"
          onRemove={() => onChange(remove(fields, index))}>
          <div className="grid gap-2 sm:grid-cols-2">
            <Input label="Field name" value={field.name}
              onChange={(name) => onChange(replace(fields, index, { ...field, name }))} />
            <Select label="Field type" value={field.type} options={FIELD_TYPES}
              onChange={(type) => onChange(replace(fields, index, { ...field, type }))} />
          </div>
          <Input label="Field description" value={field.description}
            onChange={(description) => onChange(replace(fields, index, { ...field, description }))} />
          <Check label="Required" checked={field.required}
            onChange={(required) => onChange(replace(fields, index, { ...field, required }))} />
        </Group>
      ))}
      <AddButton label={`Add ${title.toLowerCase()} field`}
        onClick={() => onChange([...fields, { name: '', type: 'string', required: true, description: '' }])} />
    </div>
  )
}

function EndpointForm({ endpoint, onChange, onRemove }: {
  endpoint: Endpoint
  onChange(endpoint: Endpoint): void
  onRemove(): void
}) {
  const set = (patch: Partial<Endpoint>) => onChange({ ...endpoint, ...patch })
  return (
    <Group legend={`Endpoint ${endpoint.method} ${endpoint.path}`} removeLabel="Remove endpoint" onRemove={onRemove}>
      <div className="grid gap-2 sm:grid-cols-[8rem_1fr]">
        <Select label="Method" value={endpoint.method} options={METHODS} onChange={(method) => set({ method })} />
        <Input label="Path" value={endpoint.path} onChange={(path) => set({ path })} />
      </div>
      <Input label="Summary" value={endpoint.summary} onChange={(summary) => set({ summary })} />
      <Input label="Allowed roles, comma separated" value={endpoint.roles.join(',')}
        onChange={(text) => set({ roles: text.split(',') })} />
      <FieldsForm title="Request" fields={endpoint.requestFields} onChange={(requestFields) => set({ requestFields })} />
      <FieldsForm title="Response" fields={endpoint.responseFields}
        onChange={(responseFields) => set({ responseFields })} />
    </Group>
  )
}

function ApiForm({ value, onChange }: { value: ApiDesign; onChange(value: ApiDesign): void }) {
  return (
    <div className="space-y-3">
      {value.endpoints.map((endpoint, index) => (
        <EndpointForm key={index} endpoint={endpoint}
          onChange={(next) => onChange({ endpoints: replace(value.endpoints, index, next) })}
          onRemove={() => onChange({ endpoints: remove(value.endpoints, index) })} />
      ))}
      <AddButton label="Add endpoint"
        onClick={() =>
          onChange({
            endpoints: [
              ...value.endpoints,
              { method: 'GET', path: '/', summary: '', requestFields: [], responseFields: [], roles: [], storyIds: [] },
            ],
          })} />
    </div>
  )
}
