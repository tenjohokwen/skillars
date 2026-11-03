import type { Browser, BrowserContext } from '@playwright/test'
import { describe, expect, it, vi } from 'vitest'
import {
  CookieSessionStrategy,
  defineTaxonomy,
  hashRouting,
  laneProjects,
  mergeSessionState,
  optionalEnv,
  requiredEnv,
  SessionStore,
  validateSuiteEnv,
  videoMode,
  withPriming,
  type AuthStrategy,
  type SuiteEnv,
} from '../../src/index.js'

const validEnv = (): SuiteEnv => ({
  name: 'demo',
  baseUrl: 'http://localhost:4321',
  apiBaseUrl: 'http://localhost:4321/api',
  routing: hashRouting('http://localhost:4321'),
  auth: new CookieSessionStrategy({ cookies: async () => [] }),
  taxonomy: defineTaxonomy({ persona: ['@guest'] }),
  ids: { base: 1_000_000n },
  emailDomain: 'example.test',
})

describe('validateSuiteEnv', () => {
  it('accepts a complete env', () => {
    expect(validateSuiteEnv(validEnv())).toEqual([])
  })

  it('lists every problem at once', () => {
    const problems = validateSuiteEnv({
      ...validEnv(),
      name: ' ',
      baseUrl: 'localhost:4321',
      apiBaseUrl: 'ftp://x',
      emailDomain: 'nope',
      ids: { base: -1n },
    })
    expect(problems).toHaveLength(5)
  })
})

describe('env helpers', () => {
  it('reads required and optional variables', () => {
    expect(requiredEnv('A', { A: 'x' })).toBe('x')
    expect(() => requiredEnv('B', { B: ' ' })).toThrow(/B is required/)
    expect(optionalEnv('C', 'fallback', {})).toBe('fallback')
  })
})

describe('laneProjects', () => {
  it('splits serial-lane tags into a dependent project', () => {
    const [parallel, serial] = laneProjects({ namePrefix: 'chromium' })
    expect(parallel?.name).toBe('chromium-parallel')
    expect(serial?.dependencies).toEqual(['chromium-parallel'])
    const grep = serial?.grep as RegExp
    expect(grep.test('wipes @regression @destructive')).toBe(true)
    expect(grep.test('edits @regression @config')).toBe(true)
    expect(grep.test('edits @regression @config-viewer')).toBe(false)
    expect((parallel?.grepInvert as RegExp).test('wipes @destructive')).toBe(true)
  })
})

function fakeBrowser() {
  const contexts: { cookies: unknown[]; closed: boolean }[] = []
  const browser = {
    newContext: vi.fn(async () => {
      const record = { cookies: [] as unknown[], closed: false }
      contexts.push(record)
      return {
        addCookies: async (cookies: unknown[]) => void record.cookies.push(...cookies),
        storageState: async () => ({ cookies: record.cookies, origins: [] }),
        close: async () => void (record.closed = true),
      } as unknown as BrowserContext
    }),
  } as unknown as Pick<Browser, 'newContext'>
  return { browser, contexts }
}

describe('SessionStore', () => {
  const cookie = (name: string) => ({ name, value: '1', url: 'http://localhost:4321' })

  it('establishes once per role in a primed throwaway context', async () => {
    const { browser, contexts } = fakeBrowser()
    const auth = withPriming(
      new CookieSessionStrategy({ cookies: async (role) => [cookie(`session-${role}`)] }),
      async () => [cookie('fingerprint')],
    )
    const store = new SessionStore(browser, auth)
    const first = await store.state('coach')
    const again = await store.state('coach')
    expect(again).toBe(first)
    expect(first.cookies.map((c) => c.name)).toEqual(['fingerprint', 'session-coach'])
    expect(contexts).toHaveLength(1)
    expect(contexts[0]?.closed).toBe(true)
    expect(store.cachedRoles()).toEqual(['coach'])
  })

  it('does not cache a failed login, and can be invalidated', async () => {
    const { browser } = fakeBrowser()
    let attempts = 0
    const flaky: AuthStrategy = {
      establish: async (_role, context) => {
        attempts += 1
        if (attempts === 1) throw new Error('login refused')
        return context.storageState()
      },
      logout: async () => {},
    }
    const store = new SessionStore(browser, flaky)
    await expect(store.state('parent')).rejects.toThrow('login refused')
    await expect(store.state('parent')).resolves.toBeDefined()
    store.invalidate('parent')
    await store.state('parent')
    expect(attempts).toBe(3)
  })
})

describe('videoMode', () => {
  it('defaults to retain-on-failure and validates overrides', () => {
    expect(videoMode(undefined)).toBe('retain-on-failure')
    expect(videoMode('off')).toBe('off')
    expect(() => videoMode('sometimes')).toThrow(/PITCHSIDE_VIDEO/)
  })
})

