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
}

/** Reached from the profile's "Buy sessions", which adds the ?playerId the page needs. */
export class PackPurchaseScreen extends Screen {
  readonly route = R.purchasePack
  readonly readyTestId = 'purchase-title'
  packOption = () => this.tid('pack-option')
  noPack = () => this.tid('pack-none')
  addCardBanner = () => this.tid('purchase-add-card-banner')
  addCard = () => this.tid('purchase-add-card-btn')
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
  empty = () => this.tid('packs-empty')
}
