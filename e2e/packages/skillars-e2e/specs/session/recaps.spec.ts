import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { Provisioner } from '../../src/data/provisioner.js'
import { E2E } from '../../src/env.js'
import { t, tc } from '../../src/i18n.js'
import { CommandCentreScreen } from '../../src/screens/index.js'

// I · Session delivery, part 2: a wrap-up that was left half-way, the missing-recaps nudge, and the
// recap dialog's edit window.
//
// Completed sessions are made through the product's own calls (Quick Complete, wrap-up, the family's
// confirmation) with the booking moved into the past first; a session whose wrap-up never came is
// made by the stuck-completion sweeper, run on request.

/** A coach and a family with a card. */
async function coachAndFamily(provision: Provisioner) {
  const coach = await provision.coach()
  const parent = await provision.parent()
  await provision.saveCard(parent)
  return { coach, parent }
}

/**
 * The date the recap dialog prints for a window that ends `windowDays` after `end`: formatted in the
 * browser, with the app's locale, as the page formats it.
 */
async function recapDateLabel(page: Page, end: Date, windowDays: number): Promise<string> {
  const until = new Date(end.getTime() + windowDays * 86_400_000).toISOString()
  return page.evaluate(
    ([iso, locale]) =>
      new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(new Date(iso)),
    [until, E2E.locale] as const,
  )
}

