import createClient from 'openapi-fetch'
import { supabase } from '../auth/supabase'
import type { components, paths } from './schema'

export type Problem = components['schemas']['Problem']

export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title)
    this.name = 'ApiError'
    this.status = problem.status
    this.problem = problem
  }
}

export interface ApiAuth {
  getAccessToken(): Promise<string | null>
  refreshAccessToken(): Promise<string | null>
  signOut(): Promise<void>
}

interface ApiClientOptions {
  baseUrl: string
  auth: ApiAuth
  fetch?: (request: Request) => Promise<Response>
}

const API_PREFIX = '/api/v1'

function versionedBaseUrl(baseUrl: string) {
  const trimmed = baseUrl.replace(/\/+$/, '')
  return trimmed.endsWith(API_PREFIX) ? trimmed : `${trimmed}${API_PREFIX}`
}

async function readProblem(response: Response): Promise<Problem> {
  const fallback = {
    title: response.statusText || 'Request failed',
    status: response.status,
  }
  try {
    const body: unknown = await response.clone().json()
    if (body && typeof body === 'object' && 'title' in body) {
      return { ...(body as Problem), status: response.status }
    }
  } catch {
    // A proxy or gateway can return a non-JSON error.
  }
  return fallback
}

export function createApiClient({
  baseUrl,
  auth,
  fetch: send = (request) => globalThis.fetch(request),
}: ApiClientOptions) {
  let refreshing: Promise<string | null> | null = null

  const refreshOnce = () => {
    refreshing ??= auth.refreshAccessToken().finally(() => {
      refreshing = null
    })
    return refreshing
  }

  const sendWithToken = (request: Request, token: string | null) => {
    if (token) request.headers.set('Authorization', `Bearer ${token}`)
    return send(request)
  }

  const authenticatedFetch = async (request: Request) => {
    const retry = request.clone()
    const first = await sendWithToken(request, await auth.getAccessToken())
    if (first.status !== 401) return first

    const token = await refreshOnce().catch(() => null)
    if (!token) {
      await auth.signOut()
      return first
    }

    const second = await sendWithToken(retry, token)
    if (second.status === 401) await auth.signOut()
    return second
  }

  const client = createClient<paths>({
    baseUrl: versionedBaseUrl(baseUrl),
    fetch: authenticatedFetch,
  })

  client.use({
    async onResponse({ response }) {
      if (!response.ok) throw new ApiError(await readProblem(response))
      return response
    },
  })

  return client
}

export const api = createApiClient({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
  auth: {
    async getAccessToken() {
      const { data } = await supabase.auth.getSession()
      return data.session?.access_token ?? null
    },
    async refreshAccessToken() {
      const { data } = await supabase.auth.refreshSession()
      return data.session?.access_token ?? null
    },
    async signOut() {
      await supabase.auth.signOut({ scope: 'local' })
    },
  },
})
