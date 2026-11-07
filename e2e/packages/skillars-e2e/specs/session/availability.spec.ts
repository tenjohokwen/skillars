import type { Locator } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { E2E } from '../../src/env.js'
import { t, tc } from '../../src/i18n.js'
import { AvailabilityScreen } from '../../src/screens/index.js'

// I · Session delivery, part 3: the coach's availability calendar. A published coach has
// Monday–Friday 09:00–17:00 (Europe/Berlin); the calendar draws them on every week.
//
// Owner decision 2026-11-06 (SES-10, option A): deleting or changing a window never touches its booked
// sessions, but the coach is told first how many would end up outside their hours.

/** The weekday's long name, as the panel's day picker lists it (1 = Monday). */
function weekdayName(isoDay: number): string {
  return new Intl.DateTimeFormat(E2E.locale, { weekday: 'long', timeZone: 'UTC' }).format(
    new Date(Date.UTC(2024, 0, isoDay)), // 2024-01-01 was a Monday
  )
}

const background = (overlay: Locator) =>
  overlay.evaluate((el) => getComputedStyle(el).backgroundColor)

test.describe('I · Availability calendar', () => {
  test(
    'recurring windows and one-off blocks are drawn distinctly, each on its own day',
    scenario({
      id: 'SES-07',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const calendar = await new AvailabilityScreen(self.page, suite).open()

      // A block next Thursday, 12:00–14:00.
      const thursday = nextWeekSlot(4).slice(0, 10)
      await calendar.blockStartDate().fill(thursday)
      await calendar.blockStartTime().fill('12:00')
      await calendar.blockEndDate().fill(thursday)
      await calendar.blockEndTime().fill('14:00')
      await calendar.blockReason().fill('Dentist')
      await calendar.saveBlock().click()
      await expect(calendar.blockSaved()).toBeVisible()
      await calendar.nextWeek().click()

      for (const day of [0, 1, 2, 3, 4]) {
        await expect(calendar.windowsOn(day)).toHaveCount(1)
        await expect(calendar.windowsOn(day)).toContainText('09:00')
        await expect(calendar.windowsOn(day)).toContainText('17:00')
      }
      for (const day of [5, 6]) await expect(calendar.windowsOn(day)).toHaveCount(0)
      await expect(calendar.blocks()).toHaveCount(1)
      const block = calendar.blocksOn(3)
      await expect(block).toHaveText(new RegExp(t('booking.availability.blocked')))
      // Not the same look: a coach must never take a block for a window.
      expect(await background(block)).not.toBe(await background(calendar.windowsOn(3)))

      // The block is that week's only: the week after has none.
      await calendar.nextWeek().click()
      await expect(calendar.windowsOn(3)).toHaveCount(1)
      await expect(calendar.blocks()).toHaveCount(0)
    },
  )

  test(
    'adding a window from the always-visible panel keeps the week in view',
    scenario({
      id: 'SES-08',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const calendar = await new AvailabilityScreen(self.page, suite).open()
      await calendar.nextWeek().click()
      await calendar.nextWeek().click()
      const week = await calendar.weekLabel().textContent()

      await calendar.chooseDay(weekdayName(6))
      await calendar.windowStart().fill('10:00')
      await calendar.windowEnd().fill('12:00')
      await calendar.saveWindow().click()
      await expect(calendar.windowSaved()).toBeVisible()

      await expect(calendar.weekLabel()).toHaveText(week!)
      await expect(calendar.windowsOn(5)).toHaveCount(1)
      await expect(calendar.windowsOn(5)).toContainText('10:00')
      await expect(calendar.windowsOn(5)).toContainText('12:00')
    },
  )

  test(
    'a window is edited from the calendar, and a delete asks first',
    scenario({
      id: 'SES-09',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const calendar = await new AvailabilityScreen(self.page, suite).open()

      // Clicking Wednesday's window loads it into the panel.
      await calendar.windowsOn(2).click()
      await expect(calendar.windowStart()).toHaveValue('09:00')
      await expect(calendar.windowEnd()).toHaveValue('17:00')
      await expect(calendar.windowCancel()).toBeVisible()
      await calendar.windowEnd().fill('15:00')
      await calendar.saveWindow().click()
      // Nothing booked, so nothing to confirm.
      await expect(calendar.windowSaved()).toBeVisible()
      await expect(calendar.impactDialog()).toHaveCount(0)
      await expect(calendar.windowsOn(2)).toContainText('15:00')
      await expect(calendar.windowCancel()).toHaveCount(0)

      // Deleting Friday's window asks first; Cancel keeps it.
      await calendar.deleteWindow(calendar.windowsOn(4)).click()
      await expect(calendar.impactMessage()).toHaveText(
        t('booking.availability.deleteWindowNoSessions'),
      )
      await calendar.impactCancel().click()
      await expect(calendar.impactDialog()).toBeHidden()
      await expect(calendar.windowsOn(4)).toHaveCount(1)

      await calendar.deleteWindow(calendar.windowsOn(4)).click()
      await calendar.impactConfirm().click()
      await expect(calendar.impactDialog()).toBeHidden()
      await expect(calendar.windowsOn(4)).toHaveCount(0)
      await expect(calendar.windows()).toHaveCount(4)
    },
  )

  test(
    'a window with a booked session says so before a delete or an edit, and the booking is kept',
    scenario({
      id: 'SES-10',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@coach', '@parent', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      // Next Tuesday 10:00 Berlin, inside Tuesday's 09:00–17:00.
      const bookingId = await provision.confirmedBooking(parent, coach, {
        weekday: 2,
        time: '10:00',
      })

      const self = await actor(asRole(coach))
      const calendar = await new AvailabilityScreen(self.page, suite).open()

      // A delete names the session it would leave outside; Cancel keeps the window.
      await calendar.deleteWindow(calendar.windowsOn(1)).click()
      await expect(calendar.impactMessage()).toHaveText(
        tc('booking.availability.deleteWindowImpact', 1, { count: 1 }),
      )
      await calendar.impactCancel().click()
      await expect(calendar.windowsOn(1)).toHaveCount(1)

      // An edit to 12:00–17:00 would leave 10:00 outside: it asks, then saves on confirm.
      await calendar.windowsOn(1).click()
      await calendar.windowStart().fill('12:00')
      await calendar.saveWindow().click()
      await expect(calendar.impactMessage()).toHaveText(
        tc('booking.availability.editWindowImpact', 1, { count: 1 }),
      )
      await calendar.impactConfirm().click()
      await expect(calendar.windowSaved()).toBeVisible()
      await expect(calendar.windowsOn(1)).toContainText('12:00')

      // The session stays booked.
      expect(await provision.bookingStatus(bookingId)).toBe('CONFIRMED')
    },
  )
})
