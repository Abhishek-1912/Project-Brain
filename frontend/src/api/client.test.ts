import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from './client'

vi.mock('../auth/supabase', () => ({ supabase: { auth: {} } }))

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

const unauthorized = () =>
  json(401, { title: 'Unauthorized', status: 401 })

function setup(
  responses: Response[],
  baseUrl = 'http://api.test/api/v1',
  refreshed: string | null = 'new-token',
) {
  const requests: Request[] = []
  const fetchFake = vi.fn(async (request: Request) => {
    requests.push(request.clone())
    const response = responses.shift()
    if (!response) throw new Error('Unexpected request')
    return response
  })
  const auth = {
    getAccessToken: vi.fn(async () => 'old-token'),
    refreshAccessToken: vi.fn(async () => refreshed),
    signOut: vi.fn(async () => {}),
  }
  const api = createApiClient({ baseUrl, fetch: fetchFake, auth })
  return { api, auth, requests }
}

describe('api client', () => {
  it('adds the bearer token to API requests', async () => {
    const { api, requests } = setup([json(200, {})])

    await api.GET('/me')

    expect(requests[0].headers.get('Authorization')).toBe('Bearer old-token')
  })

  it.each(['http://api.test', 'http://api.test/', 'http://api.test/api/v1'])(
    'calls the versioned API path for base URL %s',
    async (baseUrl) => {
      const { api, requests } = setup([json(200, {})], baseUrl)

      await api.GET('/me')

      expect(requests[0].url).toBe('http://api.test/api/v1/me')
    },
  )

  it('refreshes once after 401 and retries', async () => {
    const { api, auth, requests } = setup([
      unauthorized(),
      json(201, { id: 'org-1' }),
    ])

    const { data } = await api.POST('/organizations', {
      body: { name: 'Acme', slug: 'acme' },
    })

    expect(data).toEqual({ id: 'org-1' })
    expect(auth.refreshAccessToken).toHaveBeenCalledTimes(1)
    expect(auth.signOut).not.toHaveBeenCalled()
    expect(requests).toHaveLength(2)
    expect(requests[1].headers.get('Authorization')).toBe('Bearer new-token')
    await expect(requests[1].json()).resolves.toEqual({
      name: 'Acme',
      slug: 'acme',
    })
  })

  it('signs out after a second 401', async () => {
    const { api, auth, requests } = setup([unauthorized(), unauthorized()])

    await expect(api.GET('/me')).rejects.toMatchObject({ status: 401 })

    expect(requests).toHaveLength(2)
    expect(auth.refreshAccessToken).toHaveBeenCalledTimes(1)
    expect(auth.signOut).toHaveBeenCalledTimes(1)
  })

  it('signs out without retrying when the refresh fails', async () => {
    const { api, auth, requests } = setup(
      [unauthorized()],
      'http://api.test/api/v1',
      null,
    )

    await expect(api.GET('/me')).rejects.toMatchObject({ status: 401 })

    expect(requests).toHaveLength(1)
    expect(auth.signOut).toHaveBeenCalledTimes(1)
  })

  it('shares one refresh between concurrent 401 responses', async () => {
    const { api, auth } = setup([
      unauthorized(),
      unauthorized(),
      json(200, {}),
      json(200, {}),
    ])

    await Promise.all([api.GET('/me'), api.GET('/me')])

    expect(auth.refreshAccessToken).toHaveBeenCalledTimes(1)
  })

  it('throws ApiError with the problem body', async () => {
    const problem = { title: 'Invalid', status: 422, errors: { name: ['Required'] } }
    const { api, auth } = setup([json(422, problem)])

    const error = await api.GET('/me').catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 422, problem })
    expect(auth.refreshAccessToken).not.toHaveBeenCalled()
  })

  it('throws ApiError when an error response is not JSON', async () => {
    const { api } = setup([
      new Response('Bad gateway', { status: 502, statusText: 'Bad Gateway' }),
    ])

    await expect(api.GET('/me')).rejects.toMatchObject({
      status: 502,
      problem: { title: 'Bad Gateway', status: 502 },
    })
  })
})
