import { expect, scenario, test } from '../../src/app.js'
import { VERIFY_TOKEN } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { R, REGISTRATION } from '../../src/routes.js'
import {
  EmailPendingScreen,
  LoginScreen,
  PlayerBuilderScreen,
  RegisterScreen,
  scrollToEnd,
} from '../../src/screens/index.js'

test.describe('B · Registration & verification', () => {
  test(
    'a coach registers, verifies by email, logs in and is held at the profile builder',
    scenario({
      id: 'REG-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@registration', '@onboarding'],
    }),
    async ({ page, suite, mail, provision, settledRoute }) => {
      // The spine of the coach funnel. The phone-OTP step is off in the seeded config
      // (REG-21 covers it on); with it off, verification hands straight over to login.
      test.slow()
      const who = provision.identity('coach')
      const register = await new RegisterScreen(page, suite, 'coach').open()
      await register.fillDetails(who)
      await register.tos().click()
      await register.privacy().click()
      const since = new Date(Date.now() - 2_000)
      await register.submit().click()

      const pending = await new EmailPendingScreen(page, suite, 'coach').waitReady()
      await expect(pending.body()).toContainText(who.email)

      const message = await mail.waitForMessage({
        to: who.email,
        subjectMatches: /verify/i,
        since,
        timeoutMs: 30_000,
      })
      const token = message.code(VERIFY_TOKEN)
      await page.goto(suite.routing.toUrl(`${REGISTRATION.coach.verifyEmail}?token=${token}`))
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.verifiedBanner()).toHaveText(t('auth.emailVerifiedPleaseLogin'))

      // A brand-new coach has an incomplete profile: the command-centre guard holds them at
      // the builder (C-11).
      await login.logIn(who.email, who.password)
      expect(await settledRoute(R.coachBuilder)).toBe(R.coachBuilder)
    },
  )

  test(
    'an adult player registers, verifies by email, logs in and finishes their profile',
    scenario({
      id: 'REG-03',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@player', '@registration'],
    }),
    async ({ page, suite, mail, provision, clock, settledRoute }) => {
      // A whole journey: provisioning, mail round trips and several logins.
      test.slow()
      const who = provision.identity('player')
      const register = await new RegisterScreen(page, suite, 'player').open()
      // The adult flow is the lighter one: two checkboxes, neither scroll-gated.
      await expect(register.tos()).toBeEnabled()
      await expect(register.privacy()).toBeEnabled()
      await register.fillDetails({ ...who, dateOfBirth: clock.dateOfBirthForAge(25).toISODate() })
      await register.tos().click()
      await register.privacy().click()
      const since = new Date(Date.now() - 2_000)
      await register.submit().click()

      const pending = await new EmailPendingScreen(page, suite, 'player').waitReady()
      await expect(pending.body()).toContainText(who.email)

      const message = await mail.waitForMessage({
        to: who.email,
        subjectMatches: /verify/i,
        since,
        timeoutMs: 30_000,
      })
      const token = message.code(VERIFY_TOKEN)
      await page.goto(suite.routing.toUrl(`${REGISTRATION.player.verifyEmail}?token=${token}`))
      // The verify page submits on its own and hands over to the login page.
      expect(await settledRoute(R.login)).toBe(R.login)
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.verifiedBanner()).toHaveText(t('auth.emailVerifiedPleaseLogin'))

      await login.logIn(who.email, who.password)
      const builder = await new PlayerBuilderScreen(page, suite).waitReady()
      await expect(builder.submit()).toBeDisabled()
      await builder.choosePosition(t('auth.player.positionMidfielder'))
      await builder.submit().click()
      expect(await settledRoute(R.lockerRoom)).toMatch(/^\/player\/locker-room\/\d+$/)
    },
  )

  test(
    'parent consent: each box enables only after its policy is scrolled, and all three gate submit',
    scenario({
      id: 'REG-04',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@guest', '@registration'],
    }),
    async ({ page, suite, provision }) => {
      const register = await new RegisterScreen(page, suite, 'parent').open()
      await register.fillDetails(provision.identity('parent'))
      for (const box of [register.tos(), register.privacy(), register.parentConsent()]) {
        await expect(box).toBeDisabled()
      }
      await expect(register.submit()).toBeDisabled()

      await scrollToEnd(register.tosScrollBox())
      await scrollToEnd(register.privacyScrollBox())
      await register.tos().click()
      await register.privacy().click()
      // Two of three read and accepted: the unread parental-consent box keeps submit shut.
      await expect(register.parentConsent()).toBeDisabled()
      await expect(register.submit()).toBeDisabled()

      await scrollToEnd(register.parentConsentScrollBox())
      await register.parentConsent().click()
      await expect(register.submit()).toBeEnabled()
    },
  )
})
