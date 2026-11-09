import { interpolate, waitForSettledRoute } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { R } from '../../src/routes.js'
import { LoginScreen, ParentDashboardScreen } from '../../src/screens/index.js'

// A · Auth & session, backfill: where the router sends each role, and what it does with paths that are not
// (or are no longer) routes.

const notFound = 'not-found-page'
/** The route a role lands on (roleRoutes.js); a player's home resolves on to their locker room. */
const lockerRoom = (route: string) => route.startsWith('/player/locker-room/')

test.describe('A · Auth & session — role routing', () => {
  for (const [role, home] of [
    ['coach', R.commandCentre],
    ['parent', R.parentDashboard],
    ['player', lockerRoom],
  ] as const) {
    test(
      `a logged-in ${role} opening /login is sent to their home`,
      scenario({
        id: 'AUTH-10',
        priority: 'P1',
        type: 'edge',
        tags: ['@regression', `@${role}`, '@auth'],
      }),
      async ({ actor, suite }) => {
        test.slow() // the first use of a baseline persona provisions it
        const { page } = await actor(role)
        await page.goto(suite.routing.toUrl(R.login))
        const settled = await waitForSettledRoute(page, suite.routing, home)
        if (typeof home === 'string') expect(settled).toBe(home)
        else expect(home(settled)).toBe(true)
        await expect(page.getByTestId('login-email-input')).toHaveCount(0)
      },
    )
  }

  for (const role of ['parent', 'player'] as const) {
    test(
      `a ${role} is admitted to the dual-role bookings and purchase routes`,
      scenario({
        id: 'AUTH-12',
        priority: 'P1',
        type: 'happy',
        tags: ['@regression', `@${role}`, '@auth'],
      }),
      async ({ actor, suite, baseline }) => {
        test.slow() // the first use of a baseline persona provisions it
        const coach = await baseline('coach')
        const { page } = await actor(role)
        for (const route of [
          R.parentBookings,
          interpolate(R.purchasePack, { coachId: coach.coachId! }),
          interpolate(R.requestBooking, { coachId: coach.coachId! }),
        ]) {
          await page.goto(suite.routing.toUrl(route))
          await page.reload()
          expect(await waitForSettledRoute(page, suite.routing, route)).toBe(route)
          await expect(page.getByTestId(notFound)).toHaveCount(0)
        }
      },
    )
  }

  test(
    'parents, players and admins are turned away from coach-only routes',
    scenario({
      id: 'AUTH-21',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@player', '@admin', '@auth'],
    }),
    async ({ actor, suite }) => {
      test.slow() // the first use of a baseline persona provisions it
      const coachOnly = [R.revenue, interpolate(R.coachReceipt, { bookingId: '1' })]
      for (const [role, home] of [
        ['parent', R.parentDashboard],
        ['player', lockerRoom],
        ['admin', R.adminHealth],
      ] as const) {
        const { page } = await actor(role)
        for (const route of coachOnly) {
          await page.goto(suite.routing.toUrl(route))
          await page.reload()
          // Wait for the role's home itself: a player passes through /player/home on the way to the locker room.
          const settled = await waitForSettledRoute(page, suite.routing, home)
          if (typeof home === 'string') expect(settled, `${role} → ${route}`).toBe(home)
          else expect(home(settled), `${role} → ${route}`).toBe(true)
        }
      }
    },
  )
})

test.describe('A · Auth & session — paths that are not routes', () => {
  test(
    'an unknown path renders the 404 page, not a blank shell',
    scenario({
      id: 'AUTH-14',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@guest', '@auth'],
    }),
    async ({ page, suite }) => {
      await page.goto(suite.routing.toUrl('/no-such-page/at-all'))
      await expect(page.getByTestId(notFound)).toBeVisible()
      await expect(page.getByTestId(notFound)).toContainText('404')
    },
  )

  test(
    'a ?redirect= to another origin or to a non-route lands on the role home after login, without looping',
    scenario({
      id: 'AUTH-15',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@guest', '@parent', '@auth'],
    }),
    async ({ page, suite, provision }) => {
      const parent = await provision.parent({ childAges: [] })
      for (const redirect of ['https://evil.example.com/', '//evil.example.com', '/no-such-page']) {
        await page.goto(suite.routing.toUrl(`${R.login}?redirect=${encodeURIComponent(redirect)}`))
        await page.reload()
        const login = await new LoginScreen(page, suite).waitReady()
        await login.logIn(parent.email, parent.password)
        expect(await waitForSettledRoute(page, suite.routing, R.parentDashboard)).toBe(
          R.parentDashboard,
        )
        expect(new URL(page.url()).origin).toBe(new URL(suite.baseUrl).origin)
        await expect(page.getByTestId(notFound)).toHaveCount(0)
        // Log out again for the next case: drop the session cookies.
        await page.context().clearCookies()
      }
    },
  )

  test(
    'an old /parent/approvals link lands on the parent dashboard, not the 404 page',
    scenario({
      id: 'AUTH-22',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@parent', '@auth'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      const { page } = await actor(asRole(parent))
      await page.goto(suite.routing.toUrl(R.retiredApprovals))
      await new ParentDashboardScreen(page, suite).waitReady()
      await expect(page.getByTestId(notFound)).toHaveCount(0)
    },
  )

  test(
    'the retired /otp login renders the 404 page, and the profile has no 2FA toggle',
    scenario({
      id: 'AUTH-23',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@guest', '@player', '@auth'],
    }),
    async ({ page, actor, suite }) => {
      test.slow() // the first use of a baseline persona provisions it
      await page.goto(suite.routing.toUrl('/otp'))
      await expect(page.getByTestId(notFound)).toBeVisible()

      const player = await actor('player')
      await player.page.goto(suite.routing.toUrl(R.profile))
      await expect(player.page.getByTestId('profile-privacy')).toBeVisible()
      await expect(player.page.getByText(/two[- ]factor|2fa/i)).toHaveCount(0)
      await expect(player.page.locator('[data-testid*="2fa"], [data-testid*="otp"]')).toHaveCount(0)
    },
  )
})
