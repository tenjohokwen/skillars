import type { Locator, Page } from '@playwright/test'
import {
  interpolate,
  waitForSettledRoute,
  type Routing,
  type SettledRouteOptions,
} from '../config/routing.js'
import { tid } from '../selectors/testid.js'

export interface ScreenContext {
  routing: Routing
}

/**
 * Base screen object. Thin by policy: it exposes locators and one or two composite actions, and
 * never asserts — assertions live in specs, so a failure reads as a business statement.
 */
export abstract class Screen {
  /** Route pattern, e.g. `/players/:playerId(\\d+)/packs`. */
  abstract readonly route: string
  /** The element whose visibility means "this screen has settled". */
  abstract readonly readyTestId: string

  constructor(
    protected readonly page: Page,
    protected readonly context: ScreenContext,
  ) {}

  /** Navigate here, wait for the route to settle, then for the ready element. */
  async open(
    params?: Record<string, string | number | bigint>,
    options?: SettledRouteOptions,
  ): Promise<this> {
    const route = interpolate(this.route, params)
    await this.page.goto(this.context.routing.toUrl(route))
    await this.waitReady(options)
    return this
  }

  /** For screens reached by navigation inside the app rather than by URL. */
  async waitReady(options?: SettledRouteOptions): Promise<this> {
    await waitForSettledRoute(this.page, this.context.routing, this.route, options)
    await this.tid(this.readyTestId).waitFor({ state: 'visible', timeout: options?.timeoutMs })
    return this
  }

  /** The current route, mapped back from the browser URL. */
  currentRoute(): string {
    return this.context.routing.fromUrl(this.page.url())
  }

  protected tid(id: string): Locator {
    return tid(this.page, id)
  }

  /** A child element inside a row container, e.g. `within(row, 'accept-btn')`. */
  protected within(container: Locator, id: string): Locator {
    return tid(container, id)
  }
}
