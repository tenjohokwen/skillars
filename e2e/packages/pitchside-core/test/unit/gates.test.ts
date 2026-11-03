import type { ConsoleMessage, Route } from '@playwright/test'
import { describe, expect, it } from 'vitest'
import {
  ConsoleGate,
  flattenViolations,
  GateViolationError,
  isAllowed,
  NetworkGate,
  newFindings,
  parseGateMode,
  toBaseline,
  type ConsoleEventSource,
} from '../../src/index.js'

class FakePage implements ConsoleEventSource {
  private readonly listeners: Record<string, ((arg: never) => void)[]> = {}
  on(event: string, listener: (arg: never) => void): this {
    ;(this.listeners[event] ??= []).push(listener)
    return this
  }
  url(): string {
    return 'http://app.test/#/home'
  }
  emit(event: string, arg: unknown): void {
    for (const listener of this.listeners[event] ?? []) listener(arg as never)
  }
}

const consoleMessage = (type: string, text: string): ConsoleMessage =>
  ({
    type: () => type,
    text: () => text,
    location: () => ({ url: 'http://app.test/app.js', lineNumber: 10, columnNumber: 2 }),
  }) as unknown as ConsoleMessage

describe('ConsoleGate', () => {
  it('records console errors and page errors, honouring the ignore list', () => {
    const page = new FakePage()
    const gate = new ConsoleGate({ mode: 'strict', ignore: ['ResizeObserver loop', /favicon/] })
    gate.attach(page)
    page.emit('console', consoleMessage('warning', 'just a warning'))
    page.emit('console', consoleMessage('error', 'ResizeObserver loop limit exceeded'))
    page.emit('console', consoleMessage('error', 'GET /favicon.ico 404'))
    page.emit(
      'console',
      consoleMessage('error', 'Cannot read properties of undefined (reading averageRating)'),
    )
    page.emit('pageerror', new Error('boom'))

    expect(gate.violations().map((v) => v.kind)).toEqual(['console.error', 'pageerror'])
    expect(gate.violations()[0]?.location).toBe('http://app.test/app.js:10:2')
    let thrown: unknown
    try {
      gate.assertClean()
    } catch (error) {
      thrown = error
    }
    expect(thrown).toBeInstanceOf(GateViolationError)
    expect(String((thrown as Error).message)).toMatch(/averageRating/)
    // Reported once: the default page's context and each actor's teardown all assert.
    expect(() => gate.assertClean()).not.toThrow()
  })

  it("skips the browser's own resource-load errors unless asked to keep them", () => {
    const resource =
      'Failed to load resource: the server responded with a status of 401 (Unauthorized)'
    const page = new FakePage()
    const lenient = new ConsoleGate({ mode: 'strict' })
    lenient.attach(page)
    page.emit('console', consoleMessage('error', resource))
    expect(lenient.violations()).toHaveLength(0)

    const other = new FakePage()
    const thorough = new ConsoleGate({ mode: 'strict', includeResourceErrors: true })
    thorough.attach(other)
    other.emit('console', consoleMessage('error', resource))
    expect(thorough.violations()).toHaveLength(1)
  })

  it('only reports in warn mode, and installs nothing when off', () => {
    const page = new FakePage()
    const warn = new ConsoleGate({ mode: 'warn' })
    warn.attach(page)
    page.emit('pageerror', new Error('boom'))
    expect(warn.violations()).toHaveLength(1)
    expect(() => warn.assertClean()).not.toThrow()

    const quiet = new FakePage()
    const off = new ConsoleGate({ mode: 'off' })
    off.attach(quiet)
    quiet.emit('pageerror', new Error('boom'))
    expect(off.violations()).toHaveLength(0)
  })
})

describe('NetworkGate', () => {
  const options = {
    firstPartyOrigins: ['http://app.test:9990', 'http://app.test:9990/api'],
    allow: [/^https:\/\/fonts\.gstatic\.com\//],
  }

  it('allows first-party origins, allow-listed URLs and inline schemes only', () => {
    expect(isAllowed('http://app.test:9990/#/x', options)).toBe(true)
    expect(isAllowed('https://fonts.gstatic.com/s/a.woff2', options)).toBe(true)
    expect(isAllowed('data:image/png;base64,AA', options)).toBe(true)
    expect(isAllowed('https://js.stripe.com/v3', options)).toBe(false)
    expect(isAllowed('http://app.test:9991/', options)).toBe(false)
    expect(isAllowed('not a url', options)).toBe(false)
  })

  it('aborts and records blocked requests, and fails in strict mode', async () => {
    const gate = new NetworkGate({ mode: 'strict', ...options })
    const actions: string[] = []
    const route = (url: string) =>
      ({
        request: () => ({ url: () => url, method: () => 'GET', resourceType: () => 'script' }),
        abort: async (code: string) => void actions.push(`abort ${code}`),
        fallback: async () => void actions.push('fallback'),
      }) as unknown as Route

    await gate.handle(route('http://app.test:9990/app.js'))
    await gate.handle(route('https://js.stripe.com/v3'))
    expect(actions).toEqual(['fallback', 'abort blockedbyclient'])
    expect(gate.violations()).toEqual([
      { url: 'https://js.stripe.com/v3', method: 'GET', resourceType: 'script' },
    ])
    expect(() => gate.assertClean()).toThrow(/js.stripe.com/)
  })

  it('does not install a route when off', async () => {
    let routed = false
    await new NetworkGate({ mode: 'off', ...options }).install({
      route: async () => void (routed = true),
    })
    expect(routed).toBe(false)
  })
})

describe('a11y baselines', () => {
  const violations = [
    {
      id: 'color-contrast',
      impact: 'serious',
      help: 'Contrast',
      nodes: [{ target: ['.chip'] }, { target: ['#iframe', 'button'] }],
    },
    { id: 'label', impact: 'critical', help: 'Label', nodes: [{ target: ['input#email'] }] },
  ]

  it('flattens to rule/target pairs and diffs against a baseline', () => {
    const findings = flattenViolations(violations)
    expect(findings.map((f) => f.target)).toEqual(['.chip', '#iframe >>> button', 'input#email'])
    const baseline = toBaseline('LoginScreen', findings.slice(0, 2))
    expect(baseline.accepted).toEqual([
      { rule: 'color-contrast', target: '#iframe >>> button' },
      { rule: 'color-contrast', target: '.chip' },
    ])
    expect(newFindings(findings, baseline).map((f) => f.rule)).toEqual(['label'])
    expect(newFindings(findings, null)).toHaveLength(3)
  })
})

describe('parseGateMode', () => {
  it('parses known modes and falls back on empty', () => {
    expect(parseGateMode('warn', 'strict')).toBe('warn')
    expect(parseGateMode(undefined, 'strict')).toBe('strict')
    expect(parseGateMode('', 'off')).toBe('off')
    expect(() => parseGateMode('loud', 'strict')).toThrow(/Invalid gate mode/)
  })
})
