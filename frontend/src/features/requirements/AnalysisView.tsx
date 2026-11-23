import type { AnalysisItem, RequirementAnalysisState } from './useRequirements'

type Analysis = NonNullable<RequirementAnalysisState['analysis']>

const SECTIONS = [
  { key: 'features', title: 'Features' },
  { key: 'users', title: 'Users' },
  { key: 'gaps', title: 'Gaps' },
  { key: 'risks', title: 'Risks' },
] as const

function readItems(value: unknown): AnalysisItem[] {
  if (!Array.isArray(value)) return []
  return value.filter(
    (item): item is AnalysisItem =>
      typeof item === 'object' && item !== null && typeof (item as AnalysisItem).text === 'string',
  )
}

function SourceBadge({ verified }: { verified: boolean }) {
  return verified ? (
    <span className="rounded bg-success-tint px-1.5 py-0.5 text-xs text-success">Source verified</span>
  ) : (
    <span className="rounded bg-warning-tint px-1.5 py-0.5 text-xs text-warning">
      Source not found in document
    </span>
  )
}

function Item({ item }: { item: AnalysisItem }) {
  return (
    <li className="border-b border-hairline py-3 last:border-b-0">
      <p className="text-sm leading-6 text-text-primary">{item.text}</p>
      {item.sourceQuote && (
        <blockquote className="mt-1 border-l-2 border-border pl-3 text-sm leading-6 text-text-secondary">
          {item.sourceQuote}
        </blockquote>
      )}
      {typeof item.sourceVerified === 'boolean' && (
        <div className="mt-2">
          <SourceBadge verified={item.sourceVerified} />
        </div>
      )}
    </li>
  )
}

// React escapes every value here, so text from a document or the AI can never become markup.
export function AnalysisView({ analysis }: { analysis: Analysis }) {
  const summary = typeof analysis.result.summary === 'string' ? analysis.result.summary : ''
  return (
    <section aria-labelledby="analysis-heading">
      <div className="flex flex-wrap items-baseline gap-3">
        <h3 id="analysis-heading" className="text-base font-semibold text-text-primary">
          Analysis
        </h3>
        <span className="font-mono text-xs text-text-meta">Version {analysis.version}</span>
      </div>
      <p className="mt-2 max-w-prose text-sm leading-6 whitespace-pre-wrap">{summary}</p>
      <div className="mt-5 grid gap-4 lg:grid-cols-2">
        {SECTIONS.map(({ key, title }) => {
          const items = readItems(analysis.result[key])
          return (
            <section key={key} className="rounded-lg border border-border bg-surface px-4 py-3">
              <h4 className="text-sm font-semibold text-text-primary">{title}</h4>
              {items.length === 0 ? (
                <p className="mt-2 text-sm text-text-meta">None found.</p>
              ) : (
                <ul className="mt-1">
                  {items.map((item, index) => (
                    <Item key={`${index}:${item.text}`} item={item} />
                  ))}
                </ul>
              )}
            </section>
          )
        })}
      </div>
    </section>
  )
}
