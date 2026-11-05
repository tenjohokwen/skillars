import { Screen } from 'pitchside-core'
import { ListScreen } from './ListScreen.js'
import type { Locator } from '@playwright/test'
import { R } from '../routes.js'

// Wallet and statement (test-id wave W4). The pack dashboard is PacksDashboardScreen in marketplace.ts.

/** The family's credit wallet: balance, the cashable / spend-only split, and cash-out. */
export class WalletScreen extends Screen {
  readonly route = R.wallet
  readonly readyTestId = 'wallet-balance'
  balance = () => this.tid('wallet-balance')
  /** Present only while part of the balance is spend-only. */
  split = () => this.tid('wallet-split')
  cashable = () => this.tid('wallet-cashable')
  spendOnly = () => this.tid('wallet-spend-only')
  cashOutAmount = () => this.tid('wallet-cashout-amount')
  cashOut = () => this.tid('wallet-cashout-btn')
}

/** The ledger, newest entry first, each with its running balance. */
export class CreditStatementScreen extends Screen {
  readonly route = R.creditStatement
  readonly readyTestId = 'credit-statement'
  table = () => this.tid('credit-statement')
  /** Data rows only (Quasar's "no data" row has no amount cell). */
  rows = (): Locator =>
    this.table()
      .locator('tbody tr')
      .filter({ has: this.page.getByTestId('statement-amount') })
  amount = (row: Locator) => this.within(row, 'statement-amount')
  balance = (row: Locator) => this.within(row, 'statement-balance')
}

/** A booking receipt. The parent's and the coach's views share these ids; the amounts differ. */
abstract class ReceiptScreen extends Screen {
  readonly readyTestId = 'receipt'
  coach = () => this.tid('receipt-coach')
  player = () => this.tid('receipt-player')
  gross = () => this.tid('receipt-gross')
}

/** The family's receipt: what the card was charged, any wallet credit used, and the total. */
export class ParentReceiptScreen extends ReceiptScreen {
  readonly route = R.parentReceipt
  creditUsed = () => this.tid('receipt-credit-used')
  total = () => this.tid('receipt-total')
}

/** The coach's receipt: gross, the platform commission, and the net. */
export class CoachReceiptScreen extends ReceiptScreen {
  readonly route = R.coachReceipt
  commission = () => this.tid('receipt-commission')
  net = () => this.tid('receipt-net')
}

/** The coach's pack tier page, and below it the packs this coach has sold (with Extend). */
export class CoachPackTiersScreen extends ListScreen {
  readonly route = R.packTiers
  readonly readyTestId = 'sold-pack'
  soldPacks = () => this.tid('sold-pack')
  protected rows = () => this.soldPacks()
  empty = () => this.tid('sold-packs-empty')
  /** The sold pack for the player named `playerName`. */
  soldPack = (playerName: string): Locator =>
    this.soldPacks().filter({
      has: this.page.getByTestId('sold-pack-player').getByText(playerName, { exact: true }),
    })
  expiry = (pack: Locator) => this.within(pack, 'sold-pack-expiry')
  expiringSoon = (pack: Locator) => this.within(pack, 'sold-pack-expiring-soon')
  alreadyExtended = (pack: Locator) => this.within(pack, 'sold-pack-already-extended')
  status = (pack: Locator) => this.within(pack, 'sold-pack-status')
  extend = (pack: Locator) => this.within(pack, 'extend-pack')
  extendError = () => this.tid('extend-error')
}
