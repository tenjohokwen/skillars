import type { Browser, BrowserContextOptions } from '@playwright/test'
import type { AuthStrategy, StorageState } from '../auth/AuthStrategy.js'

/**
 * Worker-scoped cache of one storage state per role. The first request for a role logs in through
 * the auth strategy in a throwaway primed context; every later request reuses the snapshot.
 */
export class SessionStore {
  private readonly cache = new Map<string, Promise<StorageState>>()

  constructor(
    private readonly browser: Pick<Browser, 'newContext'>,
    private readonly auth: AuthStrategy,
    private readonly contextOptions: BrowserContextOptions = {},
  ) {}

  state(role: string): Promise<StorageState> {
    let pending = this.cache.get(role)
    if (!pending) {
      pending = this.establish(role)
      this.cache.set(role, pending)
      // A failed login must not poison the cache for the rest of the worker.
      pending.catch(() => this.cache.delete(role))
    }
    return pending
  }

  /**
   * Forget a role's session — after a test that revokes sessions (password reset, logout-everywhere),
   * the next test must log in again rather than reuse a dead refresh token.
   */
  invalidate(role?: string): void {
    if (role === undefined) this.cache.clear()
    else this.cache.delete(role)
  }

  cachedRoles(): string[] {
    return [...this.cache.keys()]
  }

  private async establish(role: string): Promise<StorageState> {
    const context = await this.browser.newContext(this.contextOptions)
    try {
      const priming = (await this.auth.priming?.()) ?? []
      if (priming.length > 0) await context.addCookies(priming)
      return await this.auth.establish(role, context)
    } finally {
      await context.close()
    }
  }
}
