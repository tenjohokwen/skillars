import { Screen } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'
import { ListScreen } from './ListScreen.js'

// Booking: the family's request page and bookings list, and the coach's inbox.

/** Reached from the profile's primary CTA, which carries ?playerId (and ?slot from a shared link). */
export class BookingRequestScreen extends Screen {
  readonly route = R.requestBooking
  readonly readyTestId = 'booking-request-title'
  slots = () => this.tid('booking-slot')
  /**
   * The offered slot whose START reads `label` (see slotLabel). A row reads "<start> <end>", so the
   * match is anchored, or the previous slot's end would match too.
   */
  slotAt = (label: string) =>
    this.slots().filter({
      hasText: new RegExp(`^\\s*${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`),
    })
  ownSlots = () => this.tid('booking-slot-own')
  noSlots = () => this.tid('booking-no-slots')
  packSelect = () => this.tid('booking-pack-select')
  /**
   * Pay with the held pack. The page defaults to "Per session" even when the family holds credits,
   * so a spec that means to spend a credit must choose the pack. `label` is the option's text
   * (booking.requests.packOptionLabel through t()).
   */
  choosePack = (label: string) =>
    this.packSelect().getByRole('radio', { name: label, exact: true }).click()
  perSessionInfo = () => this.tid('per-session-info')
  paymentCard = () => this.tid('payment-card')
  notes = () => this.tid('booking-notes')
  submit = () => this.tid('booking-submit')
}

export class CoachRequestsScreen extends ListScreen {
  readonly route = R.bookingRequests
  readonly readyTestId = 'coach-request'
  requests = () => this.tid('coach-request')
  empty = () => this.tid('coach-requests-empty')
  protected rows = () => this.requests()
  /** The request for the player named `playerName`. */
  request = (playerName: string): Locator =>
    this.requests().filter({
      has: this.page.getByTestId('coach-request-player').getByText(playerName, { exact: true }),
    })
  accept = (request: Locator) => this.within(request, 'coach-request-accept')
  decline = (request: Locator) => this.within(request, 'coach-request-decline')
}

export class ParentBookingsScreen extends ListScreen {
  readonly route = R.parentBookings
  readonly readyTestId = 'parent-booking'
  bookings = () => this.tid('parent-booking')
  empty = () => this.tid('parent-bookings-empty')
  protected rows = () => this.bookings()
  /** The booking with the coach whose display name is `coachName`. */
  booking = (coachName: string): Locator =>
    this.bookings().filter({
      has: this.page.getByTestId('parent-booking-coach').getByText(coachName, { exact: true }),
    })
  status = (booking: Locator) => this.within(booking, 'parent-booking-status')
  /** Switches the row between the session's zone and the browser's. */
  timezoneToggle = (booking: Locator) => this.within(booking, 'parent-booking-tz-toggle')
  time = (booking: Locator) => this.within(booking, 'parent-booking-time')
}
