import type { Locator } from '@playwright/test'
import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

export * from './auth.js'
export * from './coach.js'
export * from './marketplace.js'
export * from './booking.js'
export * from './money.js'
export * from './subscription.js'
export * from './messaging.js'
export * from './video.js'
export * from './development.js'
export * from './session.js'
export * from './lockerRoom.js'
export * from './notifications.js'
export * from './privacy.js'
export * from './trust.js'
export * from './admin.js'

// One screen object per page under test. Thin by policy: locators and composite actions, never
// assertions. Only pages that already carry data-testids are modelled; the rest wait for the
// test-id backfill waves.

export class CommandCentreScreen extends Screen {
  readonly route = R.commandCentre
  readonly readyTestId = 'booking-requests-link'
  bookingRequestsLink = () => this.tid('booking-requests-link')
  stripeSetupBanner = () => this.tid('stripe-setup-banner')

  // The week schedule: one block per booking of the selected week.
  booking = (bookingId: string) =>
    this.page.locator(`[data-testid="coach-schedule-booking"][data-booking-id="${bookingId}"]`)
  /** Report a player no-show (UPCOMING sessions). */
  reportNoShow = (booking: Locator) => this.within(booking, 'coach-booking-no-show-btn')
  confirmNoShow = () => this.tid('no-show-player-confirm-btn')
  cancelSession = (booking: Locator) => this.within(booking, 'coach-booking-cancel-btn')
  cancelDialog = () => this.tid('cancel-coach-dialog')
  confirmCancel = () => this.tid('cancel-coach-confirm-btn')

  /** `label` is the reason's visible text (resolve it through `t()`). */
  async chooseCancelReason(label: string): Promise<void> {
    await this.cancelReason().click()
    await this.page.getByRole('option', { name: label, exact: true }).click()
  }
  cancelReason = () => this.tid('cancel-coach-reason')

  // Week navigation. The grid has seven day columns, Monday first, in the coach's zone.
  prevWeek = () => this.tid('schedule-prev-week')
  nextWeek = () => this.tid('schedule-next-week')
  weekLabel = () => this.tid('schedule-week-label')
  /** Shown instead of the grid when the week has no bookings. */
  emptyWeek = () => this.tid('schedule-empty')
  /** The bookings drawn in one day column; 0 is Monday. */
  bookingsOn = (dayIndex: number) =>
    this.page
      .locator(`[data-testid="schedule-day"][data-day-index="${dayIndex}"]`)
      .getByTestId('coach-schedule-booking')
  start = (booking: Locator) => this.within(booking, 'coach-booking-start-btn')
  quickComplete = (booking: Locator) => this.within(booking, 'coach-booking-quick-complete-btn')
  resumeWrapUp = (booking: Locator) => this.within(booking, 'resume-wrap-up-btn')
  recap = (booking: Locator) => this.within(booking, 'recap-btn')

  // The missing-recaps nudge: a count, then a list whose rows say "Add recap" or "Resume wrap-up".
  missingRecapsBanner = () => this.tid('missing-recaps-banner')
  missingRecapsToggle = () => this.tid('missing-recaps-toggle')
  missingRecapItems = () => this.tid('missing-recap-item')
  missingRecap = (bookingId: string) =>
    this.page.locator(`[data-testid="missing-recap-item"][data-booking-id="${bookingId}"]`)
  missingRecapAction = (item: Locator) => this.within(item, 'missing-recap-add')

  // The recap dialog (add or correct a completed session's recap).
  recapDialog = () => this.tid('recap-dialog')
  recapDialogField = (field: 'what-we-did' | 'whats-next' | 'what-went-well') =>
    this.within(this.recapDialog(), `recap-${field}`)
  recapEditableUntil = () => this.within(this.recapDialog(), 'recap-editable-until')
  recapClosed = () => this.within(this.recapDialog(), 'recap-closed')
  recapSave = () => this.within(this.recapDialog(), 'recap-save')

  /** Step back through earlier weeks (at most `maxWeeks`) until `bookingId` is on screen. */
  async findInPastWeeks(bookingId: string, maxWeeks = 6): Promise<Locator> {
    const row = this.booking(bookingId)
    const loaded = row
      .or(this.emptyWeek())
      .or(this.page.locator('[data-testid="schedule-day"]').first())
    for (let week = 0; week <= maxWeeks; week++) {
      // The week's bookings are in (or it says it has none) before the row is looked for.
      await loaded.first().waitFor()
      if (await row.isVisible()) return row
      await this.prevWeek().click()
    }
    throw new Error(`Booking ${bookingId} not found in the last ${maxWeeks} weeks`)
  }

