import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { R } from '../../src/routes.js'
import { t } from '../../src/i18n.js'
import {
  AdminRequiredScreen,
  AvailabilityScreen,
  LoginScreen,
  logOutViaUserMenu,
  ParentDashboardScreen,
} from '../../src/screens/index.js'
import { waitForSettledRoute } from 'pitchside-core'

test.describe('A · Auth & session — guards and logout', () => {
  test(
    'logging out lands on login without the expired banner, and the app stays closed',
    scenario({
      id: 'AUTH-06',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@auth'],
    }),
    async ({ actor, suite, provision }) => {
      // A fresh parent: logging out revokes the session, which a shared persona's later tests reuse.
      const parent = await provision.parent()
      const { page } = await actor(asRole(parent))
      await new ParentDashboardScreen(page, suite).open()
      await logOutViaUserMenu(page)

      // A clean logout is not an expiry (the false "session expired" banner was a real bug once).
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.expiredBanner()).toHaveCount(0)
      // No bounce back in: the app routes now send this browser to login.
      await page.goto(suite.routing.toUrl(R.parentDashboard))
      expect(await waitForSettledRoute(page, suite.routing, R.login)).toBe(R.login)
    },
  )

  test(
    'a deep link while logged out goes to login with ?redirect= and returns there after login',
    scenario({
      id: 'AUTH-07',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@guest', '@parent', '@auth'],
    }),
    async ({ page, suite, baseline, settledRoute }) => {
      const parent = await baseline('parent')
      await page.goto(suite.routing.toUrl(R.parentBookings))
      const login = await new LoginScreen(page, suite).waitReady()
      expect(new URL(page.url().replace('#/', '')).searchParams.get('redirect')).toBe(
        R.parentBookings,
      )
      await login.logIn(parent.email, parent.password)
      expect(await settledRoute(R.parentBookings)).toBe(R.parentBookings)
    },
  )

  test(
    'a parent opening a coach-only route is sent to the parent home, not shown a 401',
    scenario({
      id: 'AUTH-11',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@auth'],
    }),
    async ({ actor, suite }) => {
      const { page } = await actor('parent')
      await page.goto(suite.routing.toUrl(R.coachBuilder))
      await new ParentDashboardScreen(page, suite).waitReady()
      await expect(page.getByText('security.unauthorized')).toHaveCount(0)
    },
  )

  test(
    'an incomplete coach is held at the builder from the command centre (direct URL, in-app, back and forward), and set-up pages stay open',
    scenario({
      id: 'AUTH-13',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@auth'],
    }),
    async ({ actor, suite }) => {
      const { page } = await actor('coach-incomplete')
      const settled = (matcher: string | ((route: string) => boolean)) =>
        waitForSettledRoute(page, suite.routing, matcher)
      // Direct URL, on a fresh load.
      await page.goto(suite.routing.toUrl(R.commandCentre))
      expect(await settled(R.coachBuilder)).toBe(R.coachBuilder)
      // Then from inside the app (the profile page), so going back has somewhere in-app to land.
      await page.goto(suite.routing.toUrl(R.profile))
      expect(await settled(R.profile)).toBe(R.profile)
      // In-app navigation (a hash change in the loaded app, as a link does): the guard turns the
      // command centre into the builder. (page.goto to a hash-only URL is not a reliable stand-in.)
      await page.evaluate((route) => {
        location.hash = `#${route}`
      }, R.commandCentre)
      expect(await settled(R.coachBuilder)).toBe(R.coachBuilder)
      // Back: the history entry the coach returns to is never the command centre.
      await page.goBack()
      expect(await settled((route) => route !== R.commandCentre)).not.toBe(R.commandCentre)
      // And forward again re-runs the guard.
      await page.goForward()
      expect(await settled((route) => route !== R.commandCentre)).not.toBe(R.commandCentre)
      // The gate covers the command centre only, by decision (2026-11-04): set-up pages such as
      // availability stay open so a coach can prepare while finishing the profile. An unpublished
      // coach cannot be booked, so nothing reachable here is live. A change to this is deliberate.
      await page.goto(suite.routing.toUrl(R.availability))
      expect(await settled(R.availability)).toBe(R.availability)
      // This coach never started the builder, so has no profile to hold windows yet: the page
      // says so and points there, rather than offering a panel whose saves would all fail.
      const availability = new AvailabilityScreen(page, suite)
      await expect(availability.noProfile()).toBeVisible()
      await expect(availability.noProfile()).toContainText(t('booking.availability.profileFirst'))
      await availability.openBuilder().click()
      expect(await settled(R.coachBuilder)).toBe(R.coachBuilder)
    },
  )

  // C-11 / C-13: finance, GDPR and config are meta.fullAdmin. The router sends a limited admin to
  // the requires-administrator page before the page (or any of its admin-only calls) loads.
  for (const [page, route] of [
    ['finance', R.adminFinance],
    ['GDPR', R.adminGdpr],
    ['config', R.adminConfig],
  ] as const) {
    test(
      `a limited admin opening the ${page} page lands on "requires administrator"`,
      scenario({
        id: 'AUTH-20',
        priority: 'P0',
        type: 'neg',
        tags: ['@regression', '@ltd-admin', '@auth', '@admin-console'],
      }),
      async ({ actor, suite, provision }) => {
        const admin = await provision.limitedAdmin()
        const { page: browser } = await actor(asRole(admin))
        await browser.goto(suite.routing.toUrl(route))
        expect(
          await waitForSettledRoute(browser, suite.routing, R.adminRequiresAdministrator),
        ).toBe(R.adminRequiresAdministrator)
        const required = await new AdminRequiredScreen(browser, suite).waitReady()
        await expect(required.message()).toBeVisible()
        await expect(required.back()).toBeVisible()
      },
    )
  }
})
