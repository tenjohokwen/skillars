import type { Browser, BrowserContextOptions } from '@playwright/test'
import type { AuthStrategy, StorageState } from '../auth/AuthStrategy.js'

/** What the login context must share with the contexts that replay its session. */
export type SessionContextOptions = Pick<BrowserContextOptions, 'userAgent' | 'locale'>

/**
 * Worker-scoped cache of one storage state per role. The first request for a role logs in through
 * the auth strategy in a throwaway primed context; every later request reuses the snapshot.
 *
 * The login context must look like the contexts that will replay the session: apps that pin a
 * session to a fingerprint (User-Agent hash, client id) reject a session minted under a different
 * User-Agent. So the cache is keyed by role AND user agent, and the login context gets the same
 * `userAgent` / `locale` the caller will use.
 */
export class SessionStore {
  private readonly cache = new Map<string, Promise<StorageState>>()
  private readonly invalidations = new Map<string, number>()
  private globalInvalidations = 0

  constructor(
    private readonly browser: Pick<Browser, 'newContext'>,
    private readonly auth: AuthStrategy,
    private readonly contextOptions: BrowserContextOptions = {},
  ) {}

  state(role: string, options: SessionContextOptions = {}): Promise<StorageState> {
    const key = cacheKey(role, options)
    let pending = this.cache.get(key)
    if (!pending) {
      pending = this.establish(role, options)
      this.cache.set(key, pending)
      // A failed login must not poison the cache for the rest of the worker.
      pending.catch(() => this.cache.delete(key))
    }
    return pending
  }

  /**
   * Forget a role's session — after a test that revokes sessions (password reset, logout-everywhere),
   * the next test must log in again rather than reuse a dead refresh token.
   */
  invalidate(role?: string): void {
    if (role === undefined) {
      this.globalInvalidations += 1
      this.cache.clear()
      return
    }
    this.invalidations.set(role, (this.invalidations.get(role) ?? 0) + 1)
    for (const key of [...this.cache.keys()]) if (roleOf(key) === role) this.cache.delete(key)
  }

  /**
   * A counter that moves whenever `role` is invalidated (or everything is). Callers compare epochs to
   * tell whether an invalidation happened since they took a session — and must not write back.
   */
  epoch(role: string): number {
    return this.globalInvalidations + (this.invalidations.get(role) ?? 0)
  }

  /**
   * Replace a role's cached session with a newer snapshot — call it when a context that used the
   * session closes. Apps rotate refresh tokens on use; replaying the ORIGINAL snapshot after a
   * rotation looks like token theft to reuse detection, which revokes every session of that user.
   */
  update(role: string, options: SessionContextOptions, state: StorageState): void {
    this.cache.set(cacheKey(role, options), Promise.resolve(state))
  }

  cachedRoles(): string[] {
    return [...new Set([...this.cache.keys()].map(roleOf))]
  }

  private async establish(role: string, options: SessionContextOptions): Promise<StorageState> {
    const context = await this.browser.newContext({
      ...this.contextOptions,
      ...definedOnly(options),
    })
    try {
      const priming = (await this.auth.priming?.(role)) ?? []
      if (priming.length > 0) await context.addCookies(priming)
      return await this.auth.establish(role, context)
    } finally {
      await context.close()
    }
  }
}

const SEPARATOR = '\u0000'
const cacheKey = (role: string, options: SessionContextOptions): string =>
  [role, options.userAgent ?? '', options.locale ?? ''].join(SEPARATOR)
const roleOf = (key: string): string => key.split(SEPARATOR)[0] ?? key
const definedOnly = (options: SessionContextOptions): SessionContextOptions =>
  Object.fromEntries(Object.entries(options).filter(([, value]) => value !== undefined))
