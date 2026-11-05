import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import {
  BookingRequestScreen,
  CoachPublicProfileScreen,
  CoachRequestsScreen,
  ParentBookingsScreen,
  ParentReceiptScreen,
} from '../../src/screens/index.js'

// G · Receipts. The family's receipt; the coach's (a released payout's) is covered in suite I.

test.describe('G · Receipts', () => {
  test(
    "a paid booking's receipt is reachable from the bookings list, with the amounts charged",
    scenario({
      id: 'MNY-16',
      priority: 'P1',
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
      const child = parent.children[0]!

      // A per-session booking, accepted: the card is charged, so a receipt exists.
      const family = await actor(asRole(parent))
      const profile = await new CoachPublicProfileScreen(family.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(3),
      )
      await profile.primaryCta().click()
      await (await new BookingRequestScreen(family.page, suite).waitReady()).submit().click()
      const bookings = await new ParentBookingsScreen(family.page, suite).waitReady()
      // Not before the charge: a requested booking has no receipt.
      await expect(bookings.receipt(bookings.booking(coachName))).toHaveCount(0)
      const coachSide = await actor(asRole(coach))
      const inbox = await new CoachRequestsScreen(coachSide.page, suite).open()
      await inbox.accept(inbox.request(child.name)).click()
      await expect(inbox.request(child.name)).toHaveCount(0)

      // Family: from the bookings list to the receipt.
      await family.page.reload()
      await bookings.waitReady()
      await bookings.receipt(bookings.booking(coachName)).click()
      const receipt = await new ParentReceiptScreen(family.page, suite).waitReady()
      await expect(receipt.coach()).toHaveText(coachName)
      await expect(receipt.player()).toHaveText(child.name.split(' ')[0]!)
      await expect(receipt.gross()).toHaveText(`€${price.toFixed(2)}`)
      await expect(receipt.creditUsed()).toHaveCount(0)
      await expect(receipt.total()).toHaveText(`€${price.toFixed(2)}`)

      // The coach's receipt is the payout's, which exists only once the payout is released after the
      // session and its hold (RevenueReportingService); it is covered with session delivery (suite I).
    },
  )
})
