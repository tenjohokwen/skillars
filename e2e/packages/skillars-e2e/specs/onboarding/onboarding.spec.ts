import type { Page } from '@playwright/test'
import type { SuiteEnv } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import type { CoachAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import {
  AvailabilityScreen,
  CoachBuilderScreen,
  CoachPublicProfileScreen,
  CommandCentreScreen,
  LoginScreen,
  PackPurchaseScreen,
} from '../../src/screens/index.js'

/** A fresh verified coach, logged in through the form; the completeness gate lands them on step 1. */
async function startBuilder(
  page: Page,
  suite: SuiteEnv,
  provision: Provisioner,
): Promise<{ coach: CoachAccount; builder: CoachBuilderScreen }> {
  const coach = await provision.incompleteCoach()
  const login = await new LoginScreen(page, suite).open()
  await login.logIn(coach.email, coach.password)
  const builder = await new CoachBuilderScreen(page, suite).waitReady()
  return { coach, builder }
}

/** The coach's marketplace id, read through their own API session. */
async function publishedCoachId(provision: Provisioner, coach: CoachAccount): Promise<string> {
  const session = await provision.login(coach)
  try {
    const status = await session.api.json<{ coachId: string; profileComplete: boolean }>(
      'GET',
      '/marketplace/coaches/me/profile/status',
    )
    expect(status.profileComplete).toBe(true)
    return status.coachId
  } finally {
    await session.dispose()
  }
}

const stepOf = (current: number) => t('auth.coach.stepOfTotal', { current, total: 5 })

async function fillStep1(builder: CoachBuilderScreen, displayName: string): Promise<void> {
  await builder.displayName().fill(displayName)
  await builder.bio().fill('Technical coaching for young players.')
  await builder.city().fill('Testville')
  await builder.district().fill('Centre')
  await builder.chooseLanguages('English')
}

test.describe('C · Coach onboarding', () => {
  test(
    'a coach completes all five steps and lands on the command centre, published',
    scenario({
      id: 'ONB-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@onboarding'],
    }),
    async ({ page, suite, provision, actor }) => {
      test.slow()
      const { coach, builder } = await startBuilder(page, suite, provision)
      const displayName = `${coach.firstName} Coaching`

      await expect(builder.stepIndicator()).toHaveText(stepOf(1))
      await fillStep1(builder, displayName)
      // The browser runs in UTC, so Etc/UTC is preselected; pick the coach's real zone instead.
      await builder.chooseTimezone('Europe/Berlin')
      await builder.next().click()

      await expect(builder.stepIndicator()).toHaveText(stepOf(2))
      await builder.chooseSpecialties('Dribbling', 'Passing')
      await builder.ageGroup('10-12').click()
      await builder.ageGroup('13-17').click()
      await builder.next().click()

      await expect(builder.stepIndicator()).toHaveText(stepOf(3))
      await builder.perSessionPrice().fill('55')
      await builder.addPackRow({ sessionCount: 5, totalPrice: 250, label: 'Starter 5' })
      await builder.next().click()

      await expect(builder.stepIndicator()).toHaveText(stepOf(4))
      // Step 4 shows step 1's zone read-only: every window is stored in it.
      await expect(builder.timezoneReadonly()).toHaveText('Europe/Berlin')
      await builder.addWindowRow(t('auth.coach.weekdayMonday'), '17:00', '19:00')
      await builder.next().click()

      await expect(builder.stepIndicator()).toHaveText(stepOf(5))
      await builder.photoSkip().click()

      await new CommandCentreScreen(page, suite).waitReady()

      const availability = await new AvailabilityScreen(page, suite).open()
      await expect(availability.windows()).toHaveCount(1)
      await expect(availability.windowsOn(0)).toHaveCount(1)

      // Published means marketplace-visible: a parent can open the profile.
      const coachId = await publishedCoachId(provision, coach)
      const { page: parentPage } = await actor('parent')
      await new CoachPublicProfileScreen(parentPage, suite).open({ coachId })
    },
  )

  test(
    'a pack added in the builder can be bought by a parent',
    scenario({
      id: 'ONB-06',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@onboarding', '@payment'],
    }),
    async ({ page, suite, provision, actor }) => {
      test.slow()
      const { coach, builder } = await startBuilder(page, suite, provision)
      await fillStep1(builder, 'Pack Test Coaching')
      await builder.chooseTimezone('Europe/Berlin')
      await builder.next().click()
      await builder.chooseSpecialties('Fitness')
      await builder.ageGroup('10-12').click()
      await builder.ageGroup('13-17').click()
      await builder.next().click()

      await expect(builder.stepIndicator()).toHaveText(stepOf(3))
      await builder.perSessionPrice().fill('40')
      await builder.addPackRow({ sessionCount: 10, totalPrice: 380, label: 'Season 10' })
      await builder.next().click()
      await builder.addWindowRow(t('auth.coach.weekdayTuesday'), '18:00', '20:00')
      await builder.next().click()
      await builder.photoSkip().click()
      await new CommandCentreScreen(page, suite).waitReady()

      // Stripe Connect has no UI path in this tier; seed it so the coach is bookable (C-10).
      const coachId = await publishedCoachId(provision, coach)
      await provision.markCoachPaymentReady({ ...coach, coachId })

      const { page: parentPage } = await actor('parent')
      const profile = await new CoachPublicProfileScreen(parentPage, suite).open({ coachId })
      await expect(profile.buySessionsSecondary()).toBeVisible()
      await profile.buySessionsSecondary().click()
      const purchase = new PackPurchaseScreen(parentPage, suite)
      await expect(purchase.packOption().or(purchase.noPack())).toBeVisible()

      await expect(purchase.packOption()).toContainText(
        t('booking.packs.sessionsBundle', { count: 10 }),
      )
    },
  )

  test(
    'a coach cannot pass the pricing step without a per-session price, pack or no pack',
    scenario({
      id: 'ONB-07',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@onboarding'],
    }),
    async ({ page, suite, provision }) => {
      // Revised plan row: a coach with neither price nor pack can't exist, because step 3 requires
      // the per-session price (perSessionPrice is @NotNull server-side too). So the unbookable coach
      // the old row described is unreachable, and what is left to assert is the gate itself.
      test.slow()
      const { builder } = await startBuilder(page, suite, provision)
      await fillStep1(builder, 'No Price Coaching')
      await builder.chooseTimezone('Europe/Berlin')
      await builder.next().click()
      await builder.chooseSpecialties('Fitness')
      await builder.ageGroup('13-17').click()
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(3))

      const step3Saves: string[] = []
      page.on('request', (r) => {
        if (r.method() === 'PUT' && r.url().endsWith('/steps/3')) step3Saves.push(r.url())
      })
      // The price field's own rule message (Quasar may put the test id on the inner <input>, which
      // holds no text, so match the message itself; it is the only field on the step in error).
      const required = page.getByText(t('validation.required'), { exact: true })
      await expect(required).toBeHidden()
      // No price and no pack: Next stays on step 3, says why, and saves nothing. The price was
      // never touched, so the message comes from Next itself (ledger 2026-11-05, #449).
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(3))
      await expect(required).toBeVisible()
      // Even with a complete pack, the price is still required.
      await builder.addPackRow({ sessionCount: 5, totalPrice: 200, label: 'Five' })
      await builder.perSessionPrice().fill('40')
      await expect(required).toBeHidden()
      await builder.perSessionPrice().fill('')
      await expect(required).toBeVisible()
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(3))
      expect(step3Saves).toEqual([])
    },
  )
})

