import { E2E } from '../../src/env.js'
import { expect, scenario, test } from '../../src/app.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import { ForgotPasswordScreen, LoginScreen, ResetPasswordScreen } from '../../src/screens/index.js'

const RESET_KEY = /reset-password\?key=([^"'\s<&]+)/

test.describe('A · Auth & session — login form', () => {
  test(
    'a wrong password shows the error and stays on the form',
    scenario({
      id: 'AUTH-04',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@guest', '@auth'],
    }),
    async ({ page, suite, settledRoute, baseline }) => {
      // One failed attempt against this run's own baseline player: well under the (email, IP)
      // lockout of 5 per 15 minutes, and the account is never reused by another run.
      const player = await baseline('player')
      const login = await new LoginScreen(page, suite).open()
      await login.logIn(player.email, `${player.password}-wrong`)

      await expect(login.error()).toBeVisible()
      expect(await settledRoute(R.login)).toBe(R.login)
      expect((await page.context().cookies()).map((c) => c.name)).not.toContain('skp')
    },
  )

  test(
    'an expired session shows the expiry banner on the login page',
    scenario({
      id: 'AUTH-08',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@guest', '@auth'],
    }),
    async ({ page, suite }) => {
      await page.goto(suite.routing.toUrl(`${R.login}?expired=true`))
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.expiredBanner()).toHaveText(t('session.expired'))
    },
  )

  test(
    'forgot password → emailed link → reset → log in with the new password',
    scenario({
      id: 'AUTH-16',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@player', '@auth'],
    }),
    async ({ page, suite, mail, provision, settledRoute }) => {
      // A whole journey: provisioning, mail round trips and several logins.
      test.slow()
      // A reset revokes every session of the account, so never the shared baseline player.
      const player = await provision.adultPlayer()
      // Margin for the mail container's clock; the subject filter keeps out the verification mail.
      const since = new Date(Date.now() - 2_000)

      const forgot = await new ForgotPasswordScreen(page, suite).open()
      await forgot.request(player.email, player.dateOfBirth)
      await expect(forgot.success()).toBeVisible()

      // Provisioning just sent this address a verification mail; match the reset by subject.
      const message = await mail.waitForMessage({
        to: player.email,
        subjectMatches: /password reset/i,
        since,
        timeoutMs: 30_000,
      })
      const key = message.code(RESET_KEY)
      await page.goto(suite.routing.toUrl(`${R.resetPassword}?key=${key}`))
      const newPassword = `${E2E.accountPassword}-2`
      await (await new ResetPasswordScreen(page, suite).waitReady()).reset(newPassword)

      const login = await new LoginScreen(page, suite).waitReady()
      await login.logIn(player.email, newPassword)
      expect(await settledRoute(R.lockerRoom)).toBe(
        R.lockerRoom.replace(':playerId(\\d+)', String(player.playerId)),
      )
    },
  )
})
