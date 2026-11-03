import { expect, scenario, test } from '../../src/app.js'
import { t } from '../../src/i18n.js'
import {
  CoachPublicProfileScreen,
  LoginScreen,
  PackPurchaseScreen,
  PacksDashboardScreen,
  ParentDashboardScreen,
} from '../../src/screens/index.js'

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
})
