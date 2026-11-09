import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { PlayerAccount } from '../../src/data/provisioner.js'
import { E2E } from '../../src/env.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import {
  CoachBuilderScreen,
  ForgotPasswordScreen,
  LoginScreen,
  PlayerDashboardScreen,
  ResetPasswordScreen,
} from '../../src/screens/index.js'
import type { MailSink, SuiteEnv } from 'pitchside-core'

// A · Auth & session, backfill: the account states login tells apart, lockout, reset-link reuse, a session kept
// alive past its warning, and a reset that ends the account's other sessions.

const RESET_KEY = /reset-password\?key=([^"'\s<&]+)/

/** Ask for a reset by the forgot-password page and return the emailed key. */
async function requestResetKey(
  page: Page,
  suite: SuiteEnv,
  mail: MailSink,
  player: PlayerAccount,
): Promise<string> {
  const since = new Date(Date.now() - 2_000)
  const forgot = await new ForgotPasswordScreen(page, suite).open()
  await forgot.request(player.email, player.dateOfBirth)
  // The request sends the mail before it answers: allow for a slow mail transport.
  await expect(forgot.success()).toBeVisible({ timeout: 30_000 })
  const message = await mail.waitForMessage({
    to: player.email,
    subjectMatches: /password reset/i,
    since,
    timeoutMs: 30_000,
  })
  return message.code(RESET_KEY)
}

test.describe('A · Auth & session — account states and session lifetime', () => {
  test(
    'an unverified account sees the "not verified" banner, not the bad-credentials error',
    scenario({
      id: 'AUTH-05',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@guest', '@auth'],
    }),
    async ({ page, suite, provision }) => {
      const account = await provision.unverified('parent')
      const login = await new LoginScreen(page, suite).open()
      await login.logIn(account.email, account.password)
      await expect(login.notVerifiedBanner()).toBeVisible()
      await expect(login.notVerifiedBanner()).toHaveText(t('auth.accountNotVerified'))
      await expect(login.error()).toHaveCount(0)
      expect(login.currentRoute()).toBe(R.login)
    },
  )

  test(
    'repeated failed logins trip the lockout banner, and then even the right password is held off',
    scenario({
      id: 'AUTH-09',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@auth'],
    }),
    async ({ page, suite, provision, consoleGate }) => {
      // The limiter answers 429; the browser logs each refused request.
      consoleGate.allow('status of 401', 'status of 429')
      // A throwaway account: the limiter is keyed on (email, IP) and stays tripped for 15 minutes.
      const parent = await provision.parent({ childAges: [] })
      const login = await new LoginScreen(page, suite).open()
      let attempts = 0
      while (attempts < 8 && !(await login.lockedBanner().isVisible())) {
        attempts += 1
        await login.logIn(parent.email, `wrong-${attempts}-${E2E.accountPassword}`)
        await expect(login.error().or(login.lockedBanner())).toBeVisible()
      }
      await expect(login.lockedBanner()).toHaveText(t('auth.accountLocked'))
      // Security.login.* allows 5 failures per (email, IP) in 15 minutes.
      expect(attempts).toBeGreaterThan(5)

      await login.logIn(parent.email, parent.password)
      await expect(login.lockedBanner()).toBeVisible()
      expect(login.currentRoute()).toBe(R.login)
    },
  )

  test(
    'a reset link used once is refused the second time, without saying whose account it was',
    scenario({
      id: 'AUTH-17',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@player', '@auth', '@slow'],
    }),
    async ({ page, suite, mail, provision }) => {
      test.slow()
      // A reset revokes every session of the account: a throwaway player.
      const player = await provision.adultPlayer()
      const key = await requestResetKey(page, suite, mail, player)

      await page.goto(suite.routing.toUrl(`${R.resetPassword}?key=${key}`))
      await (
        await new ResetPasswordScreen(page, suite).waitReady()
      ).reset(`${E2E.accountPassword}-2`)
      await new LoginScreen(page, suite).waitReady()

      // The same link again.
      await page.goto(suite.routing.toUrl(`${R.resetPassword}?key=${key}`))
      await page.reload()
      const again = await new ResetPasswordScreen(page, suite).waitReady()
      await again.reset(`${E2E.accountPassword}-3`)
      await expect(again.error()).toBeVisible()
      await expect(again.error()).not.toContainText(player.email)
      await expect(again.error()).not.toContainText(player.firstName)
      // The first new password is still the one that works.
      const login = await new LoginScreen(page, suite).open()
      await login.logIn(player.email, `${E2E.accountPassword}-3`)
      await expect(login.error()).toBeVisible()
    },
  )

  test(
    'a coach idle in the profile builder past the expiry warning continues the session and submits the step',
    scenario({
      id: 'AUTH-18',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@coach', '@auth', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coach = await provision.incompleteCoach()
      const self = await actor(asRole(coach))
      const page = self.page
      const builder = await new CoachBuilderScreen(page, suite).open()
      await builder.displayName().fill(`${coach.firstName} Coaching`)
      await builder.bio().fill('Technical coaching for young players.')
      await builder.city().fill('Testville')
      await builder.district().fill('Centre')
      await builder.chooseLanguages('English')
      await builder.chooseTimezone('Europe/Berlin')

      // The coach goes quiet: no more requests (every authenticated response rewrites the expiry). Then the
      // session ends in four minutes, as the browser sees it: 'rint' is the absolute expiry the server sets
      // (epoch ms), which the app's session monitor reads every 30 s.
      await page.waitForLoadState('networkidle')
      const host = new URL(E2E.baseUrl).hostname
      await self.context.addCookies([
        {
          name: 'rint',
          value: String(Date.now() + 4 * 60_000),
          domain: host,
          path: '/',
        },
      ])
      const warning = page.getByTestId('session-warning-dialog')
      await expect(warning).toBeVisible({ timeout: 45_000 })
      await page.getByTestId('session-continue-btn').click()
      await expect(warning).toBeHidden()

      // The form is intact and its step submits: the coach was never bounced to login.
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(
        t('auth.coach.stepOfTotal', { current: 2, total: 5 }),
      )
      expect(builder.currentRoute()).toBe(R.coachBuilder)
    },
  )

  test(
    "a password reset in one browser ends the account's session in another",
    scenario({
      id: 'AUTH-19',
      priority: 'P1',
      type: 'neg',
      // Up to six minutes of waiting for the database check: nightly, not on every PR.
      tags: ['@nightly', '@player', '@auth', '@slow'],
    }),
    async ({ page, actor, suite, mail, provision, consoleGate }) => {
      test.setTimeout(9 * 60_000)
      // Browser B's first call after the reset is refused; the app logs it on the way to login.
      consoleGate.allow('status of 401', 'status code 401', '[Server Error]')
      const player = await provision.adultPlayer()
      const other = await actor(asRole(player))
      await new PlayerDashboardScreen(other.page, suite).open()

      // Browser A (a guest page) resets the password by email.
      const key = await requestResetKey(page, suite, mail, player)
      await page.goto(suite.routing.toUrl(`${R.resetPassword}?key=${key}`))
      await (
        await new ResetPasswordScreen(page, suite).waitReady()
      ).reset(`${E2E.accountPassword}-2`)
      await new LoginScreen(page, suite).waitReady()

      // Browser B is signed out within the bound the design sets: the access token is checked against the
      // database once every 5 minutes (SecurityConstants.DB_REFRESH_TOKEN_INTERVAL), not on every call.
      await expect
        .poll(
          async () => {
            await other.page.reload()
            await other.page.waitForLoadState('networkidle')
            return suite.routing.fromUrl(other.page.url())
          },
          { timeout: 6 * 60_000, intervals: [30_000] },
        )
        .toBe(R.login)
      await expect(new LoginScreen(other.page, suite).expiredBanner()).toBeVisible()
    },
  )
})
