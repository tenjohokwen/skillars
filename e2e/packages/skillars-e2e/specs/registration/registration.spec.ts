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

  test(
    'a parent registers through all three consent boxes, verifies and logs in',
    scenario({
      id: 'REG-02',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@registration'],
    }),
    async ({ page, suite, mail, provision, settledRoute }) => {
      test.slow()
      const who = provision.identity('parent')
      const register = await new RegisterScreen(page, suite, 'parent').open()
      await register.fillDetails(who)
      await scrollToEnd(register.tosScrollBox())
      await scrollToEnd(register.privacyScrollBox())
      await scrollToEnd(register.parentConsentScrollBox())
      await register.tos().click()
      await register.privacy().click()
      await register.parentConsent().click()
      const since = new Date(Date.now() - 2_000)
      await register.submit().click()

      const pending = await new EmailPendingScreen(page, suite, 'parent').waitReady()
      await expect(pending.body()).toContainText(who.email)
      const message = await mail.waitForMessage({
        to: who.email,
        subjectMatches: /verify/i,
        since,
        timeoutMs: 30_000,
      })
      await page.goto(
        suite.routing.toUrl(
          `${REGISTRATION.parent.verifyEmail}?token=${message.code(VERIFY_TOKEN)}`,
        ),
      )
      const login = await new LoginScreen(page, suite).waitReady()
      await expect(login.verifiedBanner()).toHaveText(t('auth.emailVerifiedPleaseLogin'))
      await login.logIn(who.email, who.password)
      // A verified parent, no child yet, lands on the parent dashboard.
      expect(await settledRoute(R.parentDashboard)).toBe(R.parentDashboard)
    },
  )

  test(
    'a player under 18 sees the age banner, with a link to parent registration, and cannot submit',
    scenario({
      id: 'REG-05',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@guest', '@registration'],
    }),
    async ({ page, suite, provision, clock }) => {
      const register = await new RegisterScreen(page, suite, 'player').open()
      await register.fillDetails({
        ...provision.identity('player'),
        dateOfBirth: clock.dateOfBirthForAge(15).toISODate(),
      })
      await register.tos().click()
      await register.privacy().click()
      await expect(register.mustBeAdultBanner()).toContainText(t('auth.player.mustBeAdult'))
      await expect(register.parentRegistrationLink()).toHaveAttribute('href', /parent-register/)
      await expect(register.submit()).toBeDisabled()
    },
  )

  for (const taken of ['email', 'phone'] as const) {
    test(
      `registering with ${taken === 'email' ? 'an email address' : 'a phone number'} that already has an account looks like a fresh registration`,
      scenario({
        id: 'REG-08',
        priority: 'P0',
        type: 'neg',
        tags: ['@regression', '@guest', '@registration', '@privacy'],
      }),
      async ({ page, suite, mail, provision, clock }) => {
        // Enumeration: the form must not tell a stranger whether an address or number is on the
        // platform. The owner of the existing account is told instead, by email.
        test.slow()
        const existing = await provision.registerAndVerify('player', {
          dateOfBirth: clock.dateOfBirthForAge(30).toISODate(),
        })
        const fresh = provision.identity('player')
        const attempt =
          taken === 'email'
            ? { ...fresh, email: existing.email }
            : { ...fresh, phone: existing.phone }

        const register = await new RegisterScreen(page, suite, 'player').open()
        await register.fillDetails({
          ...attempt,
          dateOfBirth: clock.dateOfBirthForAge(25).toISODate(),
        })
        await register.tos().click()
        await register.privacy().click()
        const since = new Date(Date.now() - 2_000)
        await register.submit().click()

        // The same next step as REG-03, and no error on the form.
        const pending = await new EmailPendingScreen(page, suite, 'player').waitReady()
        await expect(pending.body()).toContainText(attempt.email)
        await expect(register.error()).toBeHidden()

        const notice = await mail.waitForMessage({
          to: existing.email,
          subjectMatches: /duplicate/i,
          since,
          timeoutMs: 30_000,
        })
        expect(notice.links().some((link) => link.includes('/forgot-password'))).toBe(true)
        if (taken === 'phone') {
          // The person on the form is told too, at the address they typed: a number held by
          // someone else (it is not verified at registration) must not leave them waiting.
          const told = await mail.waitForMessage({
            to: attempt.email,
            subjectMatches: /couldn't create/i,
            since,
            timeoutMs: 30_000,
          })
          expect(told.links().some((link) => link.includes('/forgot-password'))).toBe(true)
        }
        if (taken === 'phone') {
          // Nothing was created: no verification mail goes to the (fresh) address on the form. The
          // notices are enqueued in the same transaction a verification mail would be, so it has
          // arrived by now. (Not checked for a taken email: that address is the existing account's,
          // and its own verification mail from provisioning can land late.)
          await expect(
            mail.waitForMessage({
              to: attempt.email,
              subjectMatches: /verify/i,
              since,
              timeoutMs: 3_000,
            }),
          ).rejects.toThrow()
        }
      },
    )
  }
})
