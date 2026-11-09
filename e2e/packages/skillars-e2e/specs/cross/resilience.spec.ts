import type { Page, Route } from '@playwright/test'
import { interpolate } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import {
  BookingRequestScreen,
  MarketplaceScreen,
  CoachPublicProfileScreen,
  PacksDashboardScreen,
  ParentDashboardScreen,
  PlayerDashboardScreen,
} from '../../src/screens/index.js'

// X · Cross-cutting, part 2: how the app copes when the network does not. Failing endpoints are answered 500 in
// the browser; the app logs those as "[Server Error]" (and the browser as a failed resource), which these specs
// provoke on purpose, so they allow exactly those console messages for themselves (consoleGate.allow).

const fail500 = (route: Route) =>
  route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: '{"errorMsg":{"errorKey":"e2e"}}',
  })

/** The error banner a page shows when its main data call failed (LoadErrorBanner). */
const loadError = (page: Page) => page.getByTestId('load-error')
const loadRetry = (page: Page) => page.getByTestId('load-retry')

test.describe('X · Degraded data', () => {
  test(
    "a 500 from one dashboard tile's endpoint degrades that tile only, on both dashboards",
    scenario({
      id: 'X-08',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@player', '@cross-cutting'],
    }),
    async ({ actor, suite, provision, consoleGate }) => {
      consoleGate.allow('[Server Error]', 'status of 500', 'Request failed')
      const parent = await provision.parent()
      const player = await provision.adultPlayer()
      for (const [account, Dashboard, unavailable] of [
        [parent, ParentDashboardScreen, t('auth.parent.tileUnavailable')],
        [player, PlayerDashboardScreen, t('player.dashboard.tileUnavailable')],
      ] as const) {
        const self = await actor(asRole(account))
        const page = self.page
        for (const [failing, tile, other] of [
          ['**/api/bookings/requests', 'upcoming-tile', 'wallet-tile'],
          ['**/api/payment/credits/balance', 'wallet-tile', 'upcoming-tile'],
        ] as const) {
          await page.route(failing, fail500)
          const dashboard = new Dashboard(page, suite)
          await dashboard.open()
          await page.reload()
          await dashboard.waitReady()
          await expect(page.getByTestId(tile).getByTestId('tile-value')).toHaveText(unavailable)
          await expect(page.getByTestId(other).getByTestId('tile-value')).not.toHaveText(
            unavailable,
          )
          await page.unroute(failing)
        }
      }
    },
  )

  test(
    'a 500 on the main data call shows an error with Retry, never an endless spinner or a false empty state',
    scenario({
      id: 'X-09',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@cross-cutting', '@slow'],
    }),
    async ({ actor, suite, provision, consoleGate }) => {
      test.slow()
      consoleGate.allow('[Server Error]', 'status of 500', 'Request failed')
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      await provision.confirmedBooking(parent, coach)
      const child = parent.children[0]!.playerId
      const family = await actor(asRole(parent))
      const coachSide = await actor(asRole(coach))

      const pages: {
        name: string
        page: Page
        route: string
        failing: string
        loaded: string
        loadedText?: RegExp
      }[] = [
        {
          name: 'parent bookings',
          page: family.page,
          route: R.parentBookings,
          failing: '**/api/bookings/requests',
          loaded: 'parent-booking',
        },
        {
          name: 'session packs',
          page: family.page,
          route: interpolate(R.childPacks, { playerId: child }),
          failing: '**/api/payment/session-packs**',
          loaded: 'pack-card',
        },
        {
          name: 'credit wallet',
          page: family.page,
          route: R.wallet,
          failing: '**/api/payment/credits/balance',
          loaded: 'wallet-balance',
          loadedText: /€/,
        },
        {
          name: 'notifications',
          page: family.page,
          route: R.notifications,
          failing: '**/api/notifications?**',
          loaded: 'page-list',
        },
        {
          name: 'coach booking requests',
          page: coachSide.page,
          route: R.bookingRequests,
          failing: '**/api/bookings/requests/coach',
          loaded: 'coach-requests-empty',
        },
      ]
      for (const target of pages) {
        await target.page.route(target.failing, fail500)
        await target.page.goto(suite.routing.toUrl(target.route))
        await target.page.reload()
        await expect(loadError(target.page), target.name).toBeVisible({ timeout: 15_000 })
        await expect(target.page.locator('.q-spinner'), target.name).toHaveCount(0)
        const loaded = target.loadedText
          ? target.page.getByTestId(target.loaded).filter({ hasText: target.loadedText })
          : target.page.getByTestId(target.loaded)
        await expect(loaded, target.name).toHaveCount(0)

        // Retry, once the endpoint answers again: the page loads.
        await target.page.unroute(target.failing)
        await loadRetry(target.page).click()
        await expect(loaded.first(), target.name).toBeVisible()
        await expect(loadError(target.page), target.name).toHaveCount(0)
      }
    },
  )
})

