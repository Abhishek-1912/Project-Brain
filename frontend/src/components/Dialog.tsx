import { useEffect, useId, useRef, type ReactNode } from 'react'

interface DialogProps {
  title: string
  onClose(): void
  children: ReactNode
  placement?: 'center' | 'side'
}

const PLACEMENT = {
  center: 'm-auto w-[calc(100%-2rem)] max-w-lg rounded-lg border',
  side: 'my-0 mr-0 ml-auto h-dvh max-h-none w-full max-w-md overflow-y-auto border-l',
}

export function Dialog({ title, onClose, children, placement = 'center' }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (dialog && !dialog.open) dialog.showModal()
  }, [])

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      className={`${PLACEMENT[placement]} border-border bg-surface-high p-6 text-text-secondary backdrop:bg-canvas/75`}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <h2 id={titleId} className="text-lg font-semibold text-text-primary">
        {title}
      </h2>
      {children}
    </dialog>
  )
}
