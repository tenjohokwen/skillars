import type { Locator } from '@playwright/test'
import { Screen } from 'pitchside-core'
import { R } from '../routes.js'

/**
 * The coach's plan page and a player's plan page share one layout and one set of test ids: the
 * current plan, a card per tier with its action, the subscribe dialog and the payment-issue banner.
 */
abstract class SubscriptionScreen extends Screen {
  readonly readyTestId = 'subscription-current-tier'
  currentTier = () => this.tid('subscription-current-tier')
  status = () => this.tid('subscription-status')
  /** "Renews on" — the end of the paid period. */
  periodEnd = () => this.tid('subscription-period-end')
  cancelPending = () => this.tid('subscription-cancel-pending')
  /** On the cancel-pending banner: take the cancel back. */
  keepPlan = () => this.tid('subscription-keep-plan-btn')
  /** A paid downgrade waiting for the period end. */
  changePending = () => this.tid('subscription-change-pending')
  /** On the change-pending banner: stay on the current tier. */
  keepTier = () => this.tid('subscription-keep-tier-btn')
  downgradeDialog = () => this.tid('subscription-downgrade-dialog')
  confirmDowngrade = () => this.tid('subscription-downgrade-dialog-confirm')
  cancelDialog = () => this.tid('subscription-cancel-dialog')
  confirmCancel = () => this.tid('subscription-cancel-dialog-confirm')
  /** The cancel dialog's way out: Keep my plan, which changes nothing. */
  dismissCancel = () => this.tid('subscription-cancel-dialog-dismiss')
  tierCards = () => this.tid('subscription-tier-card')
  tierCard = (tier: string): Locator =>
    this.page.locator(`[data-testid="subscription-tier-card"][data-tier="${tier}"]`)
  /** Upgrade or Downgrade on a tier that is not the current one. */
  choose = (tier: string) => this.within(this.tierCard(tier), 'subscription-change-btn')
  /** Cancel, on the current paid tier. Opens the cancel dialog; nothing is cancelled until it confirms. */
  cancel = (tier: string) => this.within(this.tierCard(tier), 'subscription-cancel-btn')
  addCardPrompt = () => this.tid('subscription-add-card-prompt')
  confirm = () => this.tid('subscription-confirm-btn')
  paymentIssue = () => this.tid('payment-issue-banner')
  updateCard = () => this.tid('subscription-update-card-btn')
  /** In the card dialog, with a card already on file: swap it for a new one. */
  replaceCard = () => this.tid('payment-card-replace-btn')
  /** In the add-card dialog: the note that a saved card also pays the subscription. */
  subscriptionNote = () => this.tid('subscription-note')
  saveCard = () => this.tid('payment-card-save-btn')
  retryPending = () => this.tid('payment-retry-pending')
}

export class CoachSubscriptionScreen extends SubscriptionScreen {
  readonly route = R.coachSubscription
}

/** Opened with { playerId }: by the parent for a child, or by a self-registered player for themselves. */
export class PlayerSubscriptionScreen extends SubscriptionScreen {
  readonly route = R.playerSubscription
  interval = () => this.tid('subscription-interval')
}

/** /profile — for a coach, the report-branding section that only ACADEMY unlocks. */
export class ProfileScreen extends Screen {
  readonly route = R.profile
  readonly readyTestId = 'coach-branding-section'
  branding = () => this.tid('coach-branding-section')
  brandingGate = () => this.within(this.branding(), 'feature-gate-overlay')
  brandingUpgrade = () => this.within(this.branding(), 'feature-gate-upgrade')
  brandingEdit = () => this.within(this.branding(), 'coach-branding-edit-btn')
}
