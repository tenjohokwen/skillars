import type { Page } from '@playwright/test'
import type { Actor, SuiteEnv } from 'pitchside-core'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { DAY_MS, dateInputValue, uiDate } from '../../src/data/dates.js'
import type { CoachAccount, ParentAccount } from '../../src/data/provisioner.js'
import { packOf } from '../../src/data/packs.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { t } from '../../src/i18n.js'
import {
  BookingRequestScreen,
  CoachPublicProfileScreen,
  CoachRequestsScreen,
  PacksDashboardScreen,
  ParentBookingsScreen,
} from '../../src/screens/index.js'

// G · Pack lifecycle. Every rule here is runtime-tunable, so expectations are computed from what the
// server reports for the seeded pack (its expiresAt, its session count), never from literal dates.

/** The tracker's wording on the coach profile (SessionPackTracker.vue creditsLabel). */
function trackerText(remaining: number, total: number): string {
  if (remaining === 0 || total === 0) return t('booking.packs.exhaustedStatus')
  const state = remaining <= 2 ? 'critical' : remaining / total < 0.3 ? 'warning' : 'healthy'
  return t(`booking.packs.${state}Status`, { remaining })
}

/**
 * A family books next week's shared slot on its pack and the coach accepts, all through the UI.
 * Returns the family's page, left on the bookings list.
 */
async function confirmedPackBooking(
  actor: (role: string) => Promise<Actor>,
  suite: SuiteEnv,
  coach: CoachAccount,
  parent: ParentAccount,
  coachName: string,
  sessions: number,
): Promise<Page> {
  const family = await actor(asRole(parent))
  const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
    coach.coachId!,
    nextWeekSlot(),
  )
  await profile.primaryCta().click()
  const request = await new BookingRequestScreen(family.page, suite).waitReady()
  await expect(
    request.packOption(
      t('booking.requests.packOptionLabel', { remaining: sessions, total: sessions }),
    ),
  ).toBeChecked()
  await request.submit().click()
  await new ParentBookingsScreen(family.page, suite).waitReady()

  const coachSide = await actor(asRole(coach))
  const inbox = await new CoachRequestsScreen(coachSide.page, suite).open()
  const incoming = inbox.request(parent.children[0]!.name)
  await inbox.accept(incoming).click()
  await expect(incoming).toHaveCount(0)
  return family.page
}