  // The live-session overlay. End stays disabled for the first 5 minutes on its own timer.
  activeSession = () => this.tid('active-session')
  timer = () => this.tid('active-session-timer')
  endSession = () => this.tid('active-session-end')

  // The wrap-up: attendance, three star ratings, homework, recap. `data-step` is 1–4.
  wrapUp = () => this.tid('wrap-up')
  attended = () => this.tid('wrap-up-attended')
  absent = () => this.tid('wrap-up-absent')
  wrapUpNext = () => this.tid('wrap-up-next')
  rating = (axis: 'effort' | 'focus' | 'technique') => this.tid(`wrap-up-rating-${axis}`)
  recapWhatWeDid = () => this.tid('recap-what-we-did')
  recapWhatsNext = () => this.tid('recap-whats-next')
  wrapUpDone = () => this.tid('wrap-up-done')
  postWrapUpSummary = () => this.tid('post-wrap-up-summary')

  /**
   * One click on Attended (or Didn't attend, with `attended: false`), five stars on each axis (each
   * fifth star moves on by itself), no homework.
   */
  async wrapUpWith(recap: {
    whatWeDid: string
    whatsNext: string
    attended?: boolean
  }): Promise<void> {
    await (recap.attended === false ? this.absent() : this.attended()).click()
    await this.page.locator('[data-testid="wrap-up"][data-step="2"]').waitFor()
    for (const axis of ['effort', 'focus', 'technique'] as const) {
      await this.rating(axis).getByRole('radio').nth(4).click()
    }
    // The last fifth star moves on after a 200 ms delay.
    await this.page.locator('[data-testid="wrap-up"][data-step="3"]').waitFor()
    await this.wrapUpNext().click()
    await this.recapWhatWeDid().fill(recap.whatWeDid)
    await this.recapWhatsNext().fill(recap.whatsNext)
    await this.wrapUpDone().click()
  }
}

/** The coach's reliability strikes, each with its reason, an acknowledge button and an appeal. */
export class ReliabilityScreen extends Screen {
  readonly route = R.coachReliability
  readonly readyTestId = 'reliability-page'
  strikes = () => this.tid('reliability-strike')
  strike = (bookingId: string) =>
    this.page.locator(`[data-testid="reliability-strike"][data-booking-id="${bookingId}"]`)
  reason = (strike: Locator) => this.within(strike, 'reliability-strike-reason')
  noStrikes = () => this.tid('reliability-no-strikes')
  acknowledge = (strike: Locator) => this.within(strike, 'reliability-acknowledge-btn')
  appeal = (strike: Locator) => this.within(strike, 'appeal-open-btn')
}

/** /admin/health-dashboard: the overall status, and per-component cards for a full admin. */
export class HealthDashboardScreen extends Screen {
  readonly route = R.adminHealth
  readonly readyTestId = 'health-status'
  status = () => this.tid('health-status')
  accessDenied = () => this.tid('health-access-denied')
  components = () => this.tid('health-component')
  component = (name: string) =>
    this.page.locator(`[data-testid="health-component"][data-name="${name}"]`)
  componentStatus = (card: Locator) => this.within(card, 'health-component-status')
  details = (card: Locator) => this.within(card, 'health-detail')
}

export class ParentDashboardScreen extends Screen {
  readonly route = R.parentDashboard
  readonly readyTestId = 'child-card'
  childCards = () => this.tid('child-card')
}

/** A parent adds a child. Under 18, a scroll-gated, versioned consent box appears. */
export class CreatePlayerScreen extends Screen {
  readonly route = R.createPlayer
  readonly readyTestId = 'create-player-title'
  name = () => this.tid('create-player-name-input')
  dateOfBirth = () => this.tid('create-player-dob-input')
  position = () => this.tid('create-player-position-select')
  ageTier = () => this.tid('create-player-age-tier')
  consent = () => this.tid('create-player-consent')
  consentScrollBox = () => this.tid('create-player-consent-scroll-box')
  consentCheckbox = () => this.tid('create-player-consent-checkbox')
  submit = () => this.tid('create-player-submit-btn')
  error = () => this.tid('create-player-error')

  /** `label` is the option's visible text (resolve it through `t()`). */
  async choosePosition(label: string): Promise<void> {
    await this.position().click()
    await this.page.getByRole('option', { name: label, exact: true }).click()
  }
}

