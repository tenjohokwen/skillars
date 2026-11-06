import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { Provisioner } from '../../src/data/provisioner.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { t } from '../../src/i18n.js'
import { CommandCentreScreen, ParentBookingsScreen } from '../../src/screens/index.js'

// I · Session delivery: the coach's week, the live session and its wrap-up, Quick Complete, and the
// coach's cancel reasons.
//
// The week grid follows the browser's "today". A test that needs bookings in the week on screen moves
// the browser's clock to next week (page.clock) and books real slots there, so no booking ever sits in
// the past where the 5-minute sweepers could act on it.

/** A coach with a fresh name and a family with a card. */
async function coachAndFamily(provision: Provisioner) {
  const coachName = `${provision.uniqueCity()} Coaching`
  const coach = await provision.coach({ displayName: coachName })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  return { coach, coachName, parent }
}

/** The browser believes it is next week's Wednesday, midday UTC; the clock keeps running. */
async function browseFromNextWeek(page: Page): Promise<void> {
  await page.clock.install({ time: new Date(`${nextWeekSlot(3, '12:00')}:00Z`) })
}

/** Next week's Monday, as the week label prints it (YYYY-MM-DD). */
const nextMonday = () => nextWeekSlot(1).slice(0, 10)

test.describe('I · Week schedule', () => {
  test(
    "the command centre puts the week's bookings in their day columns",
    scenario({
      id: 'SES-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await coachAndFamily(provision)
      const monday = await provision.confirmedBooking(parent, coach, { weekday: 1 })
      const wednesday = await provision.confirmedBooking(parent, coach, { weekday: 3 })
      const friday = await provision.confirmedBooking(parent, coach, { weekday: 5 })

      const self = await actor(asRole(coach))
      await browseFromNextWeek(self.page)
      const centre = await new CommandCentreScreen(self.page, suite).open()
      await expect(centre.weekLabel()).toContainText(nextMonday())
      for (const [day, bookingId] of [
        [0, monday],
        [2, wednesday],
        [4, friday],
      ] as const) {
        await expect(centre.bookingsOn(day)).toHaveCount(1)
        await expect(centre.bookingsOn(day)).toHaveAttribute('data-booking-id', bookingId)
      }
      for (const day of [1, 3, 5, 6]) await expect(centre.bookingsOn(day)).toHaveCount(0)
    },
  )

  test(
    'previous and next week load their own bookings, and coming back shows the same set',
    scenario({
      id: 'SES-02',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await coachAndFamily(provision)
      const bookingId = await provision.confirmedBooking(parent, coach, { weekday: 3 })

      const self = await actor(asRole(coach))
      await browseFromNextWeek(self.page)
      const centre = await new CommandCentreScreen(self.page, suite).open()
      await expect(centre.booking(bookingId)).toBeVisible()

      // The week before (the real current week): nothing booked.
      await centre.prevWeek().click()
      await expect(centre.weekLabel()).not.toContainText(nextMonday())
      await expect(centre.emptyWeek()).toBeVisible()
      await expect(centre.booking(bookingId)).toHaveCount(0)

      await centre.nextWeek().click()
      await expect(centre.weekLabel()).toContainText(nextMonday())
      await expect(centre.bookingsOn(2)).toHaveAttribute('data-booking-id', bookingId)
    },
  )

  test(
    'a week with no bookings shows the empty state, not an error',
    scenario({
      id: 'SES-03',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const centre = await new CommandCentreScreen(self.page, suite).open()
      for (let week = 0; week < 3; week++) await centre.nextWeek().click()
      await expect(centre.emptyWeek()).toHaveText(t('booking.schedule.noBookings'))
      await expect(self.page.locator('[data-testid="schedule-day"]')).toHaveCount(0)
    },
  )
})

test.describe('I · Live session', () => {
  test(
    'Start and Quick Complete are refused before the start; at the start the live overlay takes over',
    scenario({
      id: 'SES-04',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await coachAndFamily(provision)
      const bookingId = await provision.confirmedBooking(parent, coach)
      await provision.setBooking(bookingId, { startMinutesFromNow: 30, status: 'UPCOMING' })

      const self = await actor(asRole(coach))
      await self.page.clock.install()
      const centre = await new CommandCentreScreen(self.page, suite).open()
      const booking = centre.booking(bookingId)
      const tooEarly = self.page.getByText(t('booking.errors.sessionNotYetStarted'))
      await centre.start(booking).click()
      await expect(tooEarly).toBeVisible()
      await expect(centre.activeSession()).toHaveCount(0)
      await centre.quickComplete(booking).click()
      await expect(tooEarly.first()).toBeVisible()
      await expect(centre.wrapUp()).toHaveCount(0)

      // Now the session has started: Start opens the overlay, and End waits out its first 5 minutes.
      await provision.setBooking(bookingId, { startMinutesFromNow: -1, status: 'UPCOMING' })
      await self.page.reload()
      await centre.start(centre.booking(bookingId)).click()
      await expect(centre.activeSession()).toBeVisible()
      await expect(centre.endSession()).toBeDisabled()
      await expect(centre.endSession()).toContainText(t('booking.completion.endSessionDisabled'))
      await self.page.clock.runFor('05:01')
      await expect(centre.endSession()).toBeEnabled()
      await expect(centre.endSession()).toContainText(t('booking.completion.endSession'))
      expect(await provision.bookingStatus(bookingId)).toBe('IN_PROGRESS')
    },
  )

  test(
    'ending a session opens the wrap-up, then the summary; a short session waits for the family',
    scenario({
      id: 'SES-05',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await coachAndFamily(provision)
      const bookingId = await provision.confirmedBooking(parent, coach)
      await provision.setBooking(bookingId, { startMinutesFromNow: -1, status: 'UPCOMING' })

      const self = await actor(asRole(coach))
      await self.page.clock.install()
      const centre = await new CommandCentreScreen(self.page, suite).open()
      await centre.start(centre.booking(bookingId)).click()
      await expect(centre.activeSession()).toBeVisible()
      await self.page.clock.runFor('05:01')
      await centre.endSession().click()

      await expect(centre.activeSession()).toHaveCount(0)
      await expect(centre.wrapUp()).toHaveAttribute('data-step', '1')
      await centre.wrapUpWith({
        whatWeDid: 'Rondos and first touch under pressure',
        whatsNext: 'Weak-foot passing',
      })
      await expect(centre.postWrapUpSummary()).toBeVisible()
      await expect(centre.postWrapUpSummary()).toContainText(t('booking.completion.summaryTitle'))
      // A few minutes of a one-hour booking is under booking.live.minDurationPercent: not paid until
      // the family confirms.
      await expect
        .poll(() => provision.bookingStatus(bookingId))
        .toBe('COMPLETED_PENDING_CONFIRMATION')
      await expect(centre.postWrapUpSummary()).toBeHidden({ timeout: 10_000 })
    },
  )

  test(
    'a Quick Complete waits for the family, whose "Confirm session" completes it',
    scenario({
      id: 'SES-18',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@coach', '@parent', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, coachName, parent } = await coachAndFamily(provision)
      const bookingId = await provision.confirmedBooking(parent, coach)
      await provision.setBooking(bookingId, { startMinutesFromNow: -1, status: 'UPCOMING' })

      const self = await actor(asRole(coach))
      const centre = await new CommandCentreScreen(self.page, suite).open()
      await centre.quickComplete(centre.booking(bookingId)).click()
      await expect(centre.wrapUp()).toHaveAttribute('data-step', '1')
      await centre.wrapUpWith({ whatWeDid: 'Finishing drills', whatsNext: 'Crossing' })
      await expect
        .poll(() => provision.bookingStatus(bookingId))
        .toBe('COMPLETED_PENDING_CONFIRMATION')

      const family = await actor(asRole(parent))
      const bookings = await new ParentBookingsScreen(family.page, suite).open()
      const booking = bookings.booking(coachName)
      await expect(bookings.status(booking)).toContainText(
        t('booking.requests.statusCompletingPending'),
      )
      await bookings.confirmCompletion(booking).click()
      await expect(bookings.status(booking)).toContainText(t('booking.requests.statusCompleted'))
      await expect(bookings.confirmCompletion(booking)).toHaveCount(0)
      expect(await provision.bookingStatus(bookingId)).toBe('COMPLETED')
    },
  )
})

test.describe('I · Coach cancellation', () => {
  test(
    'a coach cancel needs a reason; the family is refunded in full, and only an unexcused reason strikes',
    scenario({
      id: 'SES-17',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@session', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await coachAndFamily(provision)
      const weather = await provision.confirmedBooking(parent, coach, { weekday: 2 })
      const preference = await provision.confirmedBooking(parent, coach, { weekday: 4 })

      const self = await actor(asRole(coach))
      await browseFromNextWeek(self.page)
      const centre = await new CommandCentreScreen(self.page, suite).open()

      /** Cancel `bookingId` for `reason`; returns the wallet refund it produced. */
      const cancelFor = async (bookingId: string, reason: string) => {
        const before = (await provision.walletLedger(parent)).length
        await centre.cancelSession(centre.booking(bookingId)).click()
        await expect(centre.cancelDialog()).toBeVisible()
        await expect(centre.confirmCancel()).toBeDisabled()
        await centre.chooseCancelReason(reason)
        await centre.confirmCancel().click()
        await expect(centre.cancelDialog()).toBeHidden()
        await expect
          .poll(async () => (await provision.walletLedger(parent)).length, { timeout: 30_000 })
          .toBeGreaterThan(before)
        const refund = (await provision.walletLedger(parent)).at(-1)!
        expect(refund.type).toBe('BOOKING_REFUND')
        return Number(refund.amount)
      }

      // Weather is excused: a full refund and no strike.
      expect(await cancelFor(weather, t('cancellation.reasonWeather'))).toBe(
        await provision.agreedPrice(weather),
      )
      expect(await provision.bookingStatus(weather)).toBe('CANCELLED_COACH')
      // Scheduling preference is not: the family is refunded in full all the same, and the coach is struck.
      expect(await cancelFor(preference, t('cancellation.reasonSchedulingPreference'))).toBe(
        await provision.agreedPrice(preference),
      )
      await expect
        .poll(async () => (await provision.strikes(coach)).map((s) => s.reason))
        .toEqual(['COACH_CANCELLATION_UNEXCUSED'])
    },
  )
})
