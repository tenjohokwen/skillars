import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { nextWeekSlot, slotInstant } from '../../src/data/slots.js'
import { E2E } from '../../src/env.js'
import { t } from '../../src/i18n.js'
import {
  BookingRequestScreen,
  CoachPublicProfileScreen,
  CoachRequestsScreen,
  PacksDashboardScreen,
  ParentBookingsScreen,
} from '../../src/screens/index.js'
import { chargesFor } from '../../src/stubs/wiremock.js'

// Two personas per test, each in its own browser context. Both accounts are provisioned per test:
// a confirmed booking occupies the coach's slot and spends the family's money, so neither may be a
// shared baseline persona.

const confirmed = new RegExp(
  `^(${t('booking.requests.statusConfirmed')}|${t('booking.requests.statusUpcoming')})$`,
)

test.describe('F · Booking lifecycle', () => {
  test(
    'a parent with credits requests a slot, the coach accepts, and both sides see it confirmed',
    scenario({
      id: 'BKG-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@booking'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.buyPack(parent, coach)
      const child = parent.children[0]!

      // Family: request the shared slot, paying with the pack.
      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(),
      )
      await expect(profile.credits()).toBeVisible()
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(family.page, suite).waitReady()
      await expect(request.packSelect()).toBeVisible()
      await expect(request.submit()).toBeEnabled()
      await request.submit().click()

      const bookings = await new ParentBookingsScreen(family.page, suite).waitReady()
      const booking = bookings.booking(coachName)
      await expect(bookings.status(booking)).toHaveText(t('booking.requests.statusRequested'))

      // Coach: accept it from the inbox.
      const coachSide = await actor(asRole(coach))
      const inbox = await new CoachRequestsScreen(coachSide.page, suite).open()
      const incoming = inbox.request(child.name)
      await expect(incoming).toHaveCount(1)
      await inbox.accept(incoming).click()
      await expect(incoming).toHaveCount(0)

      // Family: the same booking now reads confirmed.
      await bookings.open()
      await expect(bookings.status(bookings.booking(coachName))).toHaveText(confirmed)
    },
  )

  test(
    'a family with no pack books per session and is charged only when the coach accepts',
    scenario({
      id: 'BKG-21',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@booking', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName, perSessionPrice: 55 })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const child = parent.children[0]!

      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(3),
      )
      await expect(profile.primaryCta()).toHaveText(t('marketplace.bookSession'))
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(family.page, suite).waitReady()
      // toContainText: the banner's text also carries its icon's ligature name.
      await expect(request.perSessionInfo()).toContainText(t('booking.requests.perSessionInfo'))
      await expect(request.paymentCard()).toBeVisible()
      await expect(request.packSelect()).toHaveCount(0)
      await request.submit().click()

      const bookings = await new ParentBookingsScreen(family.page, suite).waitReady()
      await expect(bookings.status(bookings.booking(coachName))).toHaveText(
        t('booking.requests.statusRequested'),
      )
      // Requested is not charged: no PaymentIntent for this payer yet.
      expect(await chargesFor(parent.userId)).toBe(0)

      const coachSide = await actor(asRole(coach))
      const inbox = await new CoachRequestsScreen(coachSide.page, suite).open()
      const incoming = inbox.request(child.name)
      await inbox.accept(incoming).click()
      await expect(incoming).toHaveCount(0)

      // Accepting charges the saved card exactly once (no wallet credit to use first).
      await expect.poll(() => chargesFor(parent.userId)).toBe(1)
      await bookings.open()
      await expect(bookings.status(bookings.booking(coachName))).toHaveText(confirmed)
    },
  )

  test(
    'a coach declines a request and the family sees it declined, with the credit back',
    scenario({
      id: 'BKG-11',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@booking'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const sessions = await provision.buyPack(parent, coach)
      const child = parent.children[0]!

      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(4),
      )
      await profile.primaryCta().click()
      await (await new BookingRequestScreen(family.page, suite).waitReady()).submit().click()
      const bookings = await new ParentBookingsScreen(family.page, suite).waitReady()
      await expect(bookings.status(bookings.booking(coachName))).toHaveText(
        t('booking.requests.statusRequested'),
      )

      const coachSide = await actor(asRole(coach))
      const inbox = await new CoachRequestsScreen(coachSide.page, suite).open()
      const incoming = inbox.request(child.name)
      await inbox.decline(incoming).click()
      await expect(incoming).toHaveCount(0)

      await bookings.open()
      await expect(bookings.status(bookings.booking(coachName))).toHaveText(
        t('booking.requests.statusDeclined'),
      )
      const packs = await new PacksDashboardScreen(family.page, suite).open({
        playerId: child.playerId,
      })
      await expect(packs.credits(packs.card(coachName))).toHaveText(
        t('booking.packs.creditsRemainingLabel', { remaining: sessions, total: sessions }),
      )
    },
  )
})

