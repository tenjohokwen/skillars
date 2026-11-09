import { randomUUID } from 'node:crypto'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import { adultThread } from '../../src/data/threads.js'
import { t } from '../../src/i18n.js'
import {
  AdminAppealsScreen,
  AdminBookingsScreen,
  AdminCoachesScreen,
  AdminDisputesScreen,
  AdminFinanceScreen,
  AdminModerationScreen,
  AdminVideosScreen,
  CoachPublicProfileScreen,
  MessagingScreen,
  NotificationBell,
  ReliabilityScreen,
  VideosScreen,
} from '../../src/screens/index.js'

// R · Admin console, part 2: the admin actions whose value is the effect on the other side. A held message
// released to its recipient, a review published on the public profile, a suspension that cancels the family's
// booking, a dispute credit in the family's wallet, an appeal decision the appellant sees, force-complete and
// cancel only where they apply, blocking and restoring a video, and the finance page.
//
// The admin queues list oldest first: pageUntil walks the paginator to the row a spec created.

const UNCERTAIN = 'e2e-verdict-uncertain'
const words = (label: string) => `${label} ${randomUUID().slice(0, 8)}`

test.describe('R · Moderation', () => {
  test(
    'approving a held message releases it to the recipient; blocking needs a reason of at most 500 characters',
    scenario({
      id: 'ADM-04',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@admin', '@coach', '@player', '@admin-console', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // a published coach, a completed session, a thread and two moderated messages
      const { coach, player, conversationId } = await adultThread(provision)
      const released = words(`Released ${UNCERTAIN}`)
      const blocked = words(`Blocked ${UNCERTAIN}`)
      const releasedId = await provision.sendMessage(coach, conversationId, released)
      const blockedId = await provision.sendMessage(coach, conversationId, blocked)

      const recipient = await actor(asRole(player))
      const chat = await new MessagingScreen(recipient.page, suite).openConversation(conversationId)
      await expect(recipient.page.getByText(released)).toHaveCount(0)

      const admin = await actor('admin')
      const queue = await new AdminModerationScreen(admin.page, suite).open()
      await queue.pageUntil(queue.messageAlert(releasedId))
      await queue.messageAlert(releasedId).click()
      await expect(queue.messageContent()).toContainText(released)
      await queue.approve().click()
      await expect(queue.messageDialog()).toBeHidden()

      await recipient.page.reload()
      await chat.waitReady()
      await expect(chat.bubbleWith(released)).toBeVisible()

      // Blocking: no reason, no block; the reason is capped at 500 characters.
      await queue.open()
      await queue.pageUntil(queue.messageAlert(blockedId))
      await queue.messageAlert(blockedId).click()
      await queue.blockOpen().click()
      await expect(queue.blockSubmit()).toBeDisabled()
      await queue.blockReason().fill('x'.repeat(600))
      await expect(queue.blockReason()).toHaveValue('x'.repeat(500))
      await queue.blockReason().fill('Shares contact details outside the platform.')
      await queue.blockSubmit().click()
      await expect(queue.messageAlert(blockedId)).toHaveCount(0)
      await recipient.page.reload()
      await chat.waitReady()
      await expect(recipient.page.getByText(blocked)).toHaveCount(0)
    },
  )

  test(
    'publishing a held review puts it on the public profile; removing one keeps it off',
    scenario({
      id: 'ADM-05',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@admin', '@parent', '@guest', '@admin-console', '@slow'],
    }),
    async ({ actor, page, suite, provision }) => {
      test.slow() // two reviewable sessions
      const coach = await provision.coach()
      const kept = await provision.parent()
      const removed = await provision.parent()
      for (const family of [kept, removed]) {
        await provision.saveCard(family)
        await provision.reviewableSession(family, coach)
      }
      const keptBody = words(`Clear and patient ${UNCERTAIN}`)
      const removedBody = words(`Rude remarks ${UNCERTAIN}`)
      const keptId = await provision.submitReview(kept, coach, 5, keptBody)
      const removedId = await provision.submitReview(removed, coach, 1, removedBody)

      const admin = await actor('admin')
      const moderation = await new AdminModerationScreen(admin.page, suite).open()
      await moderation.reviewsTab().click()
      await moderation.pageUntil(moderation.review(keptId), 40, 'review-pagination')
      await moderation.review(keptId).click()
      await moderation.reviewApprove().click()
      await expect(moderation.reviewDialog()).toBeHidden()
      await moderation.pageUntil(moderation.review(removedId), 40, 'review-pagination')
      await moderation.review(removedId).click()
      await moderation.reviewBlockOpen().click()
      await moderation.reviewBlockReason().fill('Personal remarks, not about the session.')
      // The dialog closes once the block is saved; the queue then reloads without the review. A click that lands
      // while the stacked dialog is still opening is lost, so it is retried (a second block is answered 409, handled).
      await expect(async () => {
        await moderation.reviewBlockSubmit().click({ timeout: 2_000 })
        await expect(moderation.reviewBlockSubmit()).toBeHidden({ timeout: 5_000 })
      }).toPass({ timeout: 30_000 })
      await expect
        .poll(async () => (await provision.reviewOf(coach, removed))?.status)
        .toBe('BLOCKED')
      await expect(moderation.review(removedId)).toHaveCount(0)

      expect((await provision.reviewOf(coach, kept))?.status).toBe('APPROVED')
      expect((await provision.reviewOf(coach, removed))?.status).toBe('BLOCKED')
      const profile = await new CoachPublicProfileScreen(page, suite).open({
        coachId: coach.coachId!,
      })
      await expect(profile.reviewBodies().filter({ hasText: keptBody })).toHaveCount(1)
      await expect(profile.reviewBodies().filter({ hasText: removedBody })).toHaveCount(0)
    },
  )
})

test.describe('R · Enforcement and money', () => {
  test(
    "suspending a coach with 'cancel upcoming' cancels and refunds the family's booking; the coach can appeal",
    scenario({
      id: 'ADM-06',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@admin', '@coach', '@parent', '@admin-console', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      // Only this coach and family are touched, so it runs in the parallel lane (the plan's @destructive
      // is for effects beyond a spec's own data).
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const bookingId = await provision.confirmedBooking(parent, coach)

      const admin = await actor('admin')
      const coaches = await new AdminCoachesScreen(admin.page, suite).openCoach(coach.coachId!)
      await coaches.suspend().click()
      await expect(coaches.actionDialog()).toBeVisible()
      await coaches.actionReason().fill('Repeated no-shows reported by families.')
      // On by default (a full refund, no coach strike): left on.
      await expect(coaches.cancelUpcoming()).toHaveAttribute('aria-checked', 'true')
      await coaches.actionSubmit().click()
      await expect(coaches.actionDialog()).toBeHidden()

      await expect
        .poll(() => provision.bookingStatus(bookingId), { timeout: 30_000 })
        .toMatch(/^CANCELLED/)
      // Refunded in full to the family's credit wallet (the card charge itself stays captured).
      const price = await provision.agreedPrice(bookingId)
      await expect
        .poll(
          async () =>
            (await provision.walletLedger(parent)).some(
              (entry) => entry.type === 'BOOKING_REFUND' && Number(entry.amount) === price,
            ),
          { timeout: 30_000 },
        )
        .toBe(true)

      // The coach sees their profile read-only, with an appeal.
      const self = await actor(asRole(coach))
      await self.page.goto(suite.routing.toUrl('/profile'))
      await expect(self.page.getByTestId('coach-suspended-note')).toBeVisible()
      await expect(self.page.getByTestId('appeal-open-btn')).toBeVisible()
    },
  )

  test(
    "resolving a dispute with a full credit puts the session's price in the family's wallet",
    scenario({
      id: 'ADM-08',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@admin', '@parent', '@admin-console', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coach = await provision.coach()
      const parent = await provision.parent()
      await provision.saveCard(parent)
      const bookingId = await provision.completedSession(parent, coach)
      const price = await provision.agreedPrice(bookingId)
      const disputeId = await provision.raiseDispute(parent, bookingId)

      const admin = await actor('admin')
      const disputes = await new AdminDisputesScreen(admin.page, suite).open()
      await disputes.pageUntil(disputes.view(disputeId))
      await disputes.view(disputeId).click()
      await disputes.resolveOpen().click()
      await expect(disputes.resolveSubmit()).toBeDisabled()
      await disputes.resolution().click()
      await admin.page
        .getByRole('option', { name: t('disputes.resolutionValue.FULL_CREDIT'), exact: true })
        .click()
      await disputes.resolutionNote().fill('The coach did not run the planned session.')
      await disputes.resolveSubmit().click()
      await expect(disputes.resolveDialog()).toBeHidden()

      await expect
        .poll(async () =>
          (await provision.walletLedger(parent)).some((entry) => Number(entry.amount) === price),
        )
        .toBe(true)
    },
  )

  test(
    'deciding an appeal needs a note; an upheld one shows the coach its decision and note, an overturned strike goes',
    scenario({
      id: 'ADM-09',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@admin', '@coach', '@admin-console'],
    }),
    async ({ actor, suite, provision, mail }) => {
      const coach = await provision.coach()
      const since = new Date()
      const upheld = await provision.seedStrike(coach, 'COACH_NO_SHOW', 2)
      const overturned = await provision.seedStrike(coach, 'COACH_CANCELLATION_UNEXCUSED', 1)
      await provision.appealStrike(coach, upheld)
      await provision.appealStrike(coach, overturned)
      const note = 'The family confirmed the coach never arrived.'

      const admin = await actor('admin')
      const appeals = await new AdminAppealsScreen(admin.page, suite).open()
      for (const [strikeId, decide] of [
        [upheld, 'uphold'],
        [overturned, 'overturn'],
      ] as const) {
        const appeal = (await provision.appealOn(strikeId))!
        await appeals.open()
        await appeals.pageUntil(appeals.view(appeal.id))
        await appeals.view(appeal.id).click()
        await expect(appeals.uphold()).toBeDisabled()
        await expect(appeals.overturn()).toBeDisabled()
        await appeals.note().fill(note)
        await (decide === 'uphold' ? appeals.uphold() : appeals.overturn()).click()
        await expect(appeals.dialog()).toBeHidden()
      }
      await expect.poll(async () => (await provision.appealOn(upheld))?.status).toBe('UPHELD')
      await expect
        .poll(async () => (await provision.appealOn(overturned))?.status)
        .toBe('OVERTURNED')

      // The coach: the upheld strike stays, its chip says so and carries the note; the overturned one is gone.
      const self = await actor(asRole(coach))
      await new ReliabilityScreen(self.page, suite).open()
      expect((await provision.strikes(coach)).map((s) => s.reason)).toEqual(['COACH_NO_SHOW'])
      const chip = self.page.getByTestId('appeal-status-chip')
      await expect(chip).toHaveCount(1)
      await expect(chip).toContainText(t('appeals.status.UPHELD'))
      await chip.hover()
      await expect(self.page.locator('.q-tooltip')).toContainText(note)

      // Owner decision 2026-11-08: both decisions reach the coach, in the inbox and by email with the note.
      const bell = new NotificationBell(self.page)
      await bell.open()
      await expect(
        bell.items().filter({ hasText: t('notifications.types.APPEAL_UPHELD') }),
      ).toHaveCount(1)
      await expect(
        bell.items().filter({ hasText: t('notifications.types.APPEAL_OVERTURNED') }),
      ).toHaveCount(1)
      const email = await mail.waitForMessage({
        to: coach.email,
        subjectMatches: /appeal was successful/i,
        since,
        timeoutMs: 60_000,
      })
      expect(`${email.text} ${email.html}`).toContain(note)
    },
  )

  test(
    "force-complete is offered only for a stuck booking, and a live session can't be cancelled",
    scenario({
      id: 'ADM-10',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@admin', '@admin-console', '@slow'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow()
      const coach = await provision.coach()
      const parent = await provision.parent({ childAges: [11, 9] })
      await provision.saveCard(parent)
      const [first, second] = parent.children as [
        (typeof parent.children)[0],
        (typeof parent.children)[0],
      ]
      const upcoming = await provision.confirmedBooking(parent, coach, { playerId: first.playerId })
      const live = await provision.confirmedBooking(parent, coach, {
        playerId: second.playerId,
        weekday: 4,
      })
      await provision.setBooking(live, { startMinutesFromNow: -10, status: 'IN_PROGRESS' })
      const stuck = await provision.confirmedBooking(parent, coach, {
        playerId: first.playerId,
        weekday: 5,
      })
      await provision.setBooking(stuck, {
        startMinutesFromNow: -3 * 60,
        status: 'COMPLETED_PENDING_CONFIRMATION',
      })

      const admin = await actor('admin')
      const bookings = await new AdminBookingsScreen(admin.page, suite).open()

      // An upcoming booking: not stuck; an admin may cancel it.
      await bookings.find(upcoming)
      await expect(bookings.notStuck()).toBeVisible()
      await expect(bookings.forceComplete()).toHaveCount(0)
      await expect(bookings.cancel()).toBeVisible()

      // A session under way: no cancel, and the page says why.
      await bookings.find(live)
      await expect(bookings.liveHint()).toBeVisible()
      await expect(bookings.cancel()).toHaveCount(0)

      // A Quick Complete with no wrap-up: force-complete it.
      await bookings.find(stuck)
      await bookings.forceComplete().click()
      await bookings.note().fill('The coach confirmed the session took place.')
      await bookings.forceCompleteSubmit().click()
      await expect.poll(() => provision.bookingStatus(stuck)).toBe('COMPLETED')
    },
  )

  test(
    'blocking a video stops its uploader playing it, and says why; Restore brings Play back',
    scenario({
      id: 'ADM-11',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@admin', '@player', '@admin-console'],
    }),
    async ({ actor, suite, provision }) => {
      const player = await provision.adultPlayer()
      const title = `blocked-${randomUUID().slice(0, 6)}.mp4`
      const videoId = await provision.seedVideo({ ownerId: player.playerId!, title })
      const self = await actor(asRole(player))
      const videos = await new VideosScreen(self.page, suite).open()
      await expect(videos.item(title)).toBeVisible()

      const admin = await actor('admin')
      const console_ = await new AdminVideosScreen(admin.page, suite).open()
      await console_.findVideo(videoId)
      await console_.block().click()
      await console_.actionSubmit().click()
      await expect(console_.restore()).toBeVisible()

      // Still listed (the owner can delete it), but shown as blocked, with no Play: playback is refused.
      await self.page.reload()
      await videos.waitReady()
      await expect(videos.accessState(videos.item(title))).toHaveAttribute('data-state', 'BLOCKED')
      await expect(videos.play(videos.item(title))).toHaveCount(0)

      await console_.restore().click()
      await console_.actionSubmit().click()
      await expect(console_.block()).toBeVisible()
      await self.page.reload()
      await videos.waitReady()
      await expect(videos.play(videos.item(title))).toBeVisible()
      await expect(videos.accessState(videos.item(title))).toHaveCount(0)
    },
  )

  test(
    "finance: a reversed date range can't be applied; a valid one shows the overview; held payouts can be released",
    scenario({
      id: 'ADM-13',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@admin', '@admin-console'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const admin = await actor('admin')
      const finance = await new AdminFinanceScreen(admin.page, suite).open()

      await finance.from().fill('2026-10-31')
      await finance.to().fill('2026-10-01')
      await expect(finance.apply()).toBeDisabled()
      await finance.to().fill('2026-11-30')
      await expect(finance.apply()).toBeEnabled()
      await finance.apply().click()
      await expect(finance.overview()).toBeVisible()
      await expect(finance.metrics().first()).toBeVisible()

      await finance.coachId().fill(coach.coachId!)
      await finance.coachLookup().click()
      await expect(finance.coachRevenue()).toBeVisible()
      await finance.releaseHeld().click()
      await expect(finance.releaseDialog()).toBeVisible()
      await finance.releaseSubmit().click()
      await expect(finance.releaseDialog()).toBeHidden()
    },
  )
})
