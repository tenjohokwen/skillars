import type { Page } from '@playwright/test'
import type { SuiteEnv } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { VERIFY_TOKEN, type Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { R, REGISTRATION } from '../../src/routes.js'
import {
  CreatePlayerScreen,
  EmailPendingScreen,
  EmailVerifyScreen,
  LoginScreen,
  RegisterScreen,
} from '../../src/screens/index.js'
import type { Clock } from 'pitchside-core'

// B · Registration & verification, backfill: the adult cut-off, contact details in names, the email-pending
// resend, what the verification link does when followed, tampered with or followed twice, and the age tier.

/** Register a self-registered adult player through the page; returns who and when the verification mail left. */
async function registerPlayer(
  page: Page,
  suite: SuiteEnv,
  provision: Provisioner,
  clock: Clock,
): Promise<{ who: ReturnType<Provisioner['identity']>; since: Date }> {
  const who = provision.identity('player')
  const register = await new RegisterScreen(page, suite, 'player').open()
  await register.fillDetails({ ...who, dateOfBirth: clock.dateOfBirthForAge(25).toISODate() })
  await register.tos().click()
  await register.privacy().click()
  const since = new Date(Date.now() - 2_000)
  await register.submit().click()
  await new EmailPendingScreen(page, suite, 'player').waitReady()
  return { who, since }
}

test.describe('B · Registration — what the form accepts', () => {
  test(
    'the adult cut-off falls on the 18th birthday: the day before is a minor, the day after an adult',
    scenario({
      id: 'REG-06',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@guest', '@registration'],
    }),
    async ({ page, suite, provision, clock }) => {
      const register = await new RegisterScreen(page, suite, 'player').open()
      await register.fillDetails(provision.identity('player'))
      // Turns 18 tomorrow: still 17.
      await register.dateOfBirth().fill(clock.yearsAgo(18).plusDays(1).toISODate())
      await expect(register.mustBeAdultBanner()).toBeVisible()
      await expect(register.mustBeAdultBanner()).toContainText(t('auth.player.mustBeAdult'))
      // Turned 18 yesterday.
      await register.dateOfBirth().fill(clock.yearsAgo(18).minusDays(1).toISODate())
      await expect(register.mustBeAdultBanner()).toHaveCount(0)
    },
  )

  test(
    'a phone number, an email address or a link typed into a name raises the contact-details warning',
    scenario({
      id: 'REG-07',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@guest', '@registration'],
    }),
    async ({ page, suite }) => {
      const register = await new RegisterScreen(page, suite, 'player').open()
      const warning = page.getByTestId('register-first-name-contact-warning')
      for (const name of ['Call 0791 112 3456', 'kid@example.com', 'www.example.com']) {
        await register.firstName().fill(name)
        await expect(warning, name).toBeVisible()
        await expect(warning, name).toHaveText(t('auth.player.contactDetailWarning'))
      }
      await register.firstName().fill('Alex')
      await expect(warning).toHaveCount(0)
      await register.lastName().fill('reach me at alex@example.com')
      await expect(page.getByTestId('register-last-name-contact-warning')).toBeVisible()
    },
  )
})

test.describe('B · Registration — the verification email', () => {
  test(
    'Resend on the email-pending page is held for 60 seconds, then offered again',
    scenario({
      id: 'REG-09',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@guest', '@registration'],
    }),
    async ({ page, suite, provision, clock }) => {
      // The countdown is a browser interval: a fake clock moves it without waiting a minute.
      await page.clock.install()
      await registerPlayer(page, suite, provision, clock)
      const pending = new EmailPendingScreen(page, suite, 'player')
      await expect(pending.resend()).toBeEnabled()
      await pending.resend().click()
      await expect(pending.resendSuccess()).toBeVisible()
      await expect(pending.resend()).toBeDisabled()
      await page.clock.runFor('00:58')
      await expect(pending.resend()).toBeDisabled()
      await page.clock.runFor('00:03')
      await expect(pending.resend()).toBeEnabled()
    },
  )

  test(
    'Resend delivers a second verification email, and its link verifies the account',
    scenario({
      id: 'REG-10',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@registration', '@slow'],
    }),
    async ({ page, suite, mail, provision, clock }) => {
      test.slow()
      const { who, since } = await registerPlayer(page, suite, provision, clock)
      const first = await mail.waitForMessage({
        to: who.email,
        subjectMatches: /verify/i,
        since,
        timeoutMs: 30_000,
      })
      const pending = new EmailPendingScreen(page, suite, 'player')
      const resentAt = new Date(Date.now() - 1_000)
      await pending.resend().click()
      await expect(pending.resendSuccess()).toBeVisible()
      const second = await mail.waitForMessage({
        to: who.email,
        subjectMatches: /verify/i,
        since: resentAt,
        timeoutMs: 30_000,
      })
      expect(second.code(VERIFY_TOKEN)).not.toBe(first.code(VERIFY_TOKEN))

      await page.goto(
        suite.routing.toUrl(
          `${REGISTRATION.player.verifyEmail}?token=${second.code(VERIFY_TOKEN)}`,
        ),
      )
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.verifiedBanner()).toHaveText(t('auth.emailVerifiedPleaseLogin'))
    },
  )

  test(
    'the verification link verifies on its own: a spinner, then login, with nothing to press',
    scenario({
      id: 'REG-11',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@registration', '@slow'],
    }),
    async ({ page, suite, mail, provision, clock }) => {
      test.slow()
      const { who, since } = await registerPlayer(page, suite, provision, clock)
      const token = (
        await mail.waitForMessage({
          to: who.email,
          subjectMatches: /verify/i,
          since,
          timeoutMs: 30_000,
        })
      ).code(VERIFY_TOKEN)
      // Hold the verification call for a moment, so the page's waiting state can be seen.
      await page.route('**/verify-email?**', async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 1_500))
        await route.continue()
      })
      await page.goto(suite.routing.toUrl(`${REGISTRATION.player.verifyEmail}?token=${token}`))
      await expect(page.getByTestId('email-verify-pending')).toBeVisible()
      await expect(page.getByRole('button')).toHaveCount(0)
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.verifiedBanner()).toHaveText(t('auth.emailVerifiedPleaseLogin'))
    },
  )

  test(
    'a tampered verification link shows an error with a way forward, not a blank page',
    scenario({
      id: 'REG-12',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@player', '@registration', '@slow'],
    }),
    async ({ page, suite, mail, provision, clock }) => {
      test.slow()
      const { who, since } = await registerPlayer(page, suite, provision, clock)
      const token = (
        await mail.waitForMessage({
          to: who.email,
          subjectMatches: /verify/i,
          since,
          timeoutMs: 30_000,
        })
      ).code(VERIFY_TOKEN)
      // One hex character changed.
      const last = token.slice(-1)
      const tampered = `${token.slice(0, -1)}${last === '0' ? '1' : '0'}`
      await page.goto(suite.routing.toUrl(`${REGISTRATION.player.verifyEmail}?token=${tampered}`))
      const verify = new EmailVerifyScreen(page, suite, 'player')
      await expect(verify.errorTitle()).toBeVisible()
      await expect(verify.error()).toBeVisible()
      await expect(verify.resend()).toBeVisible()

      // The real link still works: the tampered one consumed nothing.
      await page.goto(suite.routing.toUrl(`${REGISTRATION.player.verifyEmail}?token=${token}`))
      await page.reload()
      await expect(new LoginScreen(page, suite).verifiedBanner()).toBeVisible()
    },
  )

  test(
    'following the verification link a second time says the email is verified and offers login',
    scenario({
      id: 'REG-13',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@player', '@registration', '@slow'],
    }),
    async ({ page, suite, mail, provision, clock }) => {
      test.slow()
      const { who, since } = await registerPlayer(page, suite, provision, clock)
      const token = (
        await mail.waitForMessage({
          to: who.email,
          subjectMatches: /verify/i,
          since,
          timeoutMs: 30_000,
        })
      ).code(VERIFY_TOKEN)
      const link = suite.routing.toUrl(`${REGISTRATION.player.verifyEmail}?token=${token}`)
      await page.goto(link)
      await new LoginScreen(page, suite).waitReady()

      // People click the email twice.
      await page.goto(link)
      await page.reload()
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.verifiedBanner()).toHaveText(t('auth.emailVerifiedPleaseLogin'))
      await expect(page.getByTestId('email-verify-error-title')).toHaveCount(0)
      await login.logIn(who.email, who.password)
      await expect.poll(() => login.currentRoute()).not.toBe(R.login)
    },
  )
})

test.describe('B · Registration — adding a child', () => {
  test(
    'the age-tier preview follows the date of birth, and only a minor needs the consent box',
    scenario({
      id: 'REG-20',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@registration'],
    }),
    async ({ actor, suite, provision, clock }) => {
      const parent = await provision.parent({ childAges: [] })
      const { page } = await actor(asRole(parent))
      const create = await new CreatePlayerScreen(page, suite).open()
      for (const [age, tier, minor] of [
        [8, 'player.ageTiers.U10', true],
        [11, 'player.ageTiers.AGE_10_12', true],
        [15, 'player.ageTiers.AGE_13_17', true],
        [19, 'player.ageTiers.ADULT', false],
      ] as const) {
        await create.dateOfBirth().fill(clock.dateOfBirthForAge(age).toISODate())
        await expect(create.ageTier(), `age ${age}`).toHaveText(t(tier))
        await expect(create.consent(), `age ${age}`).toHaveCount(minor ? 1 : 0)
      }
      // Back to a minor: the consent box returns.
      await create.dateOfBirth().fill(clock.dateOfBirthForAge(11).toISODate())
      await expect(create.consent()).toBeVisible()
    },
  )
})
