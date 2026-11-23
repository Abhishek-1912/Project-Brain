// @vitest-environment node
import { afterEach, expect, it, vi } from 'vitest'
import { uploadRequirement } from './useRequirements'

vi.mock('../../auth/supabase', () => ({
  supabase: {
    auth: {
      getSession: async () => ({ data: { session: { access_token: 'test-token' } }, error: null }),
    },
  },
}))

afterEach(() => vi.unstubAllGlobals())

it('sends a real multipart upload with the dummy-data confirmation', async () => {
  let contentType: string | null = null
  let form: FormData | undefined
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      contentType = request.headers.get('Content-Type')
      form = await request.formData()
      return new Response(JSON.stringify({ id: 'document' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    }),
  )

  await uploadRequirement('org', 'project', new File(['Dummy PRD'], 'dummy.md'), 'role')

  expect(contentType).toMatch(/^multipart\/form-data; boundary=/)
  expect(form?.get('dummyDataConfirmed')).toBe('true')
  expect(form?.get('approverJobRoleId')).toBe('role')
  const file = form?.get('file') as File
  expect(file.name).toBe('dummy.md')
  expect(await file.text()).toBe('Dummy PRD')
})
