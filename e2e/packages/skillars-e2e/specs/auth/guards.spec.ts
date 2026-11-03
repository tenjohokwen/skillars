import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { R } from '../../src/routes.js'
import { LoginScreen, logOutViaUserMenu, ParentDashboardScreen } from '../../src/screens/index.js'
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
    'an incomplete coach is held at the profile builder, by direct URL and by going back',
    scenario({
      id: 'AUTH-13',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@auth'],
    }),
    async ({ actor, suite }) => {
      const { page } = await actor('coach-incomplete')
      await page.goto(suite.routing.toUrl(R.commandCentre))
      expect(await waitForSettledRoute(page, suite.routing, R.coachBuilder)).toBe(R.coachBuilder)
      await page.goBack()
      await page.goto(suite.routing.toUrl(R.commandCentre))
      expect(await waitForSettledRoute(page, suite.routing, R.coachBuilder)).toBe(R.coachBuilder)
    },
  )
})
