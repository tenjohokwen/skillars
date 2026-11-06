import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { ParentAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import {
  CommandCentreScreen,
  ParentBookingsScreen,
  ReliabilityScreen,
} from '../../src/screens/index.js'

// O · No-shows. Owner policy 2026-10-31: the coach's live Start is the record. From start + 15 min to
// start + 5 h the family may claim a coach no-show (full refund to the wallet, coach strike); after a Quick
// Complete with no live start the claim splits the fee 50/50 with no strike; a session nobody started,
// claimed or completed is refunded automatically 48 h after its start, with no strike.
//
// Every booking is real (requested, accepted and paid through the API) and then moved in time with SQL
// (provision.setBooking): no test can book a slot a fixed number of minutes from now.

/** A coach with a fresh name, a family with a card, and one confirmed per-session booking between them. */
async function booked(provision: Provisioner) {
  const coachName = `${provision.uniqueCity()} Coaching`
  const coach = await provision.coach({ displayName: coachName })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  const bookingId = await provision.confirmedBooking(parent, coach)
  return { coach, coachName, parent, bookingId }
}

/** Wait for the refund the outbox writes into the family's wallet, and return it. */
async function refundOf(provision: Provisioner, parent: ParentAccount, before: number) {
  await expect
    .poll(async () => (await provision.walletLedger(parent)).length, { timeout: 30_000 })
    .toBeGreaterThan(before)
  const refund = (await provision.walletLedger(parent)).at(-1)!
  expect(refund.type).toBe('BOOKING_REFUND')
  return Number(refund.amount)
}

/** The text a template renders before its first placeholder: enough to find a line with a time in it. */
const prefixOf = (key: string, placeholder: string) =>
  t(key, { [placeholder]: '\u0000' })
    .split('\u0000')[0]!
    .trim()

test.describe('O · Coach no-show claims', () => {
  test(
    'before start + 15 min the family is told from when a coach no-show can be reported',
    scenario({
      id: 'NSD-01',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coachName, parent, bookingId } = await booked(provision)
      await provision.setBooking(bookingId, { startMinutesFromNow: -5, status: 'UPCOMING' })

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await expect(bookings.noShowPending(booking)).toContainText(
        prefixOf('cancellation.noShowCoachAvailableFrom', 'time'),
      )
      await expect(bookings.noShowClaim(booking)).toHaveCount(0)
    },
  )

  test(
    'inside the window, "Coach didn\'t show" refunds the session to the wallet and gives the coach a strike',
    scenario({
      id: 'NSD-02',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@noshow', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, coachName, parent, bookingId } = await booked(provision)
      await provision.setBooking(bookingId, { startMinutesFromNow: -30, status: 'UPCOMING' })
      const ledgerBefore = (await provision.walletLedger(parent)).length

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await bookings.noShowClaim(booking).click()
      await expect(bookings.noShowDialog()).toContainText(t('cancellation.noShowCoachConfirmBody'))
      await bookings.confirmNoShow().click()
      await expect(family.page.getByText(t('cancellation.noShowCoachSuccess'))).toBeVisible()
      await expect(bookings.status(booking)).toHaveText(t('booking.requests.statusNoShowCoach'))

      // The full fee comes back as cashable wallet credit.
      expect(await refundOf(provision, parent, ledgerBefore)).toBe(
        await provision.agreedPrice(bookingId),
      )

      // The coach sees the strike, against this booking.
      await expect
        .poll(async () => (await provision.strikes(coach)).map((s) => s.reason))
        .toEqual(['COACH_NO_SHOW'])
      const self = await actor(asRole(coach))
      const reliability = await new ReliabilityScreen(self.page, suite).open()
      await expect(reliability.reason(reliability.strike(bookingId))).toHaveText(
        t('reliability.reasonCoachNoShow'),
      )
    },
  )

  test(
    'after start + 5 h the claim is gone and the family is told the session is refunded automatically',
    scenario({
      id: 'NSD-03',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coachName, parent, bookingId } = await booked(provision)
      await provision.setBooking(bookingId, { startMinutesFromNow: -6 * 60, status: 'UPCOMING' })

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await expect(bookings.noShowClosed(booking)).toHaveText(t('cancellation.noShowWindowClosed'))
      await expect(bookings.noShowClaim(booking)).toHaveCount(0)
      // Cancelling is the same door, and it closes with the window.
      await expect(bookings.cancel(booking)).toHaveCount(0)
    },
  )

  test(
    'a claim after a Quick Complete with no live start splits the fee 50/50, with no strike',
    scenario({
      id: 'NSD-04',
      priority: 'P0',
      type: 'edge',
      tags: ['@regression', '@parent', '@noshow', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, coachName, parent, bookingId } = await booked(provision)
      await provision.setBooking(bookingId, {
        startMinutesFromNow: -90,
        status: 'COMPLETED_PENDING_CONFIRMATION',
      })
      const ledgerBefore = (await provision.walletLedger(parent)).length

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await bookings.noShowClaim(booking).click()
      // The dialog says what happens instead: half back, half to the coach, no penalty.
      await expect(bookings.noShowDialog()).toContainText(
        t('cancellation.noShowCoachConfirmBodySplit'),
      )
      await bookings.confirmNoShow().click()
      await expect(family.page.getByText(t('cancellation.noShowCoachSuccess'))).toBeVisible()

      const half = await refundOf(provision, parent, ledgerBefore)
      expect(half).toBe((await provision.agreedPrice(bookingId)) / 2)
      // No strike: wait out the listeners that would have issued one, then look.
      await expect
        .poll(() => provision.bookingStatus(bookingId))
        .not.toBe('COMPLETED_PENDING_CONFIRMATION')
      expect(await provision.strikes(coach)).toEqual([])
    },
  )

  test(
    'cancelling an unstarted session after start + 15 min is a coach no-show: full refund and a strike',
    scenario({
      id: 'NSD-05',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@noshow', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      // Still CONFIRMED (the reminder job has not run): the claim needs UPCOMING, the cancel does not.
      const { coach, coachName, parent, bookingId } = await booked(provision)
      await provision.setBooking(bookingId, { startMinutesFromNow: -30 })
      const ledgerBefore = (await provision.walletLedger(parent)).length

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await bookings.cancel(booking).click()
      await bookings.confirmCancel().click()
      await expect(bookings.cancelDialog()).toBeHidden()

      expect(await refundOf(provision, parent, ledgerBefore)).toBe(
        await provision.agreedPrice(bookingId),
      )
      await expect
        .poll(async () => (await provision.strikes(coach)).map((s) => s.reason))
        .toEqual(['COACH_NO_SHOW'])
    },
  )

  test(
    'a session nobody started, claimed or completed is refunded automatically 48 h after its start, with no strike',
    scenario({
      id: 'NSD-06',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@noshow', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const { coach, coachName, parent, bookingId } = await booked(provision)
      await provision.setBooking(bookingId, { startMinutesFromNow: -49 * 60, status: 'UPCOMING' })
      const ledgerBefore = (await provision.walletLedger(parent)).length

      await provision.runJob('unresolved-sessions')
      expect(await provision.bookingStatus(bookingId)).toBe('CANCELLED')
      expect(await refundOf(provision, parent, ledgerBefore)).toBe(
        await provision.agreedPrice(bookingId),
      )

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      await expect(bookings.status(bookings.booking(coachName))).toHaveText(
        t('booking.requests.statusCancelled'),
      )
      expect(await provision.strikes(coach)).toEqual([])
    },
  )
})

test.describe('O · Coach side', () => {
  test(
    "the coach's report of a player no-show is refused before start + 15 min",
    scenario({
      id: 'NSD-07',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, bookingId } = await booked(provision)
      await provision.setBooking(bookingId, { startMinutesFromNow: -5, status: 'UPCOMING' })

      const self = await actor(asRole(coach))
      const schedule = await new CommandCentreScreen(self.page, suite).open()
      const booking = schedule.booking(bookingId)
      await schedule.reportNoShow(booking).click()
      await schedule.confirmNoShow().click()
      await expect(self.page.getByText(t('booking.errors.noShowTooEarly'))).toBeVisible()
      expect(await provision.bookingStatus(bookingId)).toBe('UPCOMING')
    },
  )

  test(
    "a coach's fourth excused cancellation in 90 days becomes a strike",
    scenario({
      id: 'NSD-12',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@noshow'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, bookingId } = await booked(provision)
      // Three excused cancellations already this window; the cap is three.
      await provision.seedExcusedCancellations(coach, 3)
      // In the current week's schedule, still ahead.
      await provision.setBooking(bookingId, { startMinutesFromNow: 3 * 60 })

      const self = await actor(asRole(coach))
      const schedule = await new CommandCentreScreen(self.page, suite).open()
      await schedule.cancelSession(schedule.booking(bookingId)).click()
      await expect(schedule.cancelDialog()).toBeVisible()
      await schedule.chooseCancelReason(t('cancellation.reasonHealthMedical'))
      await schedule.confirmCancel().click()

      await expect
        .poll(async () => (await provision.strikes(coach)).map((s) => s.reason))
        .toEqual(['COACH_CANCELLATION_EXCUSED_LIMIT'])
      const reliability = await new ReliabilityScreen(self.page, suite).open()
      await expect(reliability.reason(reliability.strike(bookingId))).toHaveText(
        t('reliability.reasonCoachCancellationExcusedLimit'),
      )
    },
  )
})