test.describe('X · Network and navigation', () => {
  test(
    'going offline mid-journey shows a recoverable error; back online, Retry loads the page',
    scenario({
      id: 'X-10',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@cross-cutting'],
    }),
    async ({ actor, suite, provision, consoleGate }) => {
      consoleGate.allow(
        'Network Error',
        'ERR_INTERNET_DISCONNECTED',
        '[Server Error]',
        'Request failed',
        'dynamically imported module',
      )
      const parent = await provision.parent()
      const family = await actor(asRole(parent))
      const page = family.page
      await new ParentDashboardScreen(page, suite).open()

      await family.context.setOffline(true)
      // An in-app navigation to a page whose code is not loaded yet: it cannot be fetched, and the app says so.
      await page.evaluate((hash) => {
        window.location.hash = hash
      }, `#${R.wallet}`)
      await expect(page.getByTestId('navigation-error')).toBeVisible({ timeout: 15_000 })
      await expect(page.getByTestId('navigation-error')).toContainText(t('common.pageLoadFailed'))

      // Back online, Retry opens the page it could not.
      await family.context.setOffline(false)
      await page.getByTestId('navigation-retry').click()
      await expect(page.getByTestId('wallet-balance')).toContainText('€')
    },
  )

  test(
    'on a slow network a double click on Submit sends one booking request',
    scenario({
      id: 'X-11',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@coach', '@cross-cutting', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coach = await provision.coach({ displayName: `${provision.uniqueCity()} Coaching` })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)
      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(),
      )
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(family.page, suite).waitReady()
      await expect(
        request.packOption(
          t('booking.requests.packOptionLabel', { remaining: sessions, total: sessions }),
        ),
      ).toBeChecked()

      // Every booking POST takes three seconds to answer.
      let posts = 0
      await family.page.route('**/api/bookings/requests', async (route) => {
        if (route.request().method() === 'POST') {
          posts += 1
          await new Promise((resolve) => setTimeout(resolve, 3_000))
        }
        await route.continue()
      })
      await request.submit().dblclick()
      await expect.poll(() => provision.bookingCount(parent), { timeout: 20_000 }).toBe(1)
      expect(posts).toBe(1)
    },
  )

  test(
    'Back and Forward move sanely across guarded routes, and a guest is not looped back into a guard',
    scenario({
      id: 'X-12',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@guest', '@cross-cutting'],
    }),
    async ({ actor, page, suite, provision }) => {
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      const child = parent.children[0]!.playerId
      const family = await actor(asRole(parent))
      const fp = family.page
      const routeOf = (p: Page) => suite.routing.fromUrl(p.url())

      await new ParentDashboardScreen(fp, suite).open()
      const packs = interpolate(R.childPacks, { playerId: child })
      await fp.evaluate((hash) => (window.location.hash = hash), `#${packs}`)
      await new PacksDashboardScreen(fp, suite).waitReady()
      await fp.evaluate((hash) => (window.location.hash = hash), `#${R.wallet}`)
      await fp.getByTestId('wallet-balance').waitFor()

      await fp.goBack()
      await expect.poll(() => routeOf(fp)).toBe(packs)
      await expect(fp.getByTestId('pack-card').first()).toBeVisible()
      await fp.goBack()
      await expect.poll(() => routeOf(fp)).toBe(R.parentDashboard)
      await fp.goForward()
      await expect.poll(() => routeOf(fp)).toBe(packs)
      await expect(fp.getByTestId('pack-card').first()).toBeVisible()

      // A guest sent from a guarded route to login: Back leaves the app's guard, it does not bounce forever.
      // Wait for the marketplace to be up, so the in-app hash change is a navigation the router sees.
      await new MarketplaceScreen(page, suite).open()
      await page.evaluate((hash) => (window.location.hash = hash), `#${R.parentDashboard}`)
      await expect.poll(() => routeOf(page)).toBe(R.login)
      await page.goBack()
      await expect.poll(() => routeOf(page)).toBe(R.marketplace)
    },
  )

  test(
    'switching the language keeps the current page and its data',
    scenario({
      id: 'X-04',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@parent', '@cross-cutting', '@i18n'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      const child = parent.children[0]!.playerId
      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({ playerId: child })
      const route = packs.currentRoute()

      await family.page.getByTestId('language-menu-btn').click()
      await family.page.getByTestId('language-option-fr-fr').click()
      expect(packs.currentRoute()).toBe(route)
      await expect(family.page.getByTestId('pack-card').first()).toBeVisible()
      await expect(
        family.page.getByText(t('booking.packs.dashboardTitle', {}, 'fr-FR')),
      ).toBeVisible()

      // Back to English for the rest of this browser's life (the choice is stored).
      await family.page.getByTestId('language-menu-btn').click()
      await family.page.getByTestId('language-option-en-us').click()
      await expect(
        family.page.getByText(t('booking.packs.dashboardTitle', {}, 'en-US')),
      ).toBeVisible()
    },
  )
})
