import { Screen, waitForSettledRoute, type SettledRouteOptions } from 'pitchside-core'
import type { Locator } from '@playwright/test'

/**
 * A list page that renders either rows or an empty state once loaded. Ready means settled on the
 * route and showing a first row OR the empty state, so an empty list is ready too.
 */
export abstract class ListScreen extends Screen {
  protected abstract rows(): Locator
  abstract empty(): Locator

  override async waitReady(options?: SettledRouteOptions): Promise<this> {
    await waitForSettledRoute(this.page, this.context.routing, this.route, options)
    await this.rows()
      .or(this.empty())
      .first()
      .waitFor({ state: 'visible', timeout: options?.timeoutMs })
    return this
  }
}
