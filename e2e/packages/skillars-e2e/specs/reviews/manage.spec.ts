import { randomUUID } from 'node:crypto'
import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { CoachAccount, Provisioner } from '../../src/data/provisioner.js'
import { nextWeekSlot } from '../../src/data/slots.js'
import { t } from '../../src/i18n.js'
import {
  BookingRequestScreen,
  CoachPublicProfileScreen,
  CoachReviewsScreen,
} from '../../src/screens/index.js'

// M · Reviews, part 2: the coach's side (responding, the empty page), sorting and paging, flagging,
// related-party reviews, removed reviews and their appeal, and booking your own coaching.

const words = (label: string) => `${label} ${randomUUID().slice(0, 8)}`
const UNSAFE = 'e2e-verdict-unsafe'
const MODERATED = { timeout: 30_000 }

// Every review spec completes and ages a session and waits on moderated submits.
test.beforeEach(() => test.slow())

function openProfile(
  page: Page,
  suite: ConstructorParameters<typeof CoachPublicProfileScreen>[1],
  coach: CoachAccount,
) {
  return new CoachPublicProfileScreen(page, suite).open({ coachId: coach.coachId! })
}

/** A family who has reviewed the coach through the dialog. */
async function reviewed(provision: Provisioner, coach: CoachAccount, body: string) {
  const parent = await provision.parent()
  await provision.saveCard(parent)
  const bookingId = await provision.reviewableSession(parent, coach)
  return { parent, bookingId, body }
}

async function writeReview(profile: CoachPublicProfileScreen, stars: number, body: string) {
  await profile.writeReview().click()
  await profile.dialogStars(stars).click()
  await profile.dialogBody().fill(body)
  await profile.dialogSubmit().click()
  await expect(profile.dialogSubmit()).toBeHidden(MODERATED)
}

