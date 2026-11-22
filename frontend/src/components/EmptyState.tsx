import type { ReactNode } from 'react'

interface EmptyStateProps {
  title: string
  description: string
  action?: ReactNode
}

export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <section className="max-w-prose border-l-2 border-border-active py-1 pl-5">
      <h2 className="text-base font-semibold text-text-primary">{title}</h2>
      <p className="mt-1 text-sm leading-6">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </section>
  )
}
