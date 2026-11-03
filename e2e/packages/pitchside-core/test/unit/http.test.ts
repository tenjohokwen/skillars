import type { APIResponse } from '@playwright/test'
import { describe, expect, it } from 'vitest'
import { ApiClient, ApiError, type RequestPort } from '../../src/index.js'

interface Call {
  url: string
  method: string
  headers: Record<string, string>
}

const response = (status: number, body = ''): APIResponse =>
  ({
    status: () => status,
    ok: () => status >= 200 && status < 300,
    text: async () => body,
    json: async (): Promise<unknown> => JSON.parse(body) as unknown,
    dispose: async () => {},
  }) as unknown as APIResponse

function fakePort(
  statuses: (number | Error)[],
  body = '{"ok":true}',
): { port: RequestPort; calls: Call[] } {
  const calls: Call[] = []
  const port: RequestPort = {
    fetch: async (url: string, options: { method: string; headers: Record<string, string> }) => {
      calls.push({ url, method: options.method, headers: options.headers })
      const next = statuses.shift() ?? 200
      if (next instanceof Error) throw next
      return response(next, body)
    },
  }
  return { port, calls }
}

const noSleep = async (): Promise<void> => {}

describe('ApiClient', () => {
  it('joins the base URL and stamps a request id', async () => {
    const { port, calls } = fakePort([200])
    const client = new ApiClient(port, {
      baseUrl: 'http://app.test/api/',
      requestIdPrefix: 'ui',
      sleep: noSleep,
    })
    await client.get('/players')
    expect(calls[0]?.url).toBe('http://app.test/api/players')
    expect(calls[0]?.headers['X-Request-Id']).toMatch(/^ui-[0-9a-f]{6}-1$/)
    expect(client.lastRequestId).toBe(calls[0]?.headers['X-Request-Id'])
  })

  it('retries idempotent calls on gateway errors and network failures', async () => {
    const { port, calls } = fakePort([503, new Error('ECONNRESET'), 200])
    const client = new ApiClient(port, { sleep: noSleep })
    const result = await client.get('http://app.test/health')
    expect(result.status()).toBe(200)
    expect(calls).toHaveLength(3)
  })

  it('never replays a POST unless asked to', async () => {
    const { port, calls } = fakePort([503])
    const client = new ApiClient(port, { sleep: noSleep })
    expect((await client.post('http://app.test/bookings')).status()).toBe(503)
    expect(calls).toHaveLength(1)

    const optedIn = fakePort([503, 201])
    const retrying = new ApiClient(optedIn.port, { sleep: noSleep, retryNonIdempotent: true })
    expect((await retrying.post('http://app.test/bookings')).status()).toBe(201)
  })

  it('reports method, url, status, request id and body on an unexpected status', async () => {
    const { port } = fakePort([409], '{"error":"slot taken"}')
    const client = new ApiClient(port, { sleep: noSleep })
    const failing = client.post('http://app.test/bookings', { expectStatus: 201 })
    await expect(failing).rejects.toBeInstanceOf(ApiError)
    await expect(failing).rejects.toThrow(
      /POST http:\/\/app.test\/bookings returned 409 \[request e2e-.*\]: \{"error":"slot taken"\}/,
    )
  })

  it('parses JSON and asserts a 2xx by default', async () => {
    const { port } = fakePort([200, 500], '{"id":"9650000001"}')
    const client = new ApiClient(port, { sleep: noSleep, retries: 0 })
    expect(await client.json<{ id: string }>('GET', 'http://app.test/me')).toEqual({
      id: '9650000001',
    })
    await expect(client.json('GET', 'http://app.test/me')).rejects.toThrow(/returned 500/)
  })

  it('can turn the request id header off', async () => {
    const { port, calls } = fakePort([200])
    await new ApiClient(port, { requestIdHeader: null, sleep: noSleep }).get('http://app.test/x')
    expect(calls[0]?.headers).toEqual({})
  })
})