test.describe('C · Coach onboarding — browser in a zone the server lacks', () => {
  // Not one of the server's zones (its list carries Etc/UTC only), so nothing is preselected.
  test.use({ timezoneId: 'Etc/GMT+5' })

  test(
    'step 1 accepts only a timezone the server knows',
    scenario({
      id: 'ONB-04',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@onboarding', '@tz'],
    }),
    async ({ page, suite, provision }) => {
      // The zone renders every slot every parent sees (C-12), so the picker offers only the
      // server's own list. A browser in a zone the server lacks gets no preselection and a hint.
      const { builder } = await startBuilder(page, suite, provision)
      await fillStep1(builder, 'Timezone Test Coaching')

      await expect(builder.timezoneUnknownHint()).toBeVisible()
      await expect(builder.next()).toBeDisabled()

      await builder.searchTimezone('Mars/Olympus_Mons')
      await expect(builder.timezoneNoOption()).toHaveText(t('auth.coach.timezoneNoMatch'))
      await page.keyboard.press('Escape')
      await expect(builder.next()).toBeDisabled()

      await builder.chooseTimezone('America/Bogota')
      await expect(builder.next()).toBeEnabled()
      await builder.next().click()
      await expect(builder.stepIndicator()).toHaveText(stepOf(2))
    },
  )
})