test.describe("M · The coach's side", () => {
  test(
    'a coach responds to a review from their reviews page, and the response shows publicly',
    scenario({
      id: 'REV-10',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@coach', '@parent', '@reviews'],
    }),
    async ({ actor, page, suite, provision }) => {
      test.slow() // a completed, aged session, a moderated review and the coach's reply
      const coach = await provision.coach()
      const body = words('Loved the sessions')
      const { parent } = await reviewed(provision, coach, body)
      const family = await actor(asRole(parent))
      await writeReview(await openProfile(family.page, suite, coach), 5, body)

      const coachSide = await actor(asRole(coach))
      const mine = await new CoachReviewsScreen(coachSide.page, suite).open()
      const review = mine.review(body)
      const reply = words('Thank you, see you next week')
      await mine.responseInput(review).fill(reply)
      await mine.responseSubmit(review).click()
      await expect(mine.response(review)).toContainText(reply)
      await expect(mine.responseInput(review)).toHaveCount(0)

      const guest = await openProfile(page, suite, coach)
      await expect(guest.reviews().filter({ hasText: body })).toContainText(reply)
    },
  )

  test(
    'a coach with no reviews sees the empty state',
    scenario({
      id: 'REV-11',
      priority: 'P2',
      type: 'edge',
      tags: ['@regression', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const self = await actor(asRole(coach))
      const mine = await new CoachReviewsScreen(self.page, suite).open()
      await expect(mine.empty()).toHaveText(t('marketplace.noReviewsYet'))
      await expect(mine.reviews()).toHaveCount(0)
    },
  )
})

test.describe('M · Reading reviews', () => {
  test(
    'the sort appears only with more than one review, reorders the list, and pages with Load more',
    scenario({
      id: 'REV-12',
      priority: 'P2',
      type: 'happy',
      tags: ['@regression', '@guest', '@reviews'],
    }),
    async ({ page, suite, provision }) => {
      // One review: no sort.
      const single = await provision.coach()
      await provision.seedReviewList(single, [{ rating: 4, body: words('Only one') }])
      const one = await openProfile(page, suite, single)
      await expect(one.reviews()).toHaveCount(1)
      await expect(one.sortSelect()).toHaveCount(0)

      // Twelve: newest first, ten to a page. The lowest rating is the oldest.
      const coach = await provision.coach()
      const list: { rating: 1 | 2 | 3 | 4 | 5; body: string }[] = Array.from(
        { length: 12 },
        (_, i) => ({
          rating: i === 11 ? 1 : i === 5 ? 5 : 3,
          body: words(`Review ${String(i + 1).padStart(2, '0')}`),
        }),
      )
      await provision.seedReviewList(coach, list)
      const profile = await openProfile(page, suite, coach)
      // A hash change inside the loaded app is not a reliable navigation: reload the new profile.
      await page.reload()
      await expect(profile.reviews()).toHaveCount(10)
      await expect(profile.reviewBodies().first()).toHaveText(list[0]!.body)
      await expect(profile.sortSelect()).toBeVisible()

      await profile.sortSelect().click()
      await page.getByRole('option', { name: t('reviews.sortLowest'), exact: true }).click()
      await expect(profile.reviewBodies().first()).toHaveText(list[11]!.body)
      await profile.sortSelect().click()
      await page.getByRole('option', { name: t('reviews.sortHighest'), exact: true }).click()
      await expect(profile.reviewBodies().first()).toHaveText(list[5]!.body)

      await profile.loadMore().click()
      await expect(profile.reviews()).toHaveCount(12)
      await expect(profile.loadMore()).toHaveCount(0)
    },
  )

  test(
    "a parent flags another family's review with a reason, once",
    scenario({
      id: 'REV-13',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const body = words('Suspicious praise')
      const { parent: author } = await reviewed(provision, coach, body)
      const authorSide = await actor(asRole(author))
      const own = await openProfile(authorSide.page, suite, coach)
      await writeReview(own, 5, body)
      // No flag on your own review.
      await expect(own.flag(own.reviews().filter({ hasText: body }))).toHaveCount(0)

      const other = await provision.parent()
      const self = await actor(asRole(other))
      const profile = await openProfile(self.page, suite, coach)
      const review = profile.reviews().filter({ hasText: body })
      await profile.flag(review).click()
      await expect(profile.flagSubmit()).toBeDisabled()
      await profile.flagReason().click()
      await self.page
        .getByRole('option', { name: t('reviews.flagReason.FAKE_REVIEW'), exact: true })
        .click()
      await expect(profile.flagSubmit()).toBeEnabled()
      await profile.flagSubmit().click()
      await expect(profile.notification(t('reviews.flagSuccess'))).toBeVisible()

      // A second flag of the same review is refused.
      await profile.flag(review).click()
      await profile.flagReason().click()
      await self.page
        .getByRole('option', { name: t('reviews.flagReason.OTHER'), exact: true })
        .click()
      await profile.flagSubmit().click()
      await expect(profile.notification(t('reviews.alreadyFlagged'))).toBeVisible()
    },
  )
})

test.describe('M · Reviews kept back', () => {
  test(
    'a related-party reviewer is told their review is not published or counted, and nobody sees it',
    scenario({
      id: 'REV-14',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, page, suite, provision }) => {
      const coach = await provision.coach()
      const body = words('My friend is great')
      const { parent, bookingId } = await reviewed(provision, coach, body)
      await provision.markRelatedParty(bookingId, coach, parent)

      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      await writeReview(profile, 5, body)
      await expect(profile.relatedPartyHidden()).toHaveText(t('reviews.relatedPartyHidden'))
      await expect(profile.reviewBodies().filter({ hasText: body })).toHaveCount(0)
      await expect(profile.rating()).toHaveText(t('marketplace.noReviewsYet'))

      const guest = await openProfile(page, suite, coach)
      await expect(guest.name()).toBeVisible()
      await expect(guest.reviewBodies().filter({ hasText: body })).toHaveCount(0)
      await expect(guest.rating()).toHaveText(t('marketplace.noReviewsYet'))
    },
  )

  test(
    'a review removed by moderation says so and can be appealed, once',
    scenario({
      id: 'REV-15',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const body = words(`Strong words. ${UNSAFE}`)
      const { parent } = await reviewed(provision, coach, body)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      await writeReview(profile, 1, body)

      await expect(profile.myReviewBlocked()).toContainText(t('reviews.blocked'))
      await expect(profile.editReview()).toHaveCount(0)
      await profile.appealOpen().click()
      await expect(profile.appealSubmit()).toBeDisabled()
      await profile.appealReason().fill('This was a fair account of the session.')
      await profile.appealSubmit().click()
      await expect(profile.notification(t('appeals.submitSuccess'))).toBeVisible()

      // One appeal: the button is gone, the status shows instead, also after a reload.
      await self.page.reload()
      // toContainText: the chip's text also carries its icon's ligature name.
      await expect(profile.appealStatus()).toContainText(t('appeals.status.OPEN'))
      await expect(profile.appealOpen()).toHaveCount(0)
    },
  )

  test(
    'a coach who is also a parent cannot book their own sessions',
    scenario({
      id: 'REV-17',
      priority: 'P2',
      type: 'neg',
      tags: ['@regression', '@coach', '@parent', '@reviews', '@booking'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()
      const family = await provision.parent()
      await provision.coachAsParentOf(coach, family.children[0]!)
      await provision.saveCard(coach)

      const self = await actor(asRole(coach))
      const profile = await new CoachPublicProfileScreen(self.page, suite).openWithSlot(
        coach.coachId!,
        nextWeekSlot(3),
      )
      await profile.primaryCta().click()
      const request = await new BookingRequestScreen(self.page, suite).waitReady()
      await request.submit().click()
      await expect(
        self.page
          .locator('.q-notification')
          .filter({ hasText: t('booking.errors.selfBookingNotAllowed') }),
      ).toBeVisible()
    },
  )
})
