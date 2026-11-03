import { describe, expect, it } from 'vitest'
import {
  assertTestId,
  DEFAULT_SETTLE_TIMEOUT_MS,
  hashRouting,
  historyRouting,
  interpolate,
  resolveSettleTimeout,
  routePattern,
  rowTestId,
  settleTimeoutMs,
  waitForSettledRoute,
} from '../../src/index.js'

describe('hashRouting', () => {
  const routing = hashRouting('http://app.test:9990/')

  it('maps routes to hash URLs and back, ignoring the query', () => {
    expect(routing.toUrl('/coach/home')).toBe('http://app.test:9990/#/coach/home')
    expect(routing.toUrl('login')).toBe('http://app.test:9990/#/login')
    expect(routing.fromUrl('http://app.test:9990/#/login?redirect=%2Fx')).toBe('/login')
    expect(routing.fromUrl('http://app.test:9990/')).toBe('/')
  })
})

describe('historyRouting', () => {
  const routing = historyRouting('https://app.test/portal/')

  it('keeps a base path prefix', () => {
    expect(routing.toUrl('/settings')).toBe('https://app.test/portal/settings')
    expect(routing.fromUrl('https://app.test/portal/settings?tab=1')).toBe('/settings')
    expect(routing.fromUrl('https://app.test/portal')).toBe('/')
  })
})

describe('interpolate', () => {
  it('fills params, drops constraints and encodes values', () => {
    expect(interpolate('/players/:playerId(\\d+)/packs', { playerId: 9650000001n })).toBe(
      '/players/9650000001/packs',
    )
    expect(interpolate('/search/:q', { q: 'a b/c' })).toBe('/search/a%20b%2Fc')
  })

  it('rejects missing and unknown params', () => {
    expect(() => interpolate('/players/:id', {})).toThrow(/needs parameter "id"/)
    expect(() => interpolate('/players', { id: 1 })).toThrow(/has no parameter/)
  })
})

describe('routePattern', () => {
  it('honours constraints and matches one segment for plain params', () => {
    const constrained = routePattern('/players/:id(\\d+)/packs')
    expect(constrained.test('/players/42/packs')).toBe(true)
    expect(constrained.test('/players/abc/packs')).toBe(false)
    expect(routePattern('/coaches/:id').test('/coaches/a/b')).toBe(false)
    expect(routePattern('/coaches/:id').test('/coaches/a/')).toBe(true)
  })
})

describe('waitForSettledRoute', () => {
  it('waits past an intermediate redirect until the route stops changing', async () => {
    const routing = hashRouting('http://app.test')
    const timeline = [
      '/login',
      '/home',
      '/home',
      '/locker-room/7',
      '/locker-room/7',
      '/locker-room/7',
    ]
    let reads = 0
    const page = {
      url: () => routing.toUrl(timeline[Math.min(reads++, timeline.length - 1)] ?? '/'),
    }
    const settled = await waitForSettledRoute(page, routing, '/locker-room/:id(\\d+)', {
      stableMs: 60,
    })
    expect(settled).toBe('/locker-room/7')
    expect(reads).toBeGreaterThanOrEqual(timeline.length)
  })

  it('times out naming the last route it saw', async () => {
    const routing = hashRouting('http://app.test')
    const page = { url: () => routing.toUrl('/login') }
    await expect(
      waitForSettledRoute(page, routing, '/home', { timeoutMs: 120, stableMs: 10 }),
    ).rejects.toThrow(/last seen: \/login/)
  })
})

describe('resolveSettleTimeout', () => {
  it('prefers navigationTimeout, then actionTimeout, then the default', () => {
    expect(resolveSettleTimeout({ navigationTimeout: 30_000, actionTimeout: 5_000 })).toBe(30_000)
    expect(resolveSettleTimeout({ actionTimeout: 5_000 })).toBe(5_000)
    expect(resolveSettleTimeout({})).toBe(DEFAULT_SETTLE_TIMEOUT_MS)
    expect(resolveSettleTimeout(undefined)).toBe(DEFAULT_SETTLE_TIMEOUT_MS)
  })

  it('skips 0, which Playwright reads as "no limit"', () => {
    expect(resolveSettleTimeout({ navigationTimeout: 0, actionTimeout: 4_000 })).toBe(4_000)
    expect(resolveSettleTimeout({ navigationTimeout: 0, actionTimeout: 0 })).toBe(
      DEFAULT_SETTLE_TIMEOUT_MS,
    )
  })

  it('falls back to the default outside a running Playwright test', () => {
    expect(settleTimeoutMs()).toBe(DEFAULT_SETTLE_TIMEOUT_MS)
  })
})

describe('test ids', () => {
  it('enforces lower-kebab-case', () => {
    expect(assertTestId('login-email-input')).toBe('login-email-input')
    expect(() => assertTestId('loginEmail')).toThrow(/naming policy/)
    expect(() => assertTestId('login_email')).toThrow()
    expect(rowTestId('conversation-item', 9650000001n)).toBe('conversation-item-9650000001')
  })
})
