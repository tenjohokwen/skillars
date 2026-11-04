import type { Page } from '@playwright/test'
import type { SuiteEnv } from 'pitchside-core'
import type { CoachAccount, ParentAccount } from '../../src/data/provisioner.js'
import { expect, scenario, test } from '../../src/app.js'
import { t } from '../../src/i18n.js'
import { chargeKeysFor } from '../../src/stubs/wiremock.js'
import {
  CoachPublicProfileScreen,
  LoginScreen,
  PackPurchaseScreen,
  PacksDashboardScreen,
  ParentDashboardScreen,
} from '../../src/screens/index.js'

/** Log a fresh parent in through the form and open the coach's pack purchase page. */
async function openPurchase(
  page: Page,
  suite: SuiteEnv,
  parent: ParentAccount,
  coach: CoachAccount,
): Promise<{ profile: CoachPublicProfileScreen; purchase: PackPurchaseScreen }> {
  const login = await new LoginScreen(page, suite).open()
  await login.logIn(parent.email, parent.password)
  await new ParentDashboardScreen(page, suite).waitReady()
  const profile = await new CoachPublicProfileScreen(page, suite).open({ coachId: coach.coachId! })
  await profile.buySessionsSecondary().click()
  const purchase = await new PackPurchaseScreen(page, suite).waitReady()
  await purchase.packOption().click()
  return { profile, purchase }
}

test.describe('E · Payment method & pack purchase', () => {
  test(
    'a parent with a saved card buys a pack and ends up holding its credits',
    scenario({
      id: 'PAY-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@payment'],
    }),
    async ({ page, suite, provision, baseline }) => {
      // A fresh parent: holding credits changes the coach profile's CTAs, which the baseline
      // parent's specs (BKG-02) depend on. The charge goes to the stack's WireMock
      // (stripe-payment-intents-create.json), which always succeeds.
      test.slow()
      const coach = await baseline('coach')
      const coachName = `${coach.firstName} Coaching`
      const parent = await provision.parent()
      await provision.saveCard(parent)

      const login = await new LoginScreen(page, suite).open()
      await login.logIn(parent.email, parent.password)
      await new ParentDashboardScreen(page, suite).waitReady()

      const profile = await new CoachPublicProfileScreen(page, suite).open({
        coachId: coach.coachId!,
      })
      await profile.buySessionsSecondary().click()
      const purchase = await new PackPurchaseScreen(page, suite).waitReady()
      // The pack option renders only once the page has loaded the tier and the saved card, so the
      // banner check after it is meaningful (before it, the banner is absent because of the spinner).
      await purchase.packOption().click()
      await expect(purchase.addCardBanner()).toHaveCount(0)
      await expect(purchase.confirm()).toBeEnabled()
      await purchase.confirm().click()

      // The page goes back to the profile, where the family now holds credits with this coach.
      await profile.waitReady()
      await expect(profile.credits()).toBeVisible()
      await expect(profile.buySessionsSecondary()).toHaveCount(0)
      await expect(profile.primaryCta()).toHaveText(t('marketplace.bookSession'))

      const packs = await new PacksDashboardScreen(page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      await expect(packs.credits(packs.card(coachName))).toHaveText(
        t('booking.packs.creditsRemainingLabel', { remaining: 5, total: 5 }),
      )
    },
  )

  test(
    'with no card on file, confirm stays disabled behind the add-card banner',
    scenario({
      id: 'PAY-02',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@payment'],
    }),
    async ({ actor, suite, baseline }) => {
      const coach = await baseline('coach')
      const { page } = await actor('parent')
      const profile = await new CoachPublicProfileScreen(page, suite).open({
        coachId: coach.coachId!,
      })
      await profile.buySessionsSecondary().click()
      const purchase = await new PackPurchaseScreen(page, suite).waitReady()
      await purchase.packOption().click()
      await expect(purchase.addCardBanner()).toContainText(t('payment.card.addCardPrompt'))
      await expect(purchase.confirm()).toBeDisabled()
    },
  )

  test(
    'a declined card says so, offers to update the card, and leaves the family without credits',
    scenario({
      id: 'PAY-06',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@payment'],
    }),
    async ({ page, suite, provision, baseline }) => {
      test.slow()
      const coach = await baseline('coach')
      const parent = await provision.parent()
      await provision.saveCard(parent, { declines: true })

      const { purchase } = await openPurchase(page, suite, parent, coach)
      await purchase.confirm().click()

      // The decline is named (payment.cardDeclined → payment.error.cardDeclined), with a way to
      // update the card right there; the generic purchase error is for everything else.
      await expect(purchase.error()).toContainText(t('payment.error.cardDeclined'))
      await expect(purchase.updateCard()).toBeVisible()
      // Still on the purchase page (success goes back to the profile), and no pack was created.
      await expect(purchase.packOption()).toBeVisible()
      const packs = await new PacksDashboardScreen(page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      await expect(packs.empty()).toBeVisible()
      await expect(packs.cards()).toHaveCount(0)
    },
  )

  test(
    'double-clicking confirm buys one pack with one charge',
    scenario({
      id: 'PAY-09',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@payment'],
    }),
    async ({ page, suite, provision, baseline }) => {
      test.slow()
      const coach = await baseline('coach')
      const parent = await provision.parent()
      await provision.saveCard(parent)

      const { profile, purchase } = await openPurchase(page, suite, parent, coach)
      await purchase.confirm().dblclick()
      await profile.waitReady()

      const packs = await new PacksDashboardScreen(page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      await expect(packs.card(`${coach.firstName} Coaching`)).toHaveCount(1)
      await expect(packs.cards()).toHaveCount(1)
      // One Stripe idempotency key: however many requests, Stripe would charge once.
      expect([...(await chargeKeysFor(parent.userId))]).toHaveLength(1)
    },
  )
})