// C-12: a slot belongs to the coach's zone. A parent's browser elsewhere must not shift it.
test.describe('F · Booking lifecycle — parent browser in New York, coach in Berlin', () => {
  test.use({ timezoneId: 'America/New_York' })
  const coachZone = 'Europe/Berlin'
  const parentZone = 'America/New_York'
  // A slot row reads "<start>  <end>": anchor on the start, or the previous slot's end matches too.
  const startsWith = (label: string) =>
    new RegExp(`^\\s*${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)

  // Formatted in the browser with the app's own options. The request page labels a slot with its
  // zone using explicit components (SLOT_FORMAT in BookingRequestPage; dateStyle cannot be combined
  // with timeZoneName); the bookings list uses dateStyle/timeStyle without a zone.
  const format = (page: Page, instant: Date, timeZone: string, withZone: boolean) =>
    page.evaluate(
      ([iso, zone, locale, labelled]) =>
        new Intl.DateTimeFormat(
          locale,
          labelled
            ? {
                timeZone: zone,
                year: 'numeric',
                month: 'short',
                day: 'numeric',
                hour: 'numeric',
                minute: '2-digit',
                timeZoneName: 'short',
              }
            : { timeZone: zone, dateStyle: 'medium', timeStyle: 'short' },
        ).format(new Date(iso)),
      [instant.toISOString(), timeZone, E2E.locale, withZone] as const,
    )

  test(
    'slots render in the coach’s zone, labelled with it',
    scenario({
      id: 'BKG-04',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@booking', '@tz'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach({ timezone: coachZone })
      const parent = await provision.parent()
      const slot = nextWeekSlot(2, '10:00')
      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        slot,
      )
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(family.page, suite).waitReady()
      // 10:00 in Berlin, written in Berlin time with its zone; 04:00 New York time must not appear.
      const start = slotInstant(slot, coachZone)
      await expect(
        request
          .slots()
          .filter({ hasText: startsWith(await format(family.page, start, coachZone, true)) }),
      ).toHaveCount(1)
      await expect(
        request
          .slots()
          .filter({ hasText: startsWith(await format(family.page, start, parentZone, true)) }),
      ).toHaveCount(0)
    },
  )

  test(
    'the session / my-time toggle changes the displayed time and nothing else',
    scenario({
      id: 'BKG-15',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@booking', '@tz'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName, timezone: coachZone })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const slot = nextWeekSlot(5, '11:00')
      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        slot,
      )
      await profile.primaryCta().click()
      await (await new BookingRequestScreen(family.page, suite).waitReady()).submit().click()

      const bookings = await new ParentBookingsScreen(family.page, suite).waitReady()
      const booking = bookings.booking(coachName)
      const start = slotInstant(slot, coachZone)
      const requested = t('booking.requests.statusRequested')
      await expect(bookings.time(booking)).toHaveText(
        await format(family.page, start, coachZone, false),
      )
      await bookings.timezoneToggle(booking).click()
      await expect(bookings.time(booking)).toHaveText(
        await format(family.page, start, parentZone, false),
      )
      await expect(bookings.status(booking)).toHaveText(requested)
      await expect(booking).toHaveCount(1)
      await bookings.timezoneToggle(booking).click()
      await expect(bookings.time(booking)).toHaveText(
        await format(family.page, start, coachZone, false),
      )
    },
  )
})