test.describe('I · Unfinished wrap-up', () => {
  test(
    'a wrap-up left half-way is not lost: the session row offers Resume wrap-up until it is done',
    scenario({
      id: 'SES-06',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@coach', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await coachAndFamily(provision)
      const bookingId = await provision.confirmedBooking(parent, coach)
      await provision.setBooking(bookingId, { startMinutesFromNow: -1, status: 'UPCOMING' })

      const self = await actor(asRole(coach))
      const centre = await new CommandCentreScreen(self.page, suite).open()
      await centre.quickComplete(centre.booking(bookingId)).click()
      // One click answers attendance (owner decision 2026-11-06), then the coach walks away.
      await expect(centre.wrapUp()).toHaveAttribute('data-step', '1')
      await centre.attended().click()
      await expect(centre.wrapUp()).toHaveAttribute('data-step', '2')
      await self.page.reload()

      await expect(centre.wrapUp()).toHaveCount(0)
      const row = centre.booking(bookingId)
      await expect(centre.resumeWrapUp(row)).toHaveText(t('booking.schedule.resumeWrapUp'))
      expect(await provision.bookingStatus(bookingId)).toBe('COMPLETED_PENDING_CONFIRMATION')

      // Resume starts the wizard again from attendance, and finishing it retires the button.
      await centre.resumeWrapUp(row).click()
      await expect(centre.wrapUp()).toHaveAttribute('data-step', '1')
      await centre.wrapUpWith({ whatWeDid: 'Rondos', whatsNext: 'Scanning before receiving' })
      await expect(centre.postWrapUpSummary()).toBeVisible()
      await expect(centre.postWrapUpSummary()).toBeHidden({ timeout: 10_000 })
      await expect(centre.resumeWrapUp(centre.booking(bookingId))).toHaveCount(0)
      expect(await provision.bookingStatus(bookingId)).toBe('COMPLETED_PENDING_CONFIRMATION')
    },
  )
})

test.describe('I · Recaps', () => {
  test(
    'the missing-recaps nudge counts sessions without a recap, and each one it lists can be finished',
    scenario({
      id: 'SES-15',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // runJob may wait out a scheduled sweep's 2-minute lock
      const { coach, parent } = await coachAndFamily(provision)
      // Swept: the coach Quick Completed and never wrapped up, so the sweeper completed it, empty.
      const swept = await provision.sweptSession(parent, coach)
      // Confirmed first: the family confirmed before the coach's wrap-up.
      const awaiting = await provision.completedSession(parent, coach, { weekday: 4, recap: null })

      const self = await actor(asRole(coach))
      const centre = await new CommandCentreScreen(self.page, suite).open()
      await expect(centre.missingRecapsBanner()).toContainText(
        tc('booking.recap.missingBanner', 2, { count: 2 }),
      )
      await centre.missingRecapsToggle().click()
      await expect(centre.missingRecapItems()).toHaveCount(2)
      await expect(centre.missingRecapAction(centre.missingRecap(swept))).toHaveText(
        t('booking.recap.addAction'),
      )
      await expect(centre.missingRecapAction(centre.missingRecap(awaiting))).toHaveText(
        t('booking.schedule.resumeWrapUp'),
      )

      // "Add recap" opens the recap dialog; saving it takes the count down to one.
      await centre.missingRecapAction(centre.missingRecap(swept)).click()
      await expect(centre.recapDialog()).toBeVisible()
      await centre.recapDialogField('what-we-did').fill('Pressing triggers')
      await centre.recapDialogField('whats-next').fill('Counter-pressing')
      await centre.recapSave().click()
      await expect(centre.recapDialog()).toBeHidden()
      await expect(centre.missingRecapsBanner()).toContainText(
        tc('booking.recap.missingBanner', 1, { count: 1 }),
      )

      // "Resume wrap-up" runs the whole wrap-up (attendance, ratings, homework), not just the recap.
      if (!(await centre.missingRecap(awaiting).isVisible()))
        await centre.missingRecapsToggle().click()
      await centre.missingRecapAction(centre.missingRecap(awaiting)).click()
      await expect(centre.wrapUp()).toHaveAttribute('data-step', '1')
      await centre.wrapUpWith({ whatWeDid: 'Long balls', whatsNext: 'First touch out of the air' })
      await expect(centre.missingRecapsBanner()).toHaveCount(0, { timeout: 15_000 })
      expect(await provision.bookingStatus(awaiting)).toBe('COMPLETED')
    },
  )

  test(
    'the recap dialog says until when it can be edited, and once it cannot, says why and offers no Save',
    scenario({
      id: 'SES-16',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@coach', '@parent', '@session'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await coachAndFamily(provision)
      const windowDays = Number(await provision.configValue('booking.recap.editWindowDays'))
      const open = await provision.completedSession(parent, coach, { weekday: 1 })
      const disputed = await provision.completedSession(parent, coach, {
        weekday: 3,
        endedMinutesAgo: 180,
      })
      await provision.raiseDispute(parent, disputed)
      const old = await provision.completedSession(parent, coach, {
        weekday: 5,
        endedMinutesAgo: 300,
      })
      // Its end moves past the window: (window + 1) days ago.
      await provision.setBooking(old, { startMinutesFromNow: -(windowDays + 1) * 1440 })

      const self = await actor(asRole(coach))
      const centre = await new CommandCentreScreen(self.page, suite).open()

      // Open: "editable until", three fields capped at 500, and Save keeps the change.
      await (await centre.findInPastWeeks(open)).getByTestId('recap-btn').click()
      await expect(centre.recapDialog()).toBeVisible()
      const until = await recapDateLabel(self.page, await provision.bookingEnd(open), windowDays)
      await expect(centre.recapEditableUntil()).toHaveText(
        t('booking.recap.editableUntil', { date: until }),
      )
      for (const field of ['what-we-did', 'whats-next', 'what-went-well'] as const) {
        await centre.recapDialogField(field).fill('y'.repeat(600))
        await expect(centre.recapDialogField(field)).toHaveValue('y'.repeat(500))
      }
      await centre.recapDialogField('what-we-did').fill('Corrected: crossing and finishing')
      await centre.recapSave().click()
      await expect(centre.recapDialog()).toBeHidden()
      await centre.recap(centre.booking(open)).click()
      await expect(centre.recapDialogField('what-we-did')).toHaveValue(
        'Corrected: crossing and finishing',
      )
      await self.page.keyboard.press('Escape')
      await expect(centre.recapDialog()).toBeHidden()

      // Disputed: the reason, read-only fields, no Save.
      await (await centre.findInPastWeeks(disputed)).getByTestId('recap-btn').click()
      await expect(centre.recapClosed()).toHaveText(t('booking.recap.disputedError'))
      await expect(centre.recapSave()).toHaveCount(0)
      await expect(centre.recapDialogField('what-we-did')).toHaveAttribute('readonly', '')
      await self.page.keyboard.press('Escape')
      await expect(centre.recapDialog()).toBeHidden()

      // Past the window: when it closed, and no Save.
      await (await centre.findInPastWeeks(old)).getByTestId('recap-btn').click()
      const closedOn = await recapDateLabel(self.page, await provision.bookingEnd(old), windowDays)
      await expect(centre.recapClosed()).toHaveText(
        t('booking.recap.closedWindow', { date: closedOn }),
      )
      await expect(centre.recapSave()).toHaveCount(0)
    },
  )
})