describe('SessionStore priming and write-back', () => {
  it('primes the login context with the role, so per-persona values stay consistent', async () => {
    const primedFor: (string | undefined)[] = []
    const { browser } = fakeBrowser()
    const auth = new CookieSessionStrategy({
      cookies: async () => [],
      priming: async (role) => {
        primedFor.push(role)
        return [{ name: 'fp', value: `fp-${role}`, url: 'http://localhost:4321' }]
      },
    })
    const state = await new SessionStore(browser, auth).state('coach')
    expect(primedFor).toEqual(['coach'])
    expect(state.cookies.map((c) => `${c.name}=${c.value}`)).toEqual(['fp=fp-coach'])
  })

  it('serves the refreshed session instead of re-establishing or replaying the original', async () => {
    const { browser, contexts } = fakeBrowser()
    const rtkn = { name: 'rtkn', value: 'original', domain: 'app.test', path: '/' }
    const store = new SessionStore(
      browser,
      new CookieSessionStrategy({ cookies: async () => [rtkn] }),
    )
    const original = await store.state('coach', { userAgent: 'UA' })
    expect(original.cookies.map((c) => c.value)).toEqual(['original'])
    store.update('coach', { userAgent: 'UA' }, {
      cookies: [{ ...rtkn, value: 'rotated' }],
      origins: [],
    } as unknown as typeof original)
    const served = await store.state('coach', { userAgent: 'UA' })
    expect(served.cookies.map((c) => c.value)).toEqual(['rotated'])
    expect(contexts).toHaveLength(1)
  })

  it('ignores an update for a role that has no cached login', () => {
    const { browser } = fakeBrowser()
    const store = new SessionStore(browser, new CookieSessionStrategy({ cookies: async () => [] }))
    store.update('coach', {}, { cookies: [], origins: [] })
    expect(store.cachedRoles()).toEqual([])
  })
})

describe('mergeSessionState', () => {
  const cookie = (name: string, value: string, domain = 'app.test') =>
    ({
      name,
      value,
      domain,
      path: '/',
      expires: -1,
      httpOnly: true,
      secure: false,
      sameSite: 'Lax',
    }) as const
  const baseline = {
    cookies: [cookie('rtkn', 'r1'), cookie('potc', 'j1'), cookie('fcookie', 'fp')],
    origins: [{ origin: 'http://app.test', localStorage: [{ name: 'lang', value: 'en' }] }],
  }

  it('refreshes the login cookies, drops removed ones, ignores new ones and keeps login localStorage', () => {
    const current = {
      cookies: [
        cookie('rtkn', 'r2'),
        cookie('fcookie', 'fp'),
        cookie('pref', 'dark'),
        cookie('rtkn', 'x', 'other.test'),
      ],
      origins: [{ origin: 'http://app.test', localStorage: [{ name: 'tour', value: 'done' }] }],
    }
    const merged = mergeSessionState(baseline, current)
    expect(merged.cookies.map((c) => `${c.name}=${c.value}@${c.domain}`)).toEqual([
      'rtkn=r2@app.test',
      'fcookie=fp@app.test',
    ])
    expect(merged.origins).toBe(baseline.origins)
  })
})

describe('SessionStore epoch', () => {
  it('moves on a role invalidation, and on a global one for every role', () => {
    const { browser } = fakeBrowser()
    const store = new SessionStore(browser, new CookieSessionStrategy({ cookies: async () => [] }))
    const coach = store.epoch('coach')
    const parent = store.epoch('parent')
    store.invalidate('coach')
    expect(store.epoch('coach')).toBe(coach + 1)
    expect(store.epoch('parent')).toBe(parent)
    store.invalidate()
    expect(store.epoch('parent')).toBe(parent + 1)
  })
})

describe('SessionStore user-agent pinning', () => {
  it('logs in with the caller user agent and caches per role and user agent', async () => {
    const seen: unknown[] = []
    const browser = {
      newContext: vi.fn(async (options: { userAgent?: string }) => {
        seen.push(options.userAgent)
        return {
          addCookies: async () => {},
          storageState: async () => ({ cookies: [], origins: [] }),
          close: async () => {},
        } as unknown as BrowserContext
      }),
    } as unknown as Pick<Browser, 'newContext'>
    const store = new SessionStore(browser, new CookieSessionStrategy({ cookies: async () => [] }))
    await store.state('coach', { userAgent: 'UA-desktop' })
    await store.state('coach', { userAgent: 'UA-desktop' })
    await store.state('coach', { userAgent: 'UA-mobile' })
    await store.state('coach')
    expect(seen).toEqual(['UA-desktop', 'UA-mobile', undefined])
    expect(store.cachedRoles()).toEqual(['coach'])
    store.invalidate('coach')
    expect(store.cachedRoles()).toEqual([])
  })
})
