import type { Page } from '@playwright/test'
import type { SuiteEnv } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { CoachAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import { CoachBuilderScreen, CommandCentreScreen, LoginScreen } from '../../src/screens/index.js'

// C · Coach onboarding, backfill: the wizard's own behaviour (indicator, navigation, validation per step), the
// photo step, the gate once finished, contact details in the profile, and the Stripe banner.

const stepOf = (current: number) => t('auth.coach.stepOfTotal', { current, total: 5 })

/** A 1×1 JPEG, as a file chooser would hand it over. */
const TINY_JPEG = Buffer.from(
  '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=',
  'base64',
)

async function startBuilder(
  page: Page,
  suite: SuiteEnv,
  provision: Provisioner,
): Promise<{ coach: CoachAccount; builder: CoachBuilderScreen }> {
  const coach = await provision.incompleteCoach()
  const login = await new LoginScreen(page, suite).open()
  await login.logIn(coach.email, coach.password)
  return { coach, builder: await new CoachBuilderScreen(page, suite).waitReady() }
}

async function step1(builder: CoachBuilderScreen, displayName: string): Promise<void> {
  await builder.displayName().fill(displayName)
  await builder.bio().fill('Technical coaching for young players.')
  await builder.city().fill('Testville')
  await builder.district().fill('Centre')
  await builder.chooseLanguages('English')
  await builder.chooseTimezone('Europe/Berlin')
  await builder.next().click()
}

async function step2(builder: CoachBuilderScreen): Promise<void> {
  await builder.chooseSpecialties('Dribbling')
  await builder.ageGroup('10-12').click()
  await builder.next().click()
}

async function step3(builder: CoachBuilderScreen): Promise<void> {
  await builder.perSessionPrice().fill('55')
  await builder.addPackRow({ sessionCount: 5, totalPrice: 250, label: 'Starter 5' })
  await builder.next().click()
}

test.describe('C · Coach onboarding — the wizard', () => {
  test(
    'the step indicator and title follow the step the coach is on',
    scenario({
      id: 'ONB-02',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const { builder } = await startBuilder(page, suite, provision)
      const at = async (n: number) => {
        await expect(builder.stepIndicator()).toHaveText(stepOf(n))
        await expect(builder.stepTitle()).toHaveText(t(`auth.coach.step${n}Title`))
      }
      await at(1)
      await step1(builder, 'Indicator Coaching')
      await at(2)
      await step2(builder)
      await at(3)
      await step3(builder)
      await at(4)
      await builder.addWindowRow(t('auth.coach.weekdayMonday'), '17:00', '19:00')
      await builder.next().click()
      await at(5)
    },
  )

  test(
    'going back and forward through the wizard keeps what was entered, availability rows included',
    scenario({
      id: 'ONB-03',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const { builder } = await startBuilder(page, suite, provision)
      await step1(builder, 'Roundtrip Coaching')
      await step2(builder)
      await step3(builder)
      await expect(builder.stepIndicator()).toHaveText(stepOf(4))
      await builder.addWindowRow(t('auth.coach.weekdayWednesday'), '16:30', '18:45')
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(5))

      // Back to step 1 by the rail, then forward through each step.
      await builder.railSteps().nth(0).click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(1))
      await expect(builder.displayName()).toHaveValue('Roundtrip Coaching')
      await expect(builder.city()).toHaveValue('Testville')
      await builder.railSteps().nth(2).click()
      await expect(builder.perSessionPrice()).toHaveValue('55')
      await expect(builder.packRows()).toHaveCount(1)
      await expect(builder.packRows().first().getByTestId('builder-pack-label')).toHaveValue(
        'Starter 5',
      )
      await builder.railSteps().nth(3).click()
      await expect(builder.windowRows()).toHaveCount(1)
      await expect(builder.windowRows().first().getByTestId('builder-window-start')).toHaveValue(
        '16:30',
      )
      await expect(builder.windowRows().first().getByTestId('builder-window-end')).toHaveValue(
        '18:45',
      )
    },
  )

  test(
    'step 1 cannot be left with required fields empty, and each one says so',
    scenario({
      id: 'ONB-05',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@onboarding'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const { builder } = await startBuilder(page, suite, provision)
      // The step has loaded (its zone list included) before Next is pressed.
      await page.waitForLoadState('networkidle')
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(1))
      // One message per empty required field (the display name and the languages), not a single summary.
      const perField = page.locator('.q-field--error')
      await expect(perField).toHaveCount(2)
      await expect(perField.filter({ has: builder.displayName() })).toHaveCount(1)
      await expect(perField.filter({ hasText: t('auth.coach.step1Languages') })).toHaveCount(1)
      await expect(perField.first()).toContainText(t('validation.required'))

      await builder.displayName().fill('Half Filled Coaching')
      await builder.displayName().press('Tab') // the rules re-run when the field is left
      await expect(perField.filter({ has: builder.displayName() })).toHaveCount(0)
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(1))
    },
  )

  test(
    'a pack priced zero or below, or with a fractional session count, is refused',
    scenario({
      id: 'ONB-08',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision, consoleGate }) => {
      test.slow()
      consoleGate.allow(
        'status of 400',
        'status of 422',
        '[Server Error]',
        'status code 400',
        'status code 422',
      )
      const { builder } = await startBuilder(page, suite, provision)
      await step1(builder, 'Bad Pack Coaching')
      await step2(builder)
      await expect(builder.stepIndicator()).toHaveText(stepOf(3))
      await builder.perSessionPrice().fill('55')
      await builder.addPackRow({ sessionCount: 5, totalPrice: 0, label: 'Free 5' })
      const row = builder.packRows().first()
      for (const [sessions, price] of [
        ['5', '0'],
        ['5', '-10'],
        ['2.5', '100'],
      ] as const) {
        await row.getByTestId('builder-pack-sessions').fill(sessions)
        await row.getByTestId('builder-pack-price').fill(price)
        await builder.next().click()
        await expect(
          builder.packError().or(builder.error()).or(page.locator('.q-field--error')).first(),
          `${sessions} sessions for ${price}`,
        ).toBeVisible()
        await expect(builder.stepIndicator()).toHaveText(stepOf(3))
      }
      // A valid pack then goes through.
      await row.getByTestId('builder-pack-sessions').fill('5')
      await row.getByTestId('builder-pack-price').fill('250')
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(4))
    },
  )

  test(
    'step 4 says plainly when no weekly availability window has been added',
    scenario({
      id: 'ONB-09',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const { builder } = await startBuilder(page, suite, provision)
      await step1(builder, 'Empty Week Coaching')
      await step2(builder)
      await step3(builder)
      await expect(builder.stepIndicator()).toHaveText(stepOf(4))
      const empty = page.getByTestId('builder-window-empty')
      await expect(empty).toBeVisible()
      await expect(empty).toHaveText(t('auth.coach.step4WindowEmpty'))
      await builder.addWindowRow(t('auth.coach.weekdayMonday'), '17:00', '19:00')
      await expect(empty).toHaveCount(0)
      await page.getByTestId('builder-window-remove').click()
      await expect(empty).toBeVisible()
    },
  )

  test(
    'an availability window ending before, or when, it starts is refused',
    scenario({
      id: 'ONB-10',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision, consoleGate }) => {
      test.slow()
      consoleGate.allow(
        'status of 400',
        'status of 422',
        '[Server Error]',
        'status code 400',
        'status code 422',
      )
      const { builder } = await startBuilder(page, suite, provision)
      await step1(builder, 'Backwards Week Coaching')
      await step2(builder)
      await step3(builder)
      await expect(builder.stepIndicator()).toHaveText(stepOf(4))
      await builder.addWindowRow(t('auth.coach.weekdayMonday'), '19:00', '17:00')
      const row = builder.windowRows().first()
      for (const [start, end] of [
        ['19:00', '17:00'],
        ['18:00', '18:00'],
      ] as const) {
        await row.getByTestId('builder-window-start').fill(start)
        await row.getByTestId('builder-window-end').fill(end)
        await builder.next().click()
        await expect(
          builder.error().or(page.locator('.q-field--error')).first(),
          `${start}–${end}`,
        ).toBeVisible()
        await expect(builder.stepIndicator()).toHaveText(stepOf(4))
      }
      await row.getByTestId('builder-window-end').fill('20:00')
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(5))
    },
  )
})

