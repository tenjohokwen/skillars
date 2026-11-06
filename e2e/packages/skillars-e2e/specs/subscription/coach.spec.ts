import { randomBytes } from 'node:crypto'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { CoachAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { R } from '../../src/routes.js'
import { CoachSubscriptionScreen, ProfileScreen } from '../../src/screens/index.js'
import { stubStripeCardEntry } from '../../src/stubs/stripe.js'

// H · Coach plans (SCOUT free, INSTRUCTOR, ACADEMY). The stack's WireMock plays Stripe's subscription
// API: a subscribe is paid and active at once, a pm_e2e_declined_* card is refused. Billing states
// Stripe would report by webhook are seeded (provision.setSubscriptionStatus). The tier-gated feature
// asserted is report branding on /profile, which only ACADEMY unlocks; the gate reads the payment-side
// status, not just the stored tier (CoachProfileService.getCoachSubscriptionTier).

test.describe('H · Coach subscription', () => {
  test(
    'a coach sees their current plan, set apart from the other tiers, with what each tier offers them',
    scenario({
      id: 'SUB-01',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      await provision.saveCard(coach)
      await provision.subscribeCoach(coach, 'INSTRUCTOR')

      const self = await actor(asRole(coach))
      const plans = await new CoachSubscriptionScreen(self.page, suite).open()
      await expect(plans.currentTier()).toHaveText('INSTRUCTOR')
      await expect(plans.status()).toHaveText('ACTIVE')
      await expect(plans.periodEnd()).toBeVisible()
      await expect(plans.tierCards()).toHaveCount(3)

      // The current tier is the one marked, and the one with Cancel; the others offer a move.
      await expect(plans.tierCard('INSTRUCTOR')).toHaveClass(/current-tier/)
      await expect(plans.tierCard('SCOUT')).not.toHaveClass(/current-tier/)
      await expect(plans.tierCard('ACADEMY')).not.toHaveClass(/current-tier/)
      await expect(plans.cancel('INSTRUCTOR')).toBeVisible()
      await expect(plans.choose('ACADEMY')).toHaveText(t('subscription.upgrade'))
      await expect(plans.tierCard('SCOUT')).toContainText(t('subscription.freePlan'))
      await expect(plans.choose('SCOUT')).toHaveCount(0)
    },
  )

  test(
    'a Scout coach sees report branding locked behind an upgrade, not the controls',
    scenario({
      id: 'SUB-02',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()

      const self = await actor(asRole(coach))
      const profile = await new ProfileScreen(self.page, suite).open()
      await expect(profile.brandingGate()).toBeVisible()
      await expect(profile.branding()).toContainText(
        t('featureGate.requiredTier', { tier: t('featureGate.tier.ACADEMY') }),
      )
      await expect(profile.brandingEdit()).toHaveCount(0)

      // The upgrade leads to the coach's own plans page.
      await profile.brandingUpgrade().click()
      await new CoachSubscriptionScreen(self.page, suite).waitReady()
      await expect(self.page).toHaveURL(suite.routing.toUrl(R.coachSubscription))
    },
  )

  test(
    'a Scout coach who subscribes to Academy gets report branding at once, without signing in again',
    scenario({
      id: 'SUB-03',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      await provision.subscriptionPrices()
      const coach = await provision.coach()
      await provision.saveCard(coach)

      const self = await actor(asRole(coach))
      const profile = await new ProfileScreen(self.page, suite).open()
      await expect(profile.brandingGate()).toBeVisible()
      await profile.brandingUpgrade().click()

      const plans = await new CoachSubscriptionScreen(self.page, suite).waitReady()
      await expect(plans.currentTier()).toHaveText('SCOUT')
      await plans.choose('ACADEMY').click()
      // Scout has no subscription to change: Academy is subscribed to, card on file.
      await expect(plans.addCardPrompt()).toHaveCount(0)
      await plans.confirm().click()
      await expect(
        self.page.getByText(t('subscription.coach.subscribeSuccess', { tier: 'ACADEMY' })),
      ).toBeVisible()
      await expect(plans.currentTier()).toHaveText('ACADEMY')

      // Same session, in-app navigation: the gate re-reads the tier.
      await self.page.goBack()
      await profile.waitReady()
      await expect(profile.brandingGate()).toHaveCount(0)
      await expect(profile.brandingEdit()).toBeVisible()
    },
  )

  test(
    'cancelling is confirmed first, keeps the plan until the end of the paid period, and can be taken back',
    scenario({
      id: 'SUB-04',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      await provision.saveCard(coach)
      await provision.subscribeCoach(coach, 'ACADEMY')

      const self = await actor(asRole(coach))
      const plans = await new CoachSubscriptionScreen(self.page, suite).open()
      await expect(plans.cancelPending()).toHaveCount(0)
      const periodEnd = (await plans.periodEnd().textContent())!.trim()

      // The dialog says what happens and when; its way out changes nothing.
      await plans.cancel('ACADEMY').click()
      await expect(plans.cancelDialog()).toContainText(
        t('subscription.cancelBody', { tier: 'ACADEMY', free: 'SCOUT', date: periodEnd }),
      )
      await plans.dismissCancel().click()
      await expect(plans.cancelDialog()).toBeHidden()
      await expect(plans.cancelPending()).toHaveCount(0)

      await plans.cancel('ACADEMY').click()
      await plans.confirmCancel().click()
      await expect(plans.cancelPending()).toContainText(
        t('subscription.coach.cancelPending', { date: periodEnd }),
      )
      await expect(plans.currentTier()).toHaveText('ACADEMY')
      await expect(plans.cancel('ACADEMY')).toHaveCount(0)

      // Still Academy until then: branding stays unlocked.
      const profile = await new ProfileScreen(self.page, suite).open()
      await expect(profile.brandingGate()).toHaveCount(0)
      await expect(profile.brandingEdit()).toBeVisible()

      // Changed their mind: the plan renews again, and can be cancelled again.
      await plans.open()
      await plans.keepPlan().click()
      await expect(self.page.getByText(t('subscription.keepPlanSuccess'))).toBeVisible()
      await expect(plans.cancelPending()).toHaveCount(0)
      await expect(plans.cancel('ACADEMY')).toBeVisible()
      await self.page.reload()
      await plans.waitReady()
      await expect(plans.cancelPending()).toHaveCount(0)
    },
  )

  test(
    'a downgrade is confirmed, shown as pending until the period ends, and can be taken back',
    scenario({
      id: 'SUB-06',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      await provision.saveCard(coach)
      await provision.subscribeCoach(coach, 'ACADEMY')

      const self = await actor(asRole(coach))
      const plans = await new CoachSubscriptionScreen(self.page, suite).open()
      const periodEnd = (await plans.periodEnd().textContent())!.trim()
      await expect(plans.choose('INSTRUCTOR')).toHaveText(t('subscription.downgrade'))
      // Scout is reached by cancelling, not as a downgrade.
      await expect(plans.choose('SCOUT')).toHaveCount(0)

      await plans.choose('INSTRUCTOR').click()
      await expect(plans.downgradeDialog()).toContainText(
        t('subscription.downgradeBody', {
          tier: 'INSTRUCTOR',
          current: 'ACADEMY',
          date: periodEnd,
        }),
      )
      await plans.confirmDowngrade().click()

      const pending = t('subscription.changePending', {
        tier: 'INSTRUCTOR',
        date: periodEnd,
        current: 'ACADEMY',
      })
      await expect(plans.changePending()).toContainText(pending)
      await expect(plans.currentTier()).toHaveText('ACADEMY')
      await expect(plans.choose('INSTRUCTOR')).toHaveCount(0)
      // Still Academy until then.
      const profile = await new ProfileScreen(self.page, suite).open()
      await expect(profile.brandingEdit()).toBeVisible()

      // The pending change survives a reload, and Keep Academy takes it back.
      await plans.open()
      await expect(plans.changePending()).toContainText(pending)
      await plans.keepTier().click()
      await expect(
        self.page.getByText(t('subscription.keepTierSuccess', { tier: 'ACADEMY' })),
      ).toBeVisible()
      await expect(plans.changePending()).toHaveCount(0)
      await expect(plans.choose('INSTRUCTOR')).toHaveText(t('subscription.downgrade'))
    },
  )

  test(
    'a subscription in its payment grace period says so and keeps the plan; an unpaid one loses it',
    scenario({
      id: 'SUB-09',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      await provision.saveCard(coach)
      await provision.subscribeCoach(coach, 'ACADEMY')
      await provision.setSubscriptionStatus({ coachId: coach.coachId! }, 'PAST_DUE')

      const self = await actor(asRole(coach))
      const plans = await new CoachSubscriptionScreen(self.page, suite).open()
      await expect(plans.status()).toHaveText('PAST_DUE')
      await expect(plans.paymentIssue()).toContainText(t('subscription.paymentIssue'))
      const profile = await new ProfileScreen(self.page, suite).open()
      await expect(profile.brandingEdit()).toBeVisible()

      // Stripe gave up: the stored tier is still Academy, but nothing it unlocks is granted.
      await provision.setSubscriptionStatus({ coachId: coach.coachId! }, 'UNPAID')
      await self.page.reload()
      await profile.waitReady()
      await expect(profile.brandingGate()).toBeVisible()
      await expect(profile.brandingEdit()).toHaveCount(0)
    },
  )

  test(
    'subscribing with no card on file is held at the same add-card prompt as a pack purchase',
    scenario({
      id: 'SUB-10',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()

      const self = await actor(asRole(coach))
      const plans = await new CoachSubscriptionScreen(self.page, suite).open()
      await plans.choose('INSTRUCTOR').click()
      await expect(plans.addCardPrompt()).toContainText(t('payment.card.addCardPrompt'))
      await expect(plans.confirm()).toBeDisabled()
    },
  )

  test(
    'a past-due subscription shows the payment problem, and a new card sets a retry going',
    scenario({
      id: 'SUB-11',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coach = await provision.coach()
      await provision.saveCard(coach)
      await provision.subscribeCoach(coach, 'INSTRUCTOR')
      await provision.setSubscriptionStatus({ coachId: coach.coachId! }, 'PAST_DUE')

      const self = await actor(asRole(coach))
      // Stripe.js confirms the SetupIntent with this card (stubbed: no card form is typed into).
      await stubStripeCardEntry(self.context, `pm_e2e_${randomBytes(8).toString('hex')}`)
      const plans = await new CoachSubscriptionScreen(self.page, suite).open()
      await expect(plans.paymentIssue()).toContainText(t('subscription.paymentIssue'))

      await plans.updateCard().click()
      // A card is on file, so the dialog offers to replace it.
      await plans.replaceCard().click()
      // The card dialog says the new card will also pay the subscription.
      await expect(plans.subscriptionNote()).toBeVisible()
      await plans.saveCard().click()
      await expect(plans.retryPending()).toHaveText(t('subscription.paymentRetrying'))
    },
  )

  test(
    'a declined card is named as the reason a subscribe failed, and the coach stays on Scout',
    scenario({
      id: 'SUB-12',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@coach', '@subscription'],
    }),
    async ({ actor, suite, provision }) => {
      await provision.subscriptionPrices()
      const coach = await provision.coach()
      await provision.saveCard(coach, { declines: true })

      const self = await actor(asRole(coach))
      const plans = await new CoachSubscriptionScreen(self.page, suite).open()
      await plans.choose('INSTRUCTOR').click()
      await plans.confirm().click()
      await expect(self.page.getByText(t('subscription.errors.paymentDeclined'))).toBeVisible()
      await expect(plans.currentTier()).toHaveText('SCOUT')
    },
  )

  // The rest of SUB-12: each refusal is reproduced the way a stale tab meets it. The page is opened, the
  // plan changes underneath it, and the click the page still offers is refused with its own message.
  type Refusal = {
    code: 'alreadyActive' | 'updatePaymentMethod' | 'inProgress' | 'notActive' | 'cancelPending'
    what: string
    /** The plan when the page opens; Scout when absent. */
    opensOn?: 'INSTRUCTOR'
    /** What happens behind the open page. */
    behind: (provision: Provisioner, coach: CoachAccount) => Promise<void>
    /** The tier the stale page still offers. */
    clicks: 'INSTRUCTOR' | 'ACADEMY'
  }
  const refusals: Refusal[] = [
    {
      code: 'alreadyActive',
      what: 'a subscribe from a page opened before another tab subscribed',
      behind: (provision, coach) => provision.subscribeCoach(coach, 'INSTRUCTOR'),
      clicks: 'ACADEMY',
    },
    {
      code: 'updatePaymentMethod',
      what: 'a subscribe while the existing subscription has an unpaid invoice',
      behind: async (provision, coach) => {
        await provision.subscribeCoach(coach, 'INSTRUCTOR')
        await provision.setSubscriptionStatus({ coachId: coach.coachId! }, 'PAST_DUE')
      },
      clicks: 'ACADEMY',
    },
    {
      code: 'inProgress',
      what: 'a second subscribe while the first is still running',
      behind: (provision, coach) => provision.markCoachSubscribeInProgress(coach),
      clicks: 'INSTRUCTOR',
    },
    {
      code: 'notActive',
      what: 'an upgrade once a renewal payment has failed',
      opensOn: 'INSTRUCTOR',
      behind: (provision, coach) =>
        provision.setSubscriptionStatus({ coachId: coach.coachId! }, 'PAST_DUE'),
      clicks: 'ACADEMY',
    },
    {
      code: 'cancelPending',
      what: 'an upgrade once the plan was cancelled in another tab',
      opensOn: 'INSTRUCTOR',
      behind: (provision, coach) => provision.cancelCoachPlan(coach),
      clicks: 'ACADEMY',
    },
  ]
  for (const refusal of refusals) {
    test(
      `${refusal.what} is refused with its own message (${refusal.code})`,
      scenario({
        id: 'SUB-12',
        priority: 'P2',
        type: 'neg',
        tags: ['@regression', '@coach', '@subscription'],
      }),
      async ({ actor, suite, provision }) => {
        await provision.subscriptionPrices()
        const coach = await provision.coach()
        await provision.saveCard(coach)
        if (refusal.opensOn) await provision.subscribeCoach(coach, refusal.opensOn)

        const self = await actor(asRole(coach))
        const plans = await new CoachSubscriptionScreen(self.page, suite).open()
        await expect(plans.currentTier()).toHaveText(refusal.opensOn ?? 'SCOUT')
        await refusal.behind(provision, coach)

        await plans.choose(refusal.clicks).click()
        // From Scout the click subscribes, through the dialog; on a paid plan it changes tier at once.
        if (!refusal.opensOn) await plans.confirm().click()
        await expect(self.page.getByText(t(`subscription.errors.${refusal.code}`))).toBeVisible()
      },
    )
  }
})
