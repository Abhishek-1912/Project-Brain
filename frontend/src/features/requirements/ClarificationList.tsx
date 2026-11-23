import { useId, useState } from 'react'
import { ApiError } from '../../api/client'
import { primaryButton, quietButton } from './TestModeBanner'
import { useAnswerClarification, type ClarificationQuestion } from './useRequirements'

interface ClarificationListProps {
  orgId: string
  documentId: string
  questions: ClarificationQuestion[]
  canAnswer: boolean
}

export function ClarificationList({ orgId, documentId, questions, canAnswer }: ClarificationListProps) {
  if (questions.length === 0) return null
  return (
    <section aria-labelledby="questions-heading" className="mt-8">
      <h3 id="questions-heading" className="text-base font-semibold text-text-primary">
        Clarification questions
      </h3>
      <p className="mt-1 max-w-prose text-sm leading-6">
        Saved answers are used the next time you update the analysis.
      </p>
      <ol className="mt-3 space-y-3">
        {questions.map((question) => (
          <Question
            key={question.id}
            orgId={orgId}
            documentId={documentId}
            question={question}
            canAnswer={canAnswer}
          />
        ))}
      </ol>
    </section>
  )
}

function Question({
  orgId,
  documentId,
  question,
  canAnswer,
}: {
  orgId: string
  documentId: string
  question: ClarificationQuestion
  canAnswer: boolean
}) {
  const answerId = useId()
  const update = useAnswerClarification(orgId, documentId)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(question.answer ?? '')
  const showForm = canAnswer && (question.status === 'OPEN' || editing)
  const failure = update.error
    ? update.error instanceof ApiError
      ? update.error.message
      : 'The change didn’t save. Check your connection, then try again.'
    : null

  function save() {
    update.mutate(
      {
        questionId: question.id,
        body: { status: 'ANSWERED', answer: draft.trim(), version: question.version },
      },
      { onSuccess: () => setEditing(false) },
    )
  }

  function dismiss() {
    update.mutate(
      { questionId: question.id, body: { status: 'DISMISSED', version: question.version } },
      { onSuccess: () => setEditing(false) },
    )
  }

  return (
    <li className="rounded-lg border border-border bg-surface px-4 py-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <p className="min-w-0 text-sm font-medium break-words text-text-primary">{question.question}</p>
        {question.status === 'ANSWERED' && (
          <span className="rounded bg-success-tint px-1.5 py-0.5 text-xs text-success">Answered</span>
        )}
        {question.status === 'DISMISSED' && (
          <span className="rounded bg-surface-high px-1.5 py-0.5 text-xs text-text-secondary">Not needed</span>
        )}
      </div>
      <p className="mt-1 text-sm leading-6 text-text-meta">{question.reason}</p>
      {question.answer && !editing && (
        <p className="mt-2 border-l-2 border-border-active pl-3 text-sm leading-6 whitespace-pre-wrap text-text-primary">
          {question.answer}
        </p>
      )}
      {showForm && (
        <div className="mt-3">
          <label htmlFor={answerId} className="text-sm font-medium text-text-primary">
            Your answer
          </label>
          <textarea
            id={answerId}
            rows={3}
            maxLength={5000}
            value={draft}
            className="mt-1 w-full rounded-md border border-border bg-surface-dim px-3 py-2 text-sm text-text-primary"
            onChange={(event) => setDraft(event.target.value)}
          />
        </div>
      )}
      {failure && (
        <p role="alert" className="mt-2 text-sm text-error-text">
          {failure}
        </p>
      )}
      {canAnswer && (
        <div className="mt-2 flex flex-wrap gap-2">
          {showForm ? (
            <button
              type="button"
              className={primaryButton}
              disabled={!draft.trim() || update.isPending}
              onClick={save}
            >
              Save answer
            </button>
          ) : (
            <button
              type="button"
              className={quietButton}
              onClick={() => {
                setDraft(question.answer ?? '')
                setEditing(true)
              }}
            >
              {question.status === 'ANSWERED' ? 'Edit answer' : 'Answer this'}
            </button>
          )}
          {question.status !== 'DISMISSED' && (
            <button type="button" className={quietButton} disabled={update.isPending} onClick={dismiss}>
              Mark not needed
            </button>
          )}
        </div>
      )}
    </li>
  )
}
