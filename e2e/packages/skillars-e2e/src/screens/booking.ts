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
  /** The payment option `label` (booking.requests.packOptionLabel or booking.packs.perSession). */
  packOption = (label: string) => this.packSelect().getByRole('radio', { name: label, exact: true })
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
  /** The "Player: <name>" line. */
  player = (booking: Locator) => this.within(booking, 'parent-booking-player')
  /** Offered on CONFIRMED / UPCOMING rows; opens the cancel dialog. */
  cancel = (booking: Locator) => this.within(booking, 'parent-booking-cancel-btn')
  /** Shown once the booking's payment is captured; opens the family's receipt. */
  receipt = (booking: Locator) => this.within(booking, 'booking-receipt')
  cancelDialog = () => this.tid('cancel-booking-dialog')
  cancelDialogBody = () => this.tid('cancel-booking-body')
  confirmCancel = () => this.tid('cancel-booking-confirm')

  // A coach no-show claim: from start + grace until the report window closes, on an unstarted session.
  /** "You can report a coach no-show from {time}", between the start and the grace's end. */
  noShowPending = (booking: Locator) => this.within(booking, 'no-show-coach-pending')
  noShowClaim = (booking: Locator) => this.within(booking, 'no-show-coach-btn')
  /** After the window: refunded automatically if nobody records the session. */
  noShowClosed = (booking: Locator) => this.within(booking, 'no-show-window-closed')
  noShowDialog = () => this.tid('no-show-coach-dialog')
  confirmNoShow = () => this.tid('no-show-coach-confirm-btn')

  // Disputes (DisputeAction) and the appeal of a closed one (AppealAction).
  raiseDispute = (booking: Locator) => this.within(booking, 'dispute-raise-btn')
  /** Shown instead of Raise once this tab raised one (the link lives in sessionStorage). */
  viewDispute = (booking: Locator) => this.within(booking, 'dispute-view-btn')
  disputeDialog = () => this.tid('dispute-raise-dialog')
  disputeReasonSelect = () => this.tid('dispute-reason-select')
  disputeDetails = () => this.tid('dispute-details-input')
  submitDispute = () => this.tid('dispute-submit-btn')
  disputeStatus = () => this.tid('dispute-status-content')
  appealOpen = () => this.tid('appeal-open-btn')
  appealReason = () => this.tid('appeal-reason-input')
  appealSubmit = () => this.tid('appeal-submit-btn')
  appealChip = () => this.tid('appeal-status-chip')

  /** `label` is the reason's visible text (resolve it through `t()`). */
  async chooseDisputeReason(label: string): Promise<void> {
    await this.disputeReasonSelect().click()
    await this.page.getByRole('option', { name: label, exact: true }).click()
  }
}
