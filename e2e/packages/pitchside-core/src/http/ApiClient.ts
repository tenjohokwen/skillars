import { randomBytes } from 'node:crypto'
import type { APIRequestContext, APIResponse } from '@playwright/test'
import { realSleep } from '../util/poll.js'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD'

/** The subset of Playwright's APIRequestContext the client needs — easy to fake in a unit test. */
export type RequestPort = Pick<APIRequestContext, 'fetch'>

export interface ApiClientOptions {
  /** Prepended to relative paths. Leave unset if the request context already has a baseURL. */
  baseUrl?: string
  /** Extra attempts after the first, for retryable failures. Default 2. */
  retries?: number
  /** Which statuses are retried. Default: 502, 503, 504. */
  retryOnStatus?: (status: number) => boolean
  /**
   * Retry POST/PATCH too. Off by default: replaying a non-idempotent call that actually reached
   * the server is how a fixture ends up with two bookings and a confusing failure.
   */
  retryNonIdempotent?: boolean
  /** Base backoff in ms, doubled per attempt. Default 200. */
  backoffMs?: number
  /** Header carrying a per-request id the app can log, e.g. "X-Request-Id". Null disables it. */
  requestIdHeader?: string | null
  /** Prefix for generated request ids, so suite traffic is greppable in server logs. */
  requestIdPrefix?: string
  defaultHeaders?: Record<string, string>
  sleep?: (ms: number) => Promise<void>
}

export interface RequestOptions {
  data?: unknown
  params?: Record<string, string | number | boolean>
  headers?: Record<string, string>
  /** Throw an ApiError unless the status matches (a number, list, or predicate). */
  expectStatus?: number | readonly number[] | ((status: number) => boolean)
  timeoutMs?: number
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly requestId: string | undefined,
    readonly bodySnippet: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const IDEMPOTENT: ReadonlySet<HttpMethod> = new Set(['GET', 'HEAD', 'PUT', 'DELETE'])

/**
 * A thin layer over Playwright's request context: base URL joining, bounded retry of idempotent
 * calls on gateway errors, a request id on every call for log correlation, and errors that say
 * which call failed and with what body — instead of a bare "expected 200, got 500".
 */
export class ApiClient {
  private sequence = 0
  private readonly session = randomBytes(3).toString('hex')
  lastRequestId: string | undefined

  constructor(
    private readonly port: RequestPort,
    private readonly options: ApiClientOptions = {},
  ) {}

  get(path: string, options?: RequestOptions): Promise<APIResponse> {
    return this.request('GET', path, options)
  }
  post(path: string, options?: RequestOptions): Promise<APIResponse> {
    return this.request('POST', path, options)
  }
  put(path: string, options?: RequestOptions): Promise<APIResponse> {
    return this.request('PUT', path, options)
  }
  patch(path: string, options?: RequestOptions): Promise<APIResponse> {
    return this.request('PATCH', path, options)
  }
  delete(path: string, options?: RequestOptions): Promise<APIResponse> {
    return this.request('DELETE', path, options)
  }

  /** Request and parse JSON, asserting a 2xx unless `expectStatus` says otherwise. */
  async json<T>(method: HttpMethod, path: string, options: RequestOptions = {}): Promise<T> {
    const response = await this.request(method, path, {
      expectStatus: (status) => status >= 200 && status < 300,
      ...options,
    })
    return (await response.json()) as T
  }

  async request(
    method: HttpMethod,
    path: string,
    options: RequestOptions = {},
  ): Promise<APIResponse> {
    const url = this.resolve(path)
    const retries = this.options.retries ?? 2
    const retryable = this.options.retryNonIdempotent || IDEMPOTENT.has(method)
    const retryOnStatus =
      this.options.retryOnStatus ?? ((s: number) => s === 502 || s === 503 || s === 504)
    const sleep = this.options.sleep ?? realSleep
    const backoff = this.options.backoffMs ?? 200

    let attempt = 0
    for (;;) {
      const requestId = this.nextRequestId()
      const headers: Record<string, string> = { ...this.options.defaultHeaders, ...options.headers }
      const idHeader =
        this.options.requestIdHeader === undefined ? 'X-Request-Id' : this.options.requestIdHeader
      if (idHeader) headers[idHeader] = requestId
      this.lastRequestId = requestId

      let response: APIResponse
      try {
        response = await this.port.fetch(url, {
          method,
          headers,
          data: options.data,
          params: options.params,
          timeout: options.timeoutMs,
          failOnStatusCode: false,
        })
      } catch (error) {
        if (retryable && attempt < retries) {
          await sleep(backoff * 2 ** attempt)
          attempt += 1
          continue
        }
        const reason = error instanceof Error ? error.message : String(error)
        throw new ApiError(
          `${method} ${url} failed [request ${requestId}]: ${reason}`,
          0,
          requestId,
          '',
        )
      }

      if (retryable && attempt < retries && retryOnStatus(response.status())) {
        await response.dispose()
        await sleep(backoff * 2 ** attempt)
        attempt += 1
        continue
      }

      if (options.expectStatus !== undefined && !matches(options.expectStatus, response.status())) {
        const snippet = (await safeText(response)).slice(0, 500)
        throw new ApiError(
          `${method} ${url} returned ${response.status()} [request ${requestId}]: ${snippet}`,
          response.status(),
          requestId,
          snippet,
        )
      }
      return response
    }
  }

  private resolve(path: string): string {
    if (/^https?:\/\//i.test(path) || !this.options.baseUrl) return path
    return `${this.options.baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`
  }

  private nextRequestId(): string {
    this.sequence += 1
    return `${this.options.requestIdPrefix ?? 'e2e'}-${this.session}-${this.sequence}`
  }
}

function matches(expect: NonNullable<RequestOptions['expectStatus']>, status: number): boolean {
  if (typeof expect === 'number') return status === expect
  if (typeof expect === 'function') return expect(status)
  return expect.includes(status)
}

async function safeText(response: APIResponse): Promise<string> {
  try {
    return await response.text()
  } catch {
    return '<unreadable body>'
  }
}
