import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { t } from '../../src/i18n.js'
import {
  BookingRequestScreen,
  CoachPublicProfileScreen,
  CoachRequestsScreen,
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
})
