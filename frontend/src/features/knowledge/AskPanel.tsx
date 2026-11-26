import { useId, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../api/client'
import { primaryButton } from '../requirements/TestModeBanner'
import { SOURCE_TYPE_LABELS, sourcePath, useAskKnowledge, type KnowledgeAnswer } from './useKnowledge'

const MAX_QUESTION = 500

export function AskPanel({ orgId, projectId }: { orgId: string; projectId: string }) {
  const ask = useAskKnowledge(orgId, projectId)
  const [question, setQuestion] = useState('')
  const inputId = useId()
  const trimmed = question.trim()
  const projectPath = `/orgs/${orgId}/projects/${projectId}`

  function submit(event: FormEvent) {
    event.preventDefault()
    if (trimmed) ask.mutate(trimmed)
  }

  const failure = ask.error
    ? ask.error instanceof ApiError
      ? ask.error.message
      : 'The question didn’t send. Check your connection, then try again.'
    : null

  return (
    <section aria-labelledby={`${inputId}-heading`} className="mb-8">
      <h3 id={`${inputId}-heading`} className="mb-2 text-base font-semibold text-text-primary">
        Ask this project
      </h3>
      <form onSubmit={submit} className="flex max-w-3xl flex-col gap-2 sm:flex-row sm:items-start">
        <label htmlFor={inputId} className="sr-only">
          Ask a question
        </label>
        <textarea
          id={inputId}
          rows={2}
          maxLength={MAX_QUESTION}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="What does the PRD say about cancellations?"
          className="min-h-11 flex-1 resize-y rounded-md border border-border bg-surface px-3 py-2 text-sm text-text-primary"
        />
        <button type="submit" className={primaryButton} disabled={!trimmed || ask.isPending}>
          {ask.isPending ? 'Asking…' : 'Ask'}
        </button>
      </form>
      <p className="mt-1 text-xs text-text-meta">
        Answers come only from this project’s reviewed knowledge, and cite where they came from.
      </p>
      <div aria-live="polite" className="mt-4 max-w-3xl">
        {failure && (
          <p role="alert" className="text-sm text-error-text">
            {failure}
          </p>
        )}
        {ask.data && !ask.isPending && <Answer answer={ask.data} projectPath={projectPath} />}
      </div>
    </section>
  )
}

function Answer({ answer, projectPath }: { answer: KnowledgeAnswer; projectPath: string }) {
  return (
    <section aria-label="Answer" className="rounded-md border border-border bg-surface px-4 py-3">
      <p className="text-xs text-text-meta">{answer.question}</p>
      <p className="mt-1 text-sm leading-6 whitespace-pre-wrap text-text-primary">{answer.answer}</p>
      {answer.found && answer.citations.length > 0 && (
        <>
          <h4 className="mt-3 text-xs font-semibold text-text-secondary">Sources</h4>
          <ol className="mt-1 space-y-2">
            {answer.citations.map((citation) => (
              <li key={citation.number} className="text-sm">
                <Link to={sourcePath(projectPath, citation.sourceType, citation.sourceId)} className="font-medium">
                  [{citation.number}] {citation.title}
                </Link>
                <span className="ml-2 text-xs text-text-meta">{SOURCE_TYPE_LABELS[citation.sourceType]}</span>
                <p className="mt-0.5 text-xs leading-5 whitespace-pre-wrap text-text-secondary">{citation.excerpt}</p>
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  )
}