test.describe('G · Pack lifecycle', () => {
  test(
    'the pack dashboard shows sessions remaining, expiry date and status',
    scenario({
      id: 'MNY-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)
      const pack = await packOf(provision, parent, coach)

      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await expect(packs.credits(card)).toHaveText(
        t('booking.packs.creditsRemainingLabel', { remaining: sessions, total: sessions }),
      )
      await expect(packs.expiry(card)).toHaveText(
        t('booking.packs.expiresLabel', { date: uiDate(pack.expiresAt) }),
      )
      await expect(packs.status(card)).toHaveText(t('booking.packs.activeStatus'))
      await expect(packs.pause(card)).toBeVisible()
    },
  )

  test(
    'spending a credit lowers the remaining count on every surface that shows it',
    scenario({
      id: 'MNY-02',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)
      const page = await confirmedPackBooking(actor, suite, coach, parent, coachName, sessions)
      const left = sessions - 1

      // 1. The pack dashboard.
      const packs = await new PacksDashboardScreen(page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      await expect(packs.credits(packs.card(coachName))).toHaveText(
        t('booking.packs.creditsRemainingLabel', { remaining: left, total: sessions }),
      )
      // 2. The coach profile's credit tracker. 3. The request page's pack option.
      const profile = await new CoachPublicProfileScreen(page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(4),
      )
      await expect(profile.credits()).toContainText(trackerText(left, sessions))
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(page, suite).waitReady()
      await expect(
        request.packOption(
          t('booking.requests.packOptionLabel', { remaining: left, total: sessions }),
        ),
      ).toBeChecked()
      // The server agrees with every surface.
      expect((await packOf(provision, parent, coach)).remainingSessions).toBe(left)
    },
  )

  test(
    'pausing a pack pushes its expiry out by exactly the pause length',
    scenario({
      id: 'MNY-03',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      const before = await packOf(provision, parent, coach)
      const days = 21

      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await packs.pause(card).click()
      await expect(packs.pauseDialog()).toBeVisible()
      await packs.pauseStart().fill(dateInputValue(2))
      await packs.pauseDays().fill(String(days))
      await expect(packs.confirmPause()).toHaveText(t('booking.packs.pauseConfirm'))
      await packs.confirmPause().click()
      await expect(packs.pauseDialog()).toBeHidden()

      const after = await packOf(provision, parent, coach)
      expect(Date.parse(after.expiresAt) - Date.parse(before.expiresAt)).toBe(days * DAY_MS)
      expect(Date.parse(after.pausedUntil!) - Date.parse(after.pausedFrom!)).toBe(days * DAY_MS)
      await expect(packs.expiry(card)).toHaveText(
        t('booking.packs.expiresLabel', { date: uiDate(after.expiresAt) }),
      )
      // A pause that starts later leaves the pack Active, with the window noted.
      await expect(packs.status(card)).toHaveText(t('booking.packs.activeStatus'))
      await expect(packs.pauseScheduled(card)).toHaveText(
        t('booking.packs.pauseScheduledLabel', {
          from: uiDate(after.pausedFrom!),
          until: uiDate(after.pausedUntil!),
        }),
      )
    },
  )

  test(
    'a pack that used its pause offers no second one, and the endpoint refuses it',
    scenario({
      id: 'MNY-04',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      const { purchaseId } = await packOf(provision, parent, coach)

      const family = await actor(asRole(parent))
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await packs.pause(card).click()
      await packs.pauseStart().fill(dateInputValue(3))
      await packs.pauseDays().fill('7')
      await packs.confirmPause().click()
      await expect(packs.pauseDialog()).toBeHidden()

      // The control is gone, and stays gone on a fresh load.
      await family.page.reload()
      await packs.waitReady()
      await expect(packs.pause(card)).toHaveCount(0)
      await expect(packs.alreadyPaused(card)).toHaveText(t('booking.packs.alreadyPausedLabel'))
      const paused = await packOf(provision, parent, coach)

      // The server refuses a second pause outright, whatever its dates.
      const session = await provision.login(parent)
      try {
        const refused = await session.api.post(`/payment/session-packs/${purchaseId}/pause`, {
          data: {
            pauseStartDate: new Date(Date.now() + 40 * DAY_MS).toISOString(),
            pauseDurationDays: 7,
          },
          expectStatus: (status) => status >= 400 && status < 500,
        })
        expect(await refused.text()).toContain('booking.packAlreadyPaused')
      } finally {
        await session.dispose()
      }
      expect((await packOf(provision, parent, coach)).expiresAt).toBe(paused.expiresAt)
    },
  )

  test(
    'a pause over a booked session lists it and applies nothing until the family confirms',
    scenario({
      id: 'MNY-07',
      priority: 'P0',
      type: 'edge',
      tags: ['@regression', '@parent', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)
      const page = await confirmedPackBooking(actor, suite, coach, parent, coachName, sessions)
      const before = await packOf(provision, parent, coach)

      const packs = await new PacksDashboardScreen(page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await packs.pause(card).click()
      // Starts within two days, runs past next week's booking.
      await packs.pauseStart().fill(dateInputValue(2))
      await packs.pauseDays().fill('30')
      await packs.confirmPause().click()

      // First attempt: the conflict is listed and the button changes; nothing is applied.
      await expect(packs.pauseConflicts()).toHaveCount(1)
      await expect(packs.confirmPause()).toHaveText(
        t('booking.packs.pauseConfirmWithCancellations'),
      )
      await expect(packs.confirmPause()).toBeEnabled()
      await expect(packs.pauseDialog()).toBeVisible()
      const unchanged = await packOf(provision, parent, coach)
      expect(unchanged.expiresAt).toBe(before.expiresAt)
      expect(unchanged.pausedUntil).toBeNull()
    },
  )

  test(
    'confirming the conflict cancels the listed booking and applies the pause',
    scenario({
      id: 'MNY-08',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)
      const page = await confirmedPackBooking(actor, suite, coach, parent, coachName, sessions)
      const before = await packOf(provision, parent, coach)
      const days = 30

      const packs = await new PacksDashboardScreen(page, suite).open({
        playerId: parent.children[0]!.playerId,
      })
      const card = packs.card(coachName)
      await packs.pause(card).click()
      await packs.pauseStart().fill(dateInputValue(2))
      await packs.pauseDays().fill(String(days))
      await packs.confirmPause().click()
      await expect(packs.pauseConflicts()).toHaveCount(1)
      await packs.confirmPause().click()
      await expect(packs.pauseDialog()).toBeHidden()

      const after = await packOf(provision, parent, coach)
      expect(Date.parse(after.expiresAt) - Date.parse(before.expiresAt)).toBe(days * DAY_MS)
      await expect(packs.pauseScheduled(card)).toBeVisible()

      // The booking is cancelled on the bookings list too, no longer confirmed.
      const bookings = await new ParentBookingsScreen(page, suite).open()
      await expect(bookings.status(bookings.booking(coachName))).not.toHaveText(
        new RegExp(
          `^(${t('booking.requests.statusConfirmed')}|${t('booking.requests.statusUpcoming')})$`,
        ),
      )
      // A pause cancellation is a system cancel on the family's behalf; it reads as plain cancelled.
      await expect(bookings.status(bookings.booking(coachName))).toHaveText(
        new RegExp(
          `^(${t('booking.requests.statusCancelled')}|${t('booking.requests.statusCancelledParent')})$`,
        ),
      )
    },
  )
})