test.describe('C · Coach onboarding — the photo, and after', () => {
  test(
    'a photo chosen on step 5 is previewed and uploaded, and the wizard completes',
    scenario({
      id: 'ONB-11',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const { coach, builder } = await startBuilder(page, suite, provision)
      await step1(builder, 'Photo Coaching')
      await step2(builder)
      await step3(builder)
      await builder.addWindowRow(t('auth.coach.weekdayMonday'), '17:00', '19:00')
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(5))

      // Quasar puts the test id on the native file input.
      await builder
        .photoFile()
        .setInputFiles({ name: 'me.jpg', mimeType: 'image/jpeg', buffer: TINY_JPEG })
      await expect(page.getByTestId('builder-photo-preview')).toBeVisible()
      await builder.photoUpload().click()
      await new CommandCentreScreen(page, suite).waitReady()
      // The upload landed: the coach's own profile now carries a photo.
      const session = await provision.login(coach)
      try {
        const profile = await session.api.json<{ photoUrl: string | null }>(
          'GET',
          '/marketplace/coaches/me/profile',
        )
        expect(profile.photoUrl).toBeTruthy()
      } finally {
        await session.dispose()
      }
    },
  )

  test(
    'skipping the photo still completes the wizard, and the gate agrees',
    scenario({
      id: 'ONB-12',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision }) => {
      test.slow()
      const { builder } = await startBuilder(page, suite, provision)
      await step1(builder, 'No Photo Coaching')
      await step2(builder)
      await step3(builder)
      await builder.addWindowRow(t('auth.coach.weekdayFriday'), '17:00', '19:00')
      await builder.next().click()
      await builder.photoSkip().click()
      await new CommandCentreScreen(page, suite).waitReady()
      // The command-centre gate lets them back in on a fresh load.
      await page.reload()
      await new CommandCentreScreen(page, suite).waitReady()
      expect(new CommandCentreScreen(page, suite).currentRoute()).toBe(R.commandCentre)
    },
  )

  test(
    'a finished coach who opens the builder is sent to the command centre',
    scenario({
      id: 'ONB-13',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@coach', '@onboarding'],
    }),
    async ({ actor, suite }) => {
      test.slow() // the first use of the baseline coach provisions it
      const { page } = await actor('coach')
      await page.goto(suite.routing.toUrl(R.coachBuilder))
      await new CommandCentreScreen(page, suite).waitReady()
    },
  )

  test(
    'contact details are refused in the display name and a pack label',
    scenario({
      id: 'ONB-14',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@onboarding', '@slow'],
    }),
    async ({ page, suite, provision, consoleGate }) => {
      test.slow()
      consoleGate.allow(
        'status of 400',
        'status of 422',
        '[Server Error]',
        'status code 400',
        'status code 422',
      )
      const { builder } = await startBuilder(page, suite, provision)
      for (const name of ['Call 0791 112 3456', 'coach@example.com', 'Find me @coachhandle']) {
        await builder.displayName().fill(name)
        await builder.bio().fill('Technical coaching for young players.')
        await builder.city().fill('Testville')
        await builder.district().fill('Centre')
        await builder.chooseLanguages('English')
        await builder.chooseTimezone('Europe/Berlin')
        await builder.next().click()
        await expect(builder.error().or(builder.contactWarning()).first(), name).toBeVisible()
        await expect(builder.stepIndicator(), name).toHaveText(stepOf(1))
        // Reload, so the next case starts on a clean step 1.
        await page.reload()
        await builder.waitReady()
      }

      await step1(builder, 'Clean Name Coaching')
      await step2(builder)
      await builder.perSessionPrice().fill('55')
      await builder.addPackRow({
        sessionCount: 5,
        totalPrice: 250,
        label: 'WhatsApp 0791 112 3456',
      })
      await builder.next().click()
      await expect(builder.packError().or(builder.error()).first()).toBeVisible()
      await expect(builder.stepIndicator()).toHaveText(stepOf(3))
    },
  )

  test(
    'the command centre shows the Stripe-setup banner until Connect onboarding is complete',
    scenario({
      id: 'ONB-15',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@onboarding', '@payment', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coach = await provision.coach({ paymentReady: false })
      const { page } = await actor(asRole(coach))
      const centre = await new CommandCentreScreen(page, suite).open()
      await expect(page.getByTestId('stripe-setup-banner')).toBeVisible()
      await expect(page.getByTestId('stripe-setup-link')).toBeVisible()

      await provision.markCoachPaymentReady(coach)
      await page.reload()
      await centre.waitReady()
      await expect(page.getByTestId('stripe-setup-banner')).toHaveCount(0)
    },
  )
})
