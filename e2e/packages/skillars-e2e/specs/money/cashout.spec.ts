import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { WalletScreen } from '../../src/screens/index.js'
import { refundsOn } from '../../src/stubs/wiremock.js'

// G · Cash-out. Wallet credit goes back to the card charges that funded it, as Stripe refunds, less the
// processing fee (CashOutService). The credit is seeded as card-booking refunds naming their charge
// (provision.seedWalletCredit with `fundedBy`); the stack's WireMock reads each charge's refundable
// amount from its id and refuses refunds on charges marked to reject them. Each payer has a card on
// file, as anyone refunded from a card booking does: the cash-out debit needs the Stripe customer.

const euros = (amount: number) => `€${amount.toFixed(2)}`
const cents = (amount: number) => Math.round(amount * 100)

/** The processing fee a cash-out of `amount` pays, from the environment's config (CashOutService). */
async function cashOutFee(provision: Provisioner, amount: number): Promise<number> {
  const rate = Number(await provision.configValue('payment.stripe.feeRate'))
  const fixed = Number(await provision.configValue('payment.stripe.feeFixed'))
  return cents(amount * rate + fixed) / 100
}

/** The wallet balance the ledger adds up to: what the page must show, success or not. */
async function ledgerBalance(provision: Provisioner, account: { userId: string }) {
  const rows = await provision.walletLedger(account)
  return rows.reduce((sum, row) => sum + cents(Number(row.amount)), 0) / 100
}

test.describe('G · Cash-out', () => {
  test(
    'a self-registered adult player cashes out their own wallet to their card',
    scenario({
      id: 'MNY-23',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@player', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      await provision.saveCard(player)
      const { paymentIntent } = await provision.seedWalletCredit(player, 30, {
        fundedBy: { capturedCents: 3000 },
      })

      const self = await actor(asRole(player))
      const wallet = await new WalletScreen(self.page, suite).open()
      await expect(wallet.balance()).toHaveText(euros(30))
      await wallet.cashOutAmount().fill('25')
      await wallet.cashOut().click()
      await expect(
        self.page.getByText(t('payment.credits.cashoutSuccess', { amount: '25.00' })),
      ).toBeVisible()
      await expect(wallet.balance()).toHaveText(euros(5))
      await expect(wallet.cashOutError()).toHaveCount(0)

      // One refund, onto the charge the credit came from, of the amount less the fee.
      const fee = await cashOutFee(provision, 25)
      expect(await refundsOn([paymentIntent!])).toEqual([
        { paymentIntent, amountCents: cents(25 - fee) },
      ])
      const debits = (await provision.walletLedger(player)).slice(1)
      expect(debits.map((row) => row.type).sort()).toEqual(['CASH_OUT_DEBIT', 'STRIPE_FEE_DEBIT'])
      expect(debits.reduce((sum, row) => sum + cents(Number(row.amount)), 0)).toBe(-cents(25))
    },
  )

  test(
    'a cash-out larger than the funding charges can take back is refused, and nothing moves',
    scenario({
      id: 'MNY-22',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      // €50 of cashable credit, funded by a charge that can take back only €20.
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const { paymentIntent } = await provision.seedWalletCredit(parent, 50, {
        fundedBy: { capturedCents: 2000 },
      })
      const before = await provision.walletLedger(parent)

      const family = await actor(asRole(parent))
      const wallet = await new WalletScreen(family.page, suite).open()
      await wallet.cashOutAmount().fill('50')
      await wallet.cashOut().click()
      await expect(wallet.cashOutError()).toHaveText(t('payment.credits.cashOutExceedsRefundable'))
      await expect(wallet.balance()).toHaveText(euros(50))

      expect(await refundsOn([paymentIntent!])).toEqual([])
      expect(await provision.walletLedger(parent)).toEqual(before)
    },
  )

  test(
    'a cash-out split across two charges, one of which Stripe refuses, says so and shows the real balance',
    scenario({
      id: 'MNY-22',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      // Two €20 credits from two €20 charges; Stripe refuses refunds on one of them.
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const refused = await provision.seedWalletCredit(parent, 20, {
        fundedBy: { capturedCents: 2000, rejectsRefund: true },
      })
      const accepted = await provision.seedWalletCredit(parent, 20, {
        fundedBy: { capturedCents: 2000 },
      })

      const family = await actor(asRole(parent))
      const wallet = await new WalletScreen(family.page, suite).open()
      await expect(wallet.balance()).toHaveText(euros(40))
      await wallet.cashOutAmount().fill('30')
      await wallet.cashOut().click()
      await expect(wallet.cashOutError()).toHaveText(t('payment.credits.cashOutPartiallyRefunded'))

      // The net amount was split over both charges; each leg was sent, one was refused.
      const fee = cents(await cashOutFee(provision, 30))
      const net = cents(30) - fee
      const refunds = await refundsOn([refused.paymentIntent!, accepted.paymentIntent!])
      expect(refunds).toHaveLength(2)
      expect(refunds.reduce((sum, refund) => sum + refund.amountCents, 0)).toBe(net)
      const leg = (pi: string | null) => refunds.find((r) => r.paymentIntent === pi)!.amountCents
      const [kept, restored] = [leg(accepted.paymentIntent), leg(refused.paymentIntent)]

      // The refused leg's credit came back; the refunded one's is gone, with its share of the fee
      // (pro rata, rounded down, unless it was the last leg, which takes the remainder: planLegs).
      const keptFee = [Math.floor((fee * kept) / net), fee - Math.floor((fee * restored) / net)]
      const balance = await ledgerBalance(provision, parent)
      expect(keptFee.map((share) => cents(40) - kept - share)).toContain(cents(balance))
      // The page refreshed the balance after the failure rather than keeping the old one.
      await expect(wallet.balance()).toHaveText(euros(balance))
    },
  )
})
