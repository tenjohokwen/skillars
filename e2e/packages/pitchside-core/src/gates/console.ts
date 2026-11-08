import type { ConsoleMessage, Page } from '@playwright/test'
import { GateViolationError, type GateMode } from './GateMode.js'

export interface ConsoleViolation {
  kind: 'console.error' | 'pageerror'
  text: string
  pageUrl: string
  location?: string
  stack?: string
}

export interface ConsoleGateOptions {
  mode: GateMode
  /** Messages to ignore (substring or pattern), e.g. a known third-party warning. Keep it short. */
  ignore?: readonly (string | RegExp)[]
  /**
   * Count the browser's own "Failed to load resource: … status of 4xx/5xx" messages. Off by default:
   * the browser logs one for every non-2xx response, so a strict gate would fail every legitimate
   * negative test (wrong password, injected outage). The gate exists to catch app errors; assert on
   * HTTP outcomes explicitly instead.
   */
  includeResourceErrors?: boolean
}

/** The browser's network-level message, emitted for any non-2xx response — not an app error. */
export const BROWSER_RESOURCE_ERROR = /^Failed to load resource: /

/** The page events the gate listens to — a structural type so a unit test can pass a fake. */
export interface ConsoleEventSource {
  on(event: 'console', listener: (message: ConsoleMessage) => void): unknown
  on(event: 'pageerror', listener: (error: Error) => void): unknown
  url(): string
}

/**
 * Fails a test on any `console.error` or uncaught exception in the page — the cheapest real bug
 * per minute in a UI suite, because it catches crashes no scenario was written for.
 */
export class ConsoleGate {
  private readonly found: ConsoleViolation[] = []
  private readonly allowed: (string | RegExp)[] = []

  constructor(private readonly options: ConsoleGateOptions) {}

  get mode(): GateMode {
    return this.options.mode
  }

  attach(page: ConsoleEventSource | Page): void {
    if (this.options.mode === 'off') return
    const source = page
    source.on('console', (message: ConsoleMessage) => {
      if (message.type() !== 'error') return
      if (!this.options.includeResourceErrors && BROWSER_RESOURCE_ERROR.test(message.text())) return
      const location = message.location()
      this.record({
        kind: 'console.error',
        text: message.text(),
        pageUrl: source.url(),
        location: location.url
          ? `${location.url}:${location.lineNumber}:${location.columnNumber}`
          : undefined,
      })
    })
    source.on('pageerror', (error: Error) => {
      this.record({
        kind: 'pageerror',
        text: error.message,
        pageUrl: source.url(),
        stack: error.stack,
      })
    })
  }

  /**
   * Accept these messages for the rest of this test only: for a spec that provokes an error on purpose (a 500
   * from a stubbed endpoint, a page taken offline) and asserts how the app copes. The gate is test-scoped, so
   * the allowance never leaks into another test. Messages recorded before the call still count.
   */
  allow(...patterns: (string | RegExp)[]): void {
    this.allowed.push(...patterns)
  }

  violations(): readonly ConsoleViolation[] {
    return this.found
  }

  private reported = false

  /**
   * Throws in strict mode when anything was recorded — once: the default page's context and each
   * actor's teardown all assert, and one failure should not be reported twice.
   */
  assertClean(): void {
    if (this.options.mode !== 'strict' || this.found.length === 0 || this.reported) return
    this.reported = true
    throw new GateViolationError(
      'Console',
      this.found.map(
        (v) => `${v.kind} on ${v.pageUrl}: ${v.text}${v.location ? ` (${v.location})` : ''}`,
      ),
    )
  }

  private record(violation: ConsoleViolation): void {
    const ignored = [...(this.options.ignore ?? []), ...this.allowed].some((rule) =>
      typeof rule === 'string' ? violation.text.includes(rule) : rule.test(violation.text),
    )
    if (!ignored) this.found.push(violation)
  }
}
