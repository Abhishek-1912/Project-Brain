import type { ReactNode } from 'react'

interface InlineAlertProps {
  title: string
  description: string
  children?: ReactNode
}

export function InlineAlert({ title, description, children }: InlineAlertProps) {
  return (
    <section role="alert" className="max-w-prose border-l-2 border-error py-1 pl-5">
      <h2 className="text-base font-semibold text-text-primary">{title}</h2>
      <p className="mt-1 text-sm leading-6">{description}</p>
      {children && <div className="mt-4 text-sm">{children}</div>}
    </section>
  )
}
