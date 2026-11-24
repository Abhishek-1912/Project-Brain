export const primaryButton =
  'rounded-md bg-primary px-3 py-2 text-sm font-medium text-on-primary hover:bg-primary-hover disabled:cursor-not-allowed disabled:bg-surface-bright disabled:text-text-disabled'
export const quietButton =
  'rounded px-2 py-1 text-sm text-text-secondary hover:bg-surface-bright hover:text-text-primary disabled:text-text-disabled disabled:hover:bg-transparent'

export function TestModeBanner() {
  return (
    <aside className="mb-5 rounded-md border border-warning/40 bg-warning-tint px-4 py-3 text-sm leading-6 text-text-primary">
      <strong className="font-semibold">Use dummy data only.</strong> Documents are sent to the Gemini
      free tier, where people may review content and Google may use it to improve its products.
    </aside>
  )
}
