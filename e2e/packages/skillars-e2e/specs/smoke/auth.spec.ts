import { waitForSettledRoute } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { R } from '../../src/routes.js'
import { CommandCentreScreen, ParentDashboardScreen } from '../../src/screens/index.js'

test.describe('A · Auth & session — smoke', () => {
  test(
    'a coach visiting /login is sent to the command centre',
    scenario({
      id: 'AUTH-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@smoke', '@coach', '@auth'],
    }),
    async ({ actor, suite }) => {
      const { page } = await actor('coach')
      await page.goto(suite.routing.toUrl(R.login))
      await new CommandCentreScreen(page, suite).waitReady()
    },
  )

  test(
    'a parent lands on the dashboard and sees their child',
    scenario({
      id: 'AUTH-02',
      priority: 'P0',
      type: 'happy',
      tags: ['@smoke', '@parent', '@auth'],
    }),
    async ({ actor, suite, baseline }) => {
      const parent = await baseline('parent')
      const { page } = await actor('parent')
      await page.goto(suite.routing.toUrl(R.login))
      const dashboard = await new ParentDashboardScreen(page, suite).waitReady()
      await expect(dashboard.childCards()).toHaveCount(parent.children.length)
    },
  )

  test(
    'an adult player is resolved through /player/home to their own locker room',
    scenario({
      id: 'AUTH-03',
      priority: 'P0',
      type: 'happy',
      tags: ['@smoke', '@player', '@auth'],
    }),
    async ({ actor, suite, baseline }) => {
      const player = await baseline('player')
      const { page } = await actor('player')
      await page.goto(suite.routing.toUrl(R.login))
      // Two hops: /login → /player/home (resolves the id) → /player/locker-room/:id.
      const settled = await waitForSettledRoute(page, suite.routing, R.lockerRoom)
      expect(settled).toBe(`/player/locker-room/${player.playerId}`)
    },
  )

  test(
    'a logged-out deep link goes to login and carries the redirect',
    scenario({
      id: 'AUTH-07',
      priority: 'P0',
      type: 'happy',
      tags: ['@smoke', '@guest', '@auth'],
    }),
    async ({ page, gotoRoute, settledRoute }) => {
      await gotoRoute(R.parentBookings)
      await settledRoute(R.login)
      expect(decodeURIComponent(page.url())).toContain(`redirect=${R.parentBookings}`)
    },
  )

  test(
    'a parent opening a coach-only route is bounced to the parent home',
    scenario({
      id: 'AUTH-11',
      priority: 'P0',
      type: 'neg',
      tags: ['@smoke', '@parent', '@auth'],
    }),
    async ({ actor, suite }) => {
      const { page } = await actor('parent')
      await page.goto(suite.routing.toUrl(R.commandCentre))
      await new ParentDashboardScreen(page, suite).waitReady()
    },
  )

  test(
    'an incomplete coach is held at the profile builder',
    scenario({
      id: 'AUTH-13',
      priority: 'P0',
      type: 'neg',
      tags: ['@smoke', '@coach', '@auth', '@onboarding'],
    }),
    async ({ actor, suite }) => {
      const { page } = await actor('coach-incomplete')
      await page.goto(suite.routing.toUrl(R.commandCentre))
      // The async completeness guard redirects after the first navigation settles.
      expect(await waitForSettledRoute(page, suite.routing, R.coachBuilder)).toBe(R.coachBuilder)
    },
  )
})
