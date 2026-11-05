import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { t } from '../../src/i18n.js'
import {
  BookingRequestScreen,
  CoachPublicProfileScreen,
  CoachRequestsScreen,
  CreditStatementScreen,
  ParentBookingsScreen,
  WalletScreen,
} from '../../src/screens/index.js'

// G · Credit wallet. Credit is seeded straight into the append-only ledger (provision.seedWalletCredit)
// except in MNY-15, where the refund under test must come from the app itself.

const euros = (amount: number) => `€${amount.toFixed(2)}`

test.describe('G · Credit wallet', () => {
  test(
    "the wallet shows the cash balance, matching the statement's running total, and a cash-out control",
    scenario({
      id: 'MNY-13',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.seedWalletCredit(parent, 30)
      await provision.seedWalletCredit(parent, 12.5)

      const family = await actor(asRole(parent))
      const wallet = await new WalletScreen(family.page, suite).open()
      await expect(wallet.balance()).toHaveText(euros(42.5))
      // All of it is cashable, so no split is shown.
      await expect(wallet.split()).toHaveCount(0)
      await expect(wallet.cashOutAmount()).toBeVisible()
      await expect(wallet.cashOut()).toBeVisible()

      // The statement lists newest first: its top running balance is the wallet balance.
      const statement = await new CreditStatementScreen(family.page, suite).open()
      await expect(statement.rows()).toHaveCount(2)
      await expect(statement.balance(statement.rows().first())).toHaveText(euros(42.5))
      await expect(statement.balance(statement.rows().last())).toHaveText(/€(30\.00|12\.50)$/)
    },
  )

  test(
    'a cash-out above the cashable amount, of zero or negative, is refused at the field',
    scenario({
      id: 'MNY-14',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.seedWalletCredit(parent, 20)
      await provision.seedWalletCredit(parent, 15, { spendOnly: true })

      const family = await actor(asRole(parent))
      const wallet = await new WalletScreen(family.page, suite).open()
      await expect(wallet.balance()).toHaveText(euros(35))

      // The cap is the cashable part (20), not the balance (35).
      for (const refused of ['20.01', '35', '0', '-5']) {
        await wallet.cashOutAmount().fill(refused)
        await expect(wallet.cashOut(), `cash-out of ${refused}`).toBeDisabled()
      }
      await wallet.cashOutAmount().fill('20')
      await expect(wallet.cashOut()).toBeEnabled()
    },
  )

  test(
    'with spend-only credit, the wallet shows the cashable and booking-only parts separately',
    scenario({
      id: 'MNY-21',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      const parent = await provision.parent()
      await provision.seedWalletCredit(parent, 20)
      await provision.seedWalletCredit(parent, 15, { spendOnly: true })

      const family = await actor(asRole(parent))
      const wallet = await new WalletScreen(family.page, suite).open()
      await expect(wallet.balance()).toHaveText(euros(35))
      await expect(wallet.split()).toBeVisible()
      await expect(wallet.cashable()).toHaveText(
        t('payment.credits.cashableBalance', { amount: '20.00' }),
      )
      await expect(wallet.spendOnly()).toHaveText(
        t('payment.credits.spendOnlyBalance', { amount: '15.00' }),
      )
    },
  )

  test(
    'a refund lands in the wallet as a new entry and never edits an earlier one',
    scenario({
      id: 'MNY-15',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@money'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const price = 55
      const coachName = `${provision.uniqueCity()} Coaching`
      const coach = await provision.coach({ displayName: coachName, perSessionPrice: price })
      const parent = await provision.parent()
      await provision.saveCard(parent)
      await provision.seedWalletCredit(parent, 10)
      const [earlier] = await provision.walletLedger(parent)

      // A per-session booking next week, accepted (and so paid) by the coach.
      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(3),
      )
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(family.page, suite).waitReady()
      await request.submit().click()
      await new ParentBookingsScreen(family.page, suite).waitReady()
      const coachSide = await actor(asRole(coach))
      const inbox = await new CoachRequestsScreen(coachSide.page, suite).open()
      await inbox.accept(inbox.request(parent.children[0]!.name)).click()
      await expect(inbox.request(parent.children[0]!.name)).toHaveCount(0)

      // Cancelled more than 24h ahead: the session's value comes back as wallet credit.
      await family.page.reload()
      const bookings = await new ParentBookingsScreen(family.page, suite).waitReady()
      const booking = bookings.booking(coachName)
      await expect(bookings.status(booking)).toHaveText(
        new RegExp(
          `^(${t('booking.requests.statusConfirmed')}|${t('booking.requests.statusUpcoming')})$`,
        ),
      )
      const charged = await provision.walletLedger(parent)
      await bookings.cancel(booking).click()
      await bookings.confirmCancel().click()
      await expect(bookings.status(booking)).toHaveText(t('booking.requests.statusCancelledParent'))

      // The refund is written by the outbox, so wait for it to land.
      await expect
        .poll(async () => (await provision.walletLedger(parent)).length, { timeout: 30_000 })
        .toBe(charged.length + 1)
      const after = await provision.walletLedger(parent)
      const refund = after.at(-1)!
      expect(refund.type).toBe('BOOKING_REFUND')
      expect(Number(refund.amount)).toBeGreaterThan(0)
      // Append-only: every earlier row is byte-identical, the seeded credit included.
      expect(after.slice(0, charged.length).map((r) => r.row)).toEqual(charged.map((r) => r.row))
      expect(after[0]!.row).toBe(earlier!.row)

      // And the statement shows it as its own line on top.
      const statement = await new CreditStatementScreen(family.page, suite).open()
      await expect(statement.rows()).toHaveCount(after.length)
      await expect(statement.amount(statement.rows().first())).toHaveText(
        `+€${Number(refund.amount).toFixed(2)}`,
      )
    },
  )
})
