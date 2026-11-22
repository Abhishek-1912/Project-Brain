export function LoadingState({ label = 'Loading' }: { label?: string }) {
  return (
    <p role="status" className="p-8 text-sm text-text-meta">
      {label}
    </p>
  )
}
