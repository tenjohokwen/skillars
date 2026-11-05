import { randomBytes } from 'node:crypto'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { t } from '../../src/i18n.js'
import { PlayerSubscriptionScreen } from '../../src/screens/index.js'
import { stubStripeCardEntry } from '../../src/stubs/stripe.js'

// H · Player plans (ATHLETE free, SEMI_PRO and PRO yearly). A separate system from coach plans: the
// payer is the parent for a child, or a self-registered adult player for themselves. The stack's
// WireMock plays Stripe's subscription API; billing states Stripe would report by webhook are seeded.

test.describe('H · Player subscription', () => {
  test(
    'a parent subscribes a child to a paid plan with the card on file',
    scenario({
      id: 'SUB-05',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      await provision.subscriptionPrices()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const playerId = parent.children[0]!.playerId

      const family = await actor(asRole(parent))
      const plans = await new PlayerSubscriptionScreen(family.page, suite).open({ playerId })
      await expect(plans.currentTier()).toHaveText('ATHLETE')
      await expect(plans.tierCard('ATHLETE')).toContainText(t('subscription.freePlan'))
      await plans.choose('SEMI_PRO').click()
      // Semi-Pro is sold yearly only, and the card on file pays it.
      await expect(plans.interval()).toContainText('YEARLY')
      await expect(plans.addCardPrompt()).toHaveCount(0)
      await plans.confirm().click()

      await expect(
        family.page.getByText(t('subscription.player.subscribeSuccess', { tier: 'SEMI_PRO' })),
      ).toBeVisible()
      await expect(plans.currentTier()).toHaveText('SEMI_PRO')
      await expect(plans.status()).toHaveText('ACTIVE')
      await expect(plans.cancel('SEMI_PRO')).toBeVisible()
    },
  )

  test(
    "a parent moves a child's paid plan to another tier on the same subscription",
    scenario({
      id: 'SUB-06',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const playerId = parent.children[0]!.playerId
      await provision.subscribePlayer(parent, playerId, 'SEMI_PRO')

      const family = await actor(asRole(parent))
      const plans = await new PlayerSubscriptionScreen(family.page, suite).open({ playerId })
      await expect(plans.currentTier()).toHaveText('SEMI_PRO')
      await expect(plans.choose('PRO')).toHaveText(t('subscription.upgrade'))
      await plans.choose('PRO').click()
      // A change keeps the subscription's billing interval, so none is offered.
      await expect(plans.interval()).toHaveCount(0)
      await plans.confirm().click()

      await expect(plans.currentTier()).toHaveText('PRO')
      await expect(family.page.getByText(t('subscription.errors.alreadyActive'))).toHaveCount(0)
      // No paid downgrade is offered: the page could neither show it pending nor undo it.
      await expect(plans.choose('SEMI_PRO')).toHaveCount(0)
    },
  )

  test(
    "a parent cancels a child's plan, which runs to the end of the paid period",
    scenario({
      id: 'SUB-07',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const playerId = parent.children[0]!.playerId
      await provision.subscribePlayer(parent, playerId, 'SEMI_PRO')

      const family = await actor(asRole(parent))
      const plans = await new PlayerSubscriptionScreen(family.page, suite).open({ playerId })
      const periodEnd = (await plans.periodEnd().textContent())!.trim()
      await plans.cancel('SEMI_PRO').click()

      await expect(plans.cancelPending()).toHaveText(
        t('subscription.player.cancelPending', { date: periodEnd }),
      )
      await expect(plans.currentTier()).toHaveText('SEMI_PRO')
      await expect(plans.cancel('SEMI_PRO')).toHaveCount(0)
      // Nor can a plan that is ending be upgraded: that would charge for an upgrade that still ends.
      await expect(plans.choose('PRO')).toHaveCount(0)
    },
  )

  test(
    'a self-registered player manages their own plan: no card blocks it, then they change tier and cancel',
    scenario({
      id: 'SUB-08',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const playerId = player.playerId!

      const self = await actor(asRole(player))
      const plans = await new PlayerSubscriptionScreen(self.page, suite).open({ playerId })
      await plans.choose('SEMI_PRO').click()
      await expect(plans.addCardPrompt()).toContainText(t('payment.card.addCardPrompt'))
      await expect(plans.confirm()).toBeDisabled()

      await provision.saveCard(player)
      await provision.subscribePlayer(player, playerId, 'SEMI_PRO')
      await self.page.reload()
      await plans.waitReady()
      await expect(plans.currentTier()).toHaveText('SEMI_PRO')
      await plans.choose('PRO').click()
      await plans.confirm().click()
      await expect(plans.currentTier()).toHaveText('PRO')

      await plans.cancel('PRO').click()
      await expect(plans.cancelPending()).toBeVisible()
    },
  )

  test(
    'a paused plan says why, and a new card sets the payment retry going',
    scenario({
      id: 'SUB-11',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const playerId = parent.children[0]!.playerId
      await provision.subscribePlayer(parent, playerId, 'SEMI_PRO')
      await provision.setSubscriptionStatus({ playerId }, 'PAUSED')

      const family = await actor(asRole(parent))
      await stubStripeCardEntry(family.context, `pm_e2e_${randomBytes(8).toString('hex')}`)
      const plans = await new PlayerSubscriptionScreen(family.page, suite).open({ playerId })
      await expect(plans.paymentIssue()).toContainText(t('subscription.paymentPaused'))

      await plans.updateCard().click()
      // A card is on file, so the dialog offers to replace it.
      await plans.replaceCard().click()
      await expect(plans.subscriptionNote()).toBeVisible()
      await plans.saveCard().click()
      await expect(plans.retryPending()).toHaveText(t('subscription.paymentRetrying'))
    },
  )
})
