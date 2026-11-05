import { Screen } from 'pitchside-core'
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
