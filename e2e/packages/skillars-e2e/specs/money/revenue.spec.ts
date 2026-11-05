import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { t, tc } from '../../src/i18n.js'
import { RevenueScreen } from '../../src/screens/index.js'

// G · A coach's revenue page, as a coach with no completed sessions sees it. Figures and "Releases
// on {date}" come from payouts, written only when a session completes: suite I covers them.

/** RevenueDashboardPage.formatHold: whole days read as days, anything else as hours. */
const holdLabel = (hours: number) =>
  hours >= 24 && hours % 24 === 0
    ? tc('revenue.newCoachHold.days', hours / 24)
    : tc('revenue.newCoachHold.hours', hours)

test.describe('G · Revenue', () => {
  test(
    "a new coach's revenue page explains the longer hold on their first payouts",
    scenario({
      id: 'MNY-25',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      // The rule's numbers are runtime config (C-16): read them rather than hard-code the defaults.
      const config = async (key: string) => Number(await provision.configValue(key))
      const count = await config('payment.payout.newCoach.releasedCount')
      const newCoachHours = await config('payment.payout.newCoach.holdHours')
      const normalHours = await config('payment.payout.hold_hours')
      const minNet = await config('payment.payout.newCoach.minReleasedNet')
      const coach = await provision.coach({ displayName: `${provision.uniqueCity()} Coaching` })

      const coachSide = await actor(asRole(coach))
      const revenue = await new RevenueScreen(coachSide.page, suite).open()
      if (count === 0 || newCoachHours <= normalHours) {
        // No longer hold configured: nothing to explain (CoachPayoutOutboxSupport.PayoutHold.newCoach).
        await expect(revenue.holdBanner()).toHaveCount(0)
        return
      }
      const banner = revenue.holdBanner()
      await expect(banner).toContainText(
        t('revenue.newCoachHold.banner', {
          count,
          hold: holdLabel(newCoachHours),
          normal: holdLabel(normalHours),
        }),
      )
      // No payout released yet, so all of them are still to come.
      await expect(banner).toContainText(t('revenue.newCoachHold.remaining', { remaining: count }))
      if (minNet > 0) {
        await expect(banner).toContainText(
          t('revenue.newCoachHold.minNote', { amount: `€${minNet.toFixed(2)}` }),
        )
      }
    },
  )

  test(
    'a date range with no payouts shows zeroes, not blanks, and no transactions',
    scenario({
      id: 'MNY-17',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach({ displayName: `${provision.uniqueCity()} Coaching` })
      const coachSide = await actor(asRole(coach))
      const revenue = await new RevenueScreen(coachSide.page, suite).open()

      await revenue.from().fill('2025-01-01')
      await revenue.to().fill('2025-01-31')
      await revenue.apply().click()

      await expect(revenue.gross()).toHaveText('€0.00')
      await expect(revenue.commission()).toHaveText('– €0.00')
      await expect(revenue.net()).toHaveText('€0.00')
      await expect(revenue.refunds()).toHaveText('€0.00')
      await expect(revenue.sessions()).toHaveText('0')
      await expect(revenue.held()).toHaveCount(0)
      await expect(revenue.transactions()).toHaveCount(0)
    },
  )
})
