import { Screen, waitForSettledRoute, type SettledRouteOptions } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'

// Booking: the family's request page and bookings list, and the coach's inbox.

/**
 * A list page that renders either rows or an empty state once loaded. Ready means settled on the
 * route and showing a first row OR the empty state, so an empty list is ready too.
 */
abstract class ListScreen extends Screen {
  protected abstract rows(): Locator
  abstract empty(): Locator

  override async waitReady(options?: SettledRouteOptions): Promise<this> {
    await waitForSettledRoute(this.page, this.context.routing, this.route, options)
    await this.rows()
      .or(this.empty())
      .first()
      .waitFor({ state: 'visible', timeout: options?.timeoutMs })
    return this
  }
}

/** Reached from the profile's primary CTA, which carries ?playerId (and ?slot from a shared link). */
export class BookingRequestScreen extends Screen {
  readonly route = R.requestBooking
  readonly readyTestId = 'booking-request-title'
  slots = () => this.tid('booking-slot')
  ownSlots = () => this.tid('booking-slot-own')
  noSlots = () => this.tid('booking-no-slots')
  packSelect = () => this.tid('booking-pack-select')
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
  time = (booking: Locator) => this.within(booking, 'parent-booking-time')
}
