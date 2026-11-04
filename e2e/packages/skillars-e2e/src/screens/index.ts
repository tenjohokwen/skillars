import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

export * from './auth.js'
export * from './coach.js'
export * from './marketplace.js'
export * from './booking.js'

// One screen object per page under test. Thin by policy: locators and composite actions, never
// assertions. Only pages that already carry data-testids are modelled; the rest wait for the
// test-id backfill waves.

export class CommandCentreScreen extends Screen {
  readonly route = R.commandCentre
  readonly readyTestId = 'booking-requests-link'
  bookingRequestsLink = () => this.tid('booking-requests-link')
  stripeSetupBanner = () => this.tid('stripe-setup-banner')
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
  ageGroups = () => this.tid('coach-profile-age-groups')
  price = () => this.tid('coach-profile-price')
  packs = () => this.tid('coach-profile-packs')
  /** A parent's remaining credits with this coach (only once they hold or held a pack). */
  credits = () => this.tid('coach-profile-credits')
  reviews = () => this.tid('coach-profile-review')

  /** Open the profile through a coach's shared-slot link; its CTAs carry the slot onwards. */
  async openWithSlot(coachId: string, slot: string): Promise<this> {
    await this.page.goto(
      this.context.routing.toUrl(`${this.route.replace(':coachId', coachId)}?slot=${slot}`),
    )
    return this.waitReady()
  }
}
