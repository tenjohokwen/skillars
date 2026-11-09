import { Screen } from 'pitchside-core'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'
import { ListScreen } from './ListScreen.js'

// Discovery and pack purchase (test-id wave W3). The coach public profile lives in index.ts.

export class MarketplaceScreen extends Screen {
  readonly route = R.marketplace
  readonly readyTestId = 'marketplace-city-input'
  city = () => this.tid('marketplace-city-input')
  search = () => this.tid('marketplace-search-btn')
  prompt = () => this.tid('marketplace-prompt')
  empty = () => this.tid('marketplace-empty')
  resultsCount = () => this.tid('marketplace-results-count')
  cards = () => this.tid('coach-card')
  /** The card whose name is exactly `displayName`. */
  card = (displayName: string): Locator =>
    this.cards().filter({
      has: this.page.getByTestId('coach-card-name').getByText(displayName, { exact: true }),
    })
  cardPrice = (card: Locator) => this.within(card, 'coach-card-price')
  cardLocation = (card: Locator) => this.within(card, 'coach-card-location')

  async searchCity(city: string): Promise<void> {
    await this.city().fill(city)
    await this.search().click()
  }

  district = () => this.tid('marketplace-district')
  ageGroup = () => this.tid('marketplace-age-group')
  language = () => this.tid('marketplace-language')
  sort = () => this.tid('marketplace-sort')
  loadMore = () => this.tid('marketplace-load-more')
  loading = () => this.tid('marketplace-loading')
  clearFilters = () => this.tid('marketplace-clear-filters')

  /** Choose a filter or sort option by its visible text; the page refreshes the results on change. */
  async choose(select: Locator, label: string): Promise<void> {
    await select.click()
    await this.page.getByRole('option', { name: label, exact: true }).click()
    await this.page.keyboard.press('Escape')
  }
}

/** Reached from the profile's "Buy sessions", which adds the ?playerId the page needs. */
export class PackPurchaseScreen extends Screen {
  readonly route = R.purchasePack
  readonly readyTestId = 'purchase-title'
  packOption = () => this.tid('pack-option')
  noPack = () => this.tid('pack-none')
  addCardBanner = () => this.tid('purchase-add-card-banner')
  addCard = () => this.tid('purchase-add-card-btn')
  /** The inline add-card dialog and, inside it, the card form's save button. */
  cardDialog = () => this.tid('purchase-card-dialog')
  saveCard = () => this.tid('payment-card-save-btn')
  confirm = () => this.tid('purchase-confirm')
  error = () => this.tid('purchase-error')
  /** Offered in the error banner when the card was declined. */
  updateCard = () => this.tid('purchase-update-card')
}

export class PacksDashboardScreen extends ListScreen {
  readonly route = R.childPacks
  readonly readyTestId = 'pack-card'
  cards = () => this.tid('pack-card')
  protected rows = () => this.cards()
  /** The pack held with the coach whose display name is `coachName`. */
  card = (coachName: string): Locator =>
    this.cards().filter({
      has: this.page.getByTestId('pack-coach-name').getByText(coachName, { exact: true }),
    })
  credits = (card: Locator) => this.within(card, 'pack-credits')
  /** "Expires {date}"; absent on a withdrawn pack. */
  expiry = (card: Locator) => this.within(card, 'pack-expiry')
  status = (card: Locator) => this.within(card, 'pack-status-badge')
  pausedUntil = (card: Locator) => this.within(card, 'pack-paused-until')
  frozenNotice = (card: Locator) => this.within(card, 'pack-frozen-notice')
  /** Why a withdrawn pack was withdrawn. */
  revokedReason = (card: Locator) => this.within(card, 'pack-revoked-reason')
  pauseScheduled = (card: Locator) => this.within(card, 'pack-pause-scheduled')
  /** Offered only on an active pack with credits that has never been paused. */
  pause = (card: Locator) => this.within(card, 'pack-pause-cta')
  /** Shown instead of the pause control once the pack has used its one pause. */
  alreadyPaused = (card: Locator) => this.within(card, 'pack-already-paused')
  empty = () => this.tid('packs-empty')

  pauseDialog = () => this.tid('pack-pause-dialog')
  pauseStart = () => this.tid('pack-pause-start')
  pauseDays = () => this.tid('pack-pause-days')
  /** One row per booking the pause would cancel, listed after a first, unapplied attempt. */
  pauseConflicts = () => this.tid('pack-pause-conflict')
  /** "Pause pack", or "Cancel sessions & apply pause" once conflicts are listed. */
  confirmPause = () => this.tid('pack-pause-confirm')
}
