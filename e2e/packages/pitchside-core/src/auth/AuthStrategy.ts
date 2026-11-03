import type { BrowserContext, Page } from '@playwright/test'
import type { Routing } from '../config/routing.js'

export type StorageState = Awaited<ReturnType<BrowserContext['storageState']>>
export type Cookie = Parameters<BrowserContext['addCookies']>[0][number]

export interface Credentials {
  username: string
  password: string
}

export type CredentialsResolver = (role: string) => Credentials | Promise<Credentials>

/**
 * How a role gets a session. `establish` runs once per role per worker; its storage state is then
 * reused for every test in that worker.
 */
export interface AuthStrategy {
  /** Produce a reusable storage state for this role, using a fresh primed context. */
  establish(role: string, context: BrowserContext): Promise<StorageState>
  /**
   * Cookies the app needs before its first paint (e.g. a device fingerprint). Called with the role
   * for a logged-in context — at login AND for every context that replays that role's session, so a
   * per-persona value stays consistent — and with no role for an anonymous context.
   */
  priming?(role?: string): Promise<Cookie[]>
  logout(context: BrowserContext): Promise<void>
}

export class AuthError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthError'
  }
}

export interface ApiSessionStrategyOptions {
  /** Absolute API base, e.g. http://localhost:9990/api */
  apiBaseUrl: string
  loginPath: string
  logoutPath?: string
  credentials: CredentialsResolver
  /** Request body for the login call. Default `{ username, password }`. */
  body?: (credentials: Credentials, role: string) => unknown
  priming?: (role?: string) => Promise<Cookie[]>
}

const join = (base: string, path: string): string =>
  `${base.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`

/**
 * Logs in through the API from inside the browser context, so the session cookies land in the
 * context's own jar, then snapshots the storage state. The fast default for every suite except the
 * one that tests the login form itself.
 */
export class ApiSessionStrategy implements AuthStrategy {
  constructor(private readonly options: ApiSessionStrategyOptions) {}

  async priming(role?: string): Promise<Cookie[]> {
    return (await this.options.priming?.(role)) ?? []
  }

  async establish(role: string, context: BrowserContext): Promise<StorageState> {
    const credentials = await this.options.credentials(role)
    const data = this.options.body
      ? this.options.body(credentials, role)
      : { username: credentials.username, password: credentials.password }
    const response = await context.request.post(
      join(this.options.apiBaseUrl, this.options.loginPath),
      {
        data,
        failOnStatusCode: false,
      },
    )
    if (!response.ok()) {
      throw new AuthError(
        `Login as ${role} failed with HTTP ${response.status()} — check the seeded credentials`,
      )
    }
    return context.storageState()
  }

  async logout(context: BrowserContext): Promise<void> {
    if (this.options.logoutPath) {
      await context.request.post(join(this.options.apiBaseUrl, this.options.logoutPath), {
        failOnStatusCode: false,
      })
    }
    await context.clearCookies()
  }
}

export interface UiLoginStrategyOptions {
  routing: Routing
  loginRoute: string
  credentials: CredentialsResolver
  /** Fill and submit the form. */
  submit: (page: Page, credentials: Credentials, role: string) => Promise<void>
  /** Resolve once the post-login landing has settled. */
  settled: (page: Page, role: string) => Promise<void>
  logout?: (page: Page) => Promise<void>
  priming?: (role?: string) => Promise<Cookie[]>
}

/** Drives the real login form. Slow; meant for the auth suite, where the form is the subject. */
export class UiLoginStrategy implements AuthStrategy {
  constructor(private readonly options: UiLoginStrategyOptions) {}

  async priming(role?: string): Promise<Cookie[]> {
    return (await this.options.priming?.(role)) ?? []
  }

  async establish(role: string, context: BrowserContext): Promise<StorageState> {
    const page = await context.newPage()
    try {
      await page.goto(this.options.routing.toUrl(this.options.loginRoute))
      await this.options.submit(page, await this.options.credentials(role), role)
      await this.options.settled(page, role)
      return await context.storageState()
    } finally {
      await page.close()
    }
  }

  async logout(context: BrowserContext): Promise<void> {
    if (this.options.logout) {
      const page = context.pages()[0] ?? (await context.newPage())
      await this.options.logout(page)
    }
    await context.clearCookies()
  }
}

export interface CookieSessionStrategyOptions {
  /** Mint the session cookies for a role, e.g. from a test-only token endpoint or a signed value. */
  cookies: (role: string) => Promise<Cookie[]>
  priming?: (role?: string) => Promise<Cookie[]>
}

/**
 * Injects session cookies directly. For apps that can mint a session without a login round trip.
 * Remember that any JSON cookie value must keep large ids quoted, or JSON.parse loses precision.
 */
export class CookieSessionStrategy implements AuthStrategy {
  constructor(private readonly options: CookieSessionStrategyOptions) {}

  async priming(role?: string): Promise<Cookie[]> {
    return (await this.options.priming?.(role)) ?? []
  }

  async establish(role: string, context: BrowserContext): Promise<StorageState> {
    await context.addCookies(await this.options.cookies(role))
    return context.storageState()
  }

  async logout(context: BrowserContext): Promise<void> {
    await context.clearCookies()
  }
}

/** Wrap a strategy with extra priming cookies (e.g. a fingerprint) without subclassing it. */
export function withPriming(
  strategy: AuthStrategy,
  extra: (role?: string) => Promise<Cookie[]>,
): AuthStrategy {
  return {
    establish: (role, context) => strategy.establish(role, context),
    logout: (context) => strategy.logout(context),
    priming: async (role) => [...((await strategy.priming?.(role)) ?? []), ...(await extra(role))],
  }
}
