import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

export * from './auth.js'
export * from './coach.js'
export * from './marketplace.js'

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
}