/** Where a limited admin lands on a full-admin route (S-28). */
export class AdminRequiredScreen extends Screen {
  readonly route = R.adminRequiresAdministrator
  readonly readyTestId = 'admin-required-title'
  message = () => this.tid('admin-required-message')
  back = () => this.tid('admin-required-back-btn')
}

export class CoachPublicProfileScreen extends Screen {
  readonly route = R.coachProfile
  readonly readyTestId = 'coach-primary-cta'
  primaryCta = () => this.tid('coach-primary-cta')
  buySessionsSecondary = () => this.tid('coach-buy-sessions-btn')
  notBookableHint = () => this.tid('coach-not-bookable-hint')
  notFound = () => this.tid('coach-profile-not-found')
  name = () => this.tid('coach-profile-name')
  rating = () => this.tid('coach-profile-rating')
  bio = () => this.tid('coach-profile-bio')
  languages = () => this.tid('coach-profile-languages')
  location = () => this.tid('coach-profile-location')
  specialties = () => this.tid('coach-profile-specialties')
  /** S-09: 'on record (valid until MM/YYYY)' or 'no check on record'. */
  safeguardingBadge = () => this.page.locator('[data-testid^="safeguarding-badge-"]')
  ageGroups = () => this.tid('coach-profile-age-groups')
  price = () => this.tid('coach-profile-price')
  packs = () => this.tid('coach-profile-packs')
  /** A parent's remaining credits with this coach (only once they hold or held a pack). */
  credits = () => this.tid('coach-profile-credits')
  reviews = () => this.tid('coach-profile-review')
  reviewBodies = () => this.tid('coach-profile-review-body')
  reviewResponses = () => this.tid('coach-profile-review-response')
  sortSelect = () => this.tid('review-sort-select')
  loadMore = () => this.tid('reviews-load-more')
  // The viewer's own review, or the way to write one.
  writeSection = () => this.tid('review-write-section')
  writeReview = () => this.tid('review-write-btn')
  ineligible = () => this.tid('review-ineligible')
  myReviewRating = () => this.tid('my-review-rating')
  myReviewBody = () => this.tid('my-review-body')
  myReviewPending = () => this.tid('my-review-pending')
  myReviewBlocked = () => this.tid('my-review-blocked')
  relatedPartyHidden = () => this.tid('review-related-party-hidden')
  editReview = () => this.tid('review-edit-btn')
  // The write/edit dialog.
  dialogBody = () => this.tid('review-dialog-body')
  dialogSubmit = () => this.tid('review-dialog-submit')
  dialogError = () => this.tid('review-dialog-error')
  // Flagging someone else's review (ReviewFlagAction) and appealing a removed one (AppealAction).
  flag = (review: Locator) => this.within(review, 'review-flag-btn')
  flagReason = () => this.tid('review-flag-reason-select')
  flagSubmit = () => this.tid('review-flag-submit-btn')
  appealOpen = () => this.tid('appeal-open-btn')
  appealReason = () => this.tid('appeal-reason-input')
  appealSubmit = () => this.tid('appeal-submit-btn')
  appealStatus = () => this.tid('appeal-status-chip')
  /** A Quasar notification with this message. */
  notification = (message: string) =>
    this.page.locator('.q-notification').filter({ hasText: message })
  /** Picks 1–5 stars in the dialog's rating control. */
  dialogStars = (stars: number) =>
    this.tid('review-dialog-rating')
      .getByRole('radio')
      .nth(stars - 1)
  /** Shown only once the family (or player) has a CONFIRMED, UPCOMING, IN_PROGRESS or COMPLETED booking. */
  messageCoach = () => this.tid('message-coach-btn')

  /** Open the profile through a coach's shared-slot link; its CTAs carry the slot onwards. */
  async openWithSlot(coachId: string, slot: string): Promise<this> {
    await this.page.goto(
      this.context.routing.toUrl(`${this.route.replace(':coachId', coachId)}?slot=${slot}`),
    )
    return this.waitReady()
  }
}

/** /coach/reviews: the coach's own reviews, with a reply box on each approved one. */
export class CoachReviewsScreen extends Screen {
  readonly route = R.coachReviews
  readonly readyTestId = 'coach-reviews-page'
  empty = () => this.tid('coach-reviews-empty')
  reviews = () => this.tid('coach-review')
  review = (body: string) => this.reviews().filter({ hasText: body })
  responseInput = (review: Locator) => this.within(review, 'coach-review-response-input')
  responseSubmit = (review: Locator) => this.within(review, 'coach-review-response-submit')
  response = (review: Locator) => this.within(review, 'coach-review-response')
}
