import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { ParentBookingsScreen, WalletScreen } from '../../src/screens/index.js'

// O · Disputes and appeals. A family (or the coach) may dispute a settled booking within 14 days, twice
// per side at most, one open at a time. An admin resolves or dismisses it; the raiser may then appeal
// once. Admin decisions are made through the admin API (the admin console is suite R).

/** A coach with a fresh name, a family with a card, and a booking settled `daysAgo` days ago. */
async function settled(provision: Provisioner, daysAgo = 0) {
  const coachName = `${provision.uniqueCity()} Coaching`
  const coach = await provision.coach({ displayName: coachName })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  const bookingId = await provision.confirmedBooking(parent, coach)
  await provision.setBooking(bookingId, {
    startMinutesFromNow: -(daysAgo * 24 * 60 + 180),
    status: 'COMPLETED',
    statusChangedDaysAgo: daysAgo,
  })
  return { coach, coachName, parent, bookingId }
}

const REASONS = [
  'COACH_NO_SHOW',
  'SESSION_QUALITY',
  'SAFETY_CONCERN',
  'UNAUTHORISED_CHARGE',
  'OTHER',
]

test.describe('O · Disputes', () => {
  test(
    'a family raises a dispute; Submit stays disabled until a reason and details are given',
    scenario({
      id: 'NSD-08',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coachName, parent, bookingId } = await settled(provision)

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await bookings.raiseDispute(booking).click()
      await expect(bookings.disputeDialog()).toBeVisible()
      await expect(bookings.submitDispute()).toBeDisabled()

      await bookings.disputeReasonSelect().click()
      for (const reason of REASONS) {
        await expect(
          family.page.getByRole('option', { name: t(`disputes.reason.${reason}`), exact: true }),
        ).toBeVisible()
      }
      await family.page
        .getByRole('option', { name: t('disputes.reason.SESSION_QUALITY'), exact: true })
        .click()
      await expect(bookings.submitDispute()).toBeDisabled()
      await bookings.disputeDetails().fill('   ')
      await expect(bookings.submitDispute()).toBeDisabled()
      await bookings.disputeDetails().fill('The session ended 25 minutes early.')
      await bookings.submitDispute().click()
      await expect(family.page.getByText(t('disputes.raiseSuccess'))).toBeVisible()

      await expect.poll(async () => (await provision.disputeOn(bookingId))?.status).toBe('OPEN')
      await bookings.viewDispute(booking).click()
      await expect(bookings.disputeStatus()).toContainText(t('disputes.statusValue.OPEN'))
      await expect(bookings.disputeStatus()).toContainText(t('disputes.reason.SESSION_QUALITY'))
    },
  )

  test(
    'a dispute more than 14 days after the booking settled is refused',
    scenario({
      id: 'NSD-09',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coachName, parent, bookingId } = await settled(provision, 15)

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      await bookings.raiseDispute(bookings.booking(coachName)).click()
      await bookings.chooseDisputeReason(t('disputes.reason.SESSION_QUALITY'))
      await bookings.disputeDetails().fill('Raised too late.')
      await bookings.submitDispute().click()
      await expect(family.page.getByText(t('disputes.windowExpired'))).toBeVisible()
      expect(await provision.disputeOn(bookingId)).toBeUndefined()
    },
  )

  test(
    'one open dispute per side at a time, and at most two per side on a booking',
    scenario({
      id: 'NSD-10',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@parent', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coachName, parent, bookingId } = await settled(provision)
      // Raised elsewhere (another device): this tab still offers Raise.
      const first = await provision.raiseDispute(parent, bookingId)

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      const raise = async () => {
        await bookings.raiseDispute(booking).click()
        await bookings.chooseDisputeReason(t('disputes.reason.OTHER'))
        await bookings.disputeDetails().fill('Another dispute on the same booking.')
        await bookings.submitDispute().click()
      }
      await raise()
      await expect(family.page.getByText(t('disputes.alreadyRaised'))).toBeVisible()

      // Both allowed disputes used up (each dismissed), a third is refused.
      await provision.closeDispute(first, { dismiss: 'Not substantiated.' })
      const second = await provision.raiseDispute(parent, bookingId)
      await provision.closeDispute(second, { dismiss: 'Not substantiated.' })
      await family.page.reload()
      await bookings.waitReady()
      await raise()
      await expect(family.page.getByText(t('disputes.limitReached'))).toBeVisible()
    },
  )

  test(
    'a dismissed dispute offers one appeal, which becomes a status chip carrying the decision note',
    scenario({
      id: 'NSD-11',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coachName, parent, bookingId } = await settled(provision)

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await bookings.raiseDispute(booking).click()
      await bookings.chooseDisputeReason(t('disputes.reason.SESSION_QUALITY'))
      await bookings.disputeDetails().fill('Half of the drills were skipped.')
      await bookings.submitDispute().click()
      await expect(family.page.getByText(t('disputes.raiseSuccess'))).toBeVisible()

      // No appeal while it is open.
      await bookings.viewDispute(booking).click()
      await expect(bookings.disputeStatus()).toContainText(t('disputes.statusValue.OPEN'))
      await expect(bookings.appealOpen()).toHaveCount(0)
      await family.page.keyboard.press('Escape')

      await expect.poll(async () => (await provision.disputeOn(bookingId))?.id).toBeTruthy()
      const disputeId = (await provision.disputeOn(bookingId))!.id
      await provision.closeDispute(disputeId, { dismiss: 'The coach delivered the full plan.' })

      await family.page.reload()
      await bookings.waitReady()
      await bookings.viewDispute(booking).click()
      await expect(bookings.disputeStatus()).toContainText(t('disputes.statusValue.DISMISSED'))
      await bookings.appealOpen().click()
      await bookings.appealReason().fill('The session plan shows four drills; we did two.')
      await bookings.appealSubmit().click()
      await expect(bookings.appealChip()).toContainText(t('appeals.status.OPEN'))
      await expect(bookings.appealOpen()).toHaveCount(0)

      // Decided: the chip shows the outcome, and the admin's note on hover.
      const note = 'Reviewed the session plan with the coach; the dismissal stands.'
      await provision.decideAppeal(disputeId, 'UPHELD', note)
      await family.page.reload()
      await bookings.waitReady()
      await bookings.viewDispute(booking).click()
      await expect(bookings.appealChip()).toContainText(t('appeals.status.UPHELD'))
      await bookings.appealChip().hover()
      await expect(family.page.getByText(note)).toBeVisible()
    },
  )

  test(
    "an open dispute blocks the family's cash-out until it is closed",
    scenario({
      id: 'NSD-13',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@parent', '@noshow', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { parent, bookingId } = await settled(provision)
      await provision.seedWalletCredit(parent, 30, { fundedBy: { capturedCents: 3000 } })
      const disputeId = await provision.raiseDispute(parent, bookingId)

      const family = await actor(asRole(parent))
      const wallet = await new WalletScreen(family.page, suite).open()
      const before = (await wallet.balance().textContent())!.trim()
      await wallet.cashOutAmount().fill('10')
      await wallet.cashOut().click()
      await expect(wallet.cashOutError()).toHaveText(t('payment.credits.cashOutBlockedByDispute'))
      await expect(wallet.balance()).toHaveText(before)

      await provision.closeDispute(disputeId, { dismiss: 'Not substantiated.' })
      await wallet.cashOutAmount().fill('10')
      await wallet.cashOut().click()
      await expect(
        family.page.getByText(t('payment.credits.cashoutSuccess', { amount: '10.00' })),
      ).toBeVisible()
      await expect(wallet.cashOutError()).toHaveCount(0)
    },
  )
})
