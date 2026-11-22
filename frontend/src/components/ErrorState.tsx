import type { ReactNode } from 'react'

interface ErrorStateProps {
  title: string
  description: string
  action?: ReactNode
}

export function ErrorState({ title, description, action }: ErrorStateProps) {
  return (
    <main className="mx-auto mt-24 w-full max-w-md px-4">
      <section role="alert" className="border-l-2 border-error py-1 pl-5">
        <h1 className="text-xl font-semibold text-text-primary">{title}</h1>
        <p className="mt-2 text-sm leading-6">{description}</p>
        {action && <div className="mt-4 text-sm">{action}</div>}
      </section>
    </main>
  )
}
