import { randomUUID } from 'node:crypto'
import type { Page } from '@playwright/test'
import { expect, scenario, test } from '../../src/app.js'
import { asRole } from '../../src/data/accounts.js'
import type { CoachAccount, ParentAccount, Provisioner } from '../../src/data/provisioner.js'
import { t } from '../../src/i18n.js'
import { CoachPublicProfileScreen, MarketplaceScreen } from '../../src/screens/index.js'

// M · Reviews, part 1: writing, editing and who sees what.
//
// Moderation runs inside the submit request (the Gemini stub answers by marker text): plain text is
// SAFE and approved at once, "e2e-verdict-uncertain" holds it for review (the pending state), and
// "e2e-verdict-unsafe" blocks it. A session qualifies a review only a week after it completes
// (reviews.minSessionAgeDays), so specs age it (provision.reviewableSession).

const words = (label: string) => `${label} ${randomUUID().slice(0, 8)}`
const UNCERTAIN = 'e2e-verdict-uncertain'
const UNSAFE = 'e2e-verdict-unsafe'

/** A coach (in their own city, so search finds only them) and a family with a reviewable session. */
async function reviewer(provision: Provisioner) {
  const city = provision.uniqueCity()
  const displayName = `${city} Football`
  const coach = await provision.coach({ city, displayName })
  const parent = await provision.parent()
  await provision.saveCard(parent)
  const bookingId = await provision.reviewableSession(parent, coach)
  return { coach, parent, bookingId, city, displayName }
}

function openProfile(
  page: Page,
  suite: ConstructorParameters<typeof CoachPublicProfileScreen>[1],
  coach: CoachAccount,
) {
  return new CoachPublicProfileScreen(page, suite).open({ coachId: coach.coachId! })
}

/** Writes a review through the dialog. */
async function write(profile: CoachPublicProfileScreen, stars: number, body: string) {
  await profile.writeReview().click()
  await profile.dialogStars(stars).click()
  await profile.dialogBody().fill(body)
  await submit(profile)
}

/**
 * Opens the edit dialog and waits until it holds the current review (it opens with a transition,
 * and a click during it can be lost).
 */
async function openEdit(profile: CoachPublicProfileScreen, current: string) {
  await profile.editReview().click()
  await expect(profile.dialogBody()).toHaveValue(current)
  await expect(profile.dialogSubmit()).toBeEnabled()
}

/** Clicks Save and waits for the request to go out (once more if the first click was lost). */
async function save(profile: CoachPublicProfileScreen, page: Page) {
  const sent = () =>
    page.waitForRequest((r) => r.method() === 'PATCH' && r.url().includes('/api/reviews/'), {
      timeout: 5_000,
    })
  const first = sent()
  await profile.dialogSubmit().click()
  if (!(await first.then(() => true).catch(() => false))) {
    const second = sent()
    await profile.dialogSubmit().click()
    await second
  }
}

/** Moderation runs inside the submit request, so the dialog can take a while to close. */
const MODERATED = { timeout: 30_000 }
async function submit(profile: CoachPublicProfileScreen) {
  await profile.dialogSubmit().click()
  await expect(profile.dialogSubmit()).toBeHidden(MODERATED)
}

test.describe('M · Writing a review', () => {
  test(
    'a parent who has had a session writes a rated review, and it waits for moderation',
    scenario({
      id: 'REV-01',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)

      const body = words(`Patient and clear. ${UNCERTAIN}`)
      await write(profile, 4, body)
      await expect(profile.myReviewBody()).toHaveText(body)
      await expect(profile.myReviewRating()).toHaveAttribute('data-rating', '4')
      await expect(profile.myReviewPending()).toHaveText(t('reviews.pendingModeration'))
      // Held, so not public yet.
      await expect(profile.reviewBodies().filter({ hasText: body })).toHaveCount(0)
      expect((await provision.reviewOf(coach, parent))?.status).toBe('UNDER_REVIEW')
    },
  )

  test(
    'a guest sees no way to write a review',
    scenario({
      id: 'REV-02',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@guest', '@reviews'],
    }),
    async ({ page, suite, provision }) => {
      const coach = await provision.coach()
      const profile = await openProfile(page, suite, coach)
      await expect(profile.name()).toBeVisible()
      await expect(profile.writeSection()).toHaveCount(0)
      await expect(profile.writeReview()).toHaveCount(0)
    },
  )

  test(
    'someone who already reviewed the coach sees their review and Edit, not Write',
    scenario({
      id: 'REV-03',
      priority: 'P1',
      type: 'edge',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      const body = words('Great sessions')
      await write(profile, 5, body)
      await expect(profile.myReviewBody()).toHaveText(body)

      await self.page.reload()
      await expect(profile.myReviewBody()).toHaveText(body)
      await expect(profile.editReview()).toBeVisible()
      await expect(profile.writeReview()).toHaveCount(0)
    },
  )

  test(
    'an edit needs a new session since the last one and a cooldown, then updates in place and is moderated again',
    scenario({
      id: 'REV-04',
      priority: 'P1',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      test.slow() // three submits, each moderated in the request, and a second session
      const { coach, parent, bookingId } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      const first = words('Good start')
      await write(profile, 3, first)
      await expect(profile.editReview()).toBeVisible()
      const review = (await provision.reviewOf(coach, parent))!

      // Straight away: within the cooldown.
      await openEdit(profile, first)
      await profile.dialogBody().fill(words('Second thoughts'))
      await save(profile, self.page)
      await expect(profile.dialogError()).toHaveText(t('reviews.updateTooSoon'), MODERATED)
      await self.page.keyboard.press('Escape')

      // Past the cooldown, but no session since the review: still refused.
      await provision.ageReview(review.id, 31)
      await provision.setBooking(bookingId, {
        startMinutesFromNow: -40 * 24 * 60,
        status: 'COMPLETED',
        statusChangedDaysAgo: 40,
      })
      await self.page.reload()
      await openEdit(profile, first)
      await profile.dialogBody().fill(words('Second thoughts'))
      await save(profile, self.page)
      await expect(profile.dialogError()).toHaveText(t('reviews.noQualifyingSession'), MODERATED)
      await self.page.keyboard.press('Escape')

      // A new session since: the edit goes through, replaces the text and is moderated again.
      await provision.reviewableSession(parent, coach)
      await self.page.reload()
      const edited = words(`Even better now. ${UNCERTAIN}`)
      await openEdit(profile, first)
      await profile.dialogStars(5).click()
      await profile.dialogBody().fill(edited)
      await save(profile, self.page)
      await expect(profile.dialogSubmit()).toBeHidden(MODERATED)
      await expect(profile.myReviewBody()).toHaveText(edited)
      await expect(profile.myReviewPending()).toBeVisible()
      const after = (await provision.reviewOf(coach, parent))!
      expect(after.id).toBe(review.id)
      expect(after.rating).toBe(5)
      expect(after.status).toBe('UNDER_REVIEW')
    },
  )

  test(
    'review text may be 1000 characters, not 1001',
    scenario({
      id: 'REV-05',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)

      // The field holds at most 1000 characters.
      await profile.writeReview().click()
      await profile.dialogStars(4).click()
      await profile.dialogBody().fill('a'.repeat(1001))
      const held = await profile.dialogBody().inputValue()
      if (held.length > 1000) {
        // Typed past the field's limit: the server refuses it.
        await profile.dialogSubmit().click()
        await expect(profile.dialogError()).toHaveText(t('reviews.bodyTooLong'), MODERATED)
        expect(await provision.reviewOf(coach, parent)).toBeNull()
      } else {
        expect(held).toHaveLength(1000)
      }

      // Exactly 1000 is saved whole.
      await profile.dialogBody().fill('b'.repeat(1000))
      await submit(profile)
      await expect(profile.myReviewBody()).toBeVisible()
      expect((await provision.reviewOf(coach, parent))?.body).toHaveLength(1000)
    },
  )

  test(
    'a review without a star rating cannot be submitted',
    scenario({
      id: 'REV-06',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const { coach, parent } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      await profile.writeReview().click()
      await profile.dialogBody().fill(words('No stars given'))
      await expect(profile.dialogSubmit()).toBeDisabled()
      await profile.dialogStars(2).click()
      await expect(profile.dialogSubmit()).toBeEnabled()
    },
  )
})

test.describe('M · Who sees a review', () => {
  test(
    'a pending review is shown to its author as pending, and to nobody else',
    scenario({
      id: 'REV-07',
      priority: 'P0',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, page, suite, provision }) => {
      const { coach, parent } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      const body = words(`Held one. ${UNCERTAIN}`)
      await write(profile, 4, body)
      await expect(profile.myReviewPending()).toBeVisible()

      // Another family and a guest see neither the review nor a rating.
      const stranger = await actor(asRole(await provision.parent()))
      for (const viewer of [stranger.page, page]) {
        const other = await openProfile(viewer, suite, coach)
        await expect(other.name()).toBeVisible()
        await expect(other.reviewBodies().filter({ hasText: body })).toHaveCount(0)
        await expect(other.rating()).toHaveText(t('marketplace.noReviewsYet'))
      }
    },
  )

  test(
    'an approved review is public and moves the rating on the profile and the search card',
    scenario({
      id: 'REV-08',
      priority: 'P0',
      type: 'happy',
      tags: ['@regression', '@parent', '@coach', '@reviews', '@marketplace'],
    }),
    async ({ actor, page, suite, provision }) => {
      const { coach, parent, city, displayName } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      await expect(profile.rating()).toHaveText(t('marketplace.noReviewsYet'))
      const body = words('Brilliant with beginners')
      await write(profile, 5, body)

      // Approved on submit: public, and the header moves without a reload.
      await expect(profile.reviewBodies().filter({ hasText: body })).toHaveCount(1)
      await expect(profile.rating()).toHaveText('5.0 (1)')

      // A guest sees it on the profile and on the search card.
      const guest = await openProfile(page, suite, coach)
      await expect(guest.reviewBodies().filter({ hasText: body })).toHaveCount(1)
      const marketplace = await new MarketplaceScreen(page, suite).open()
      await marketplace.searchCity(city)
      await expect(marketplace.card(displayName)).toContainText('5.0')
    },
  )

  test(
    'a review the moderator rules unsafe never becomes public',
    scenario({
      id: 'REV-09',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, page, suite, provision }) => {
      const { coach, parent } = await reviewer(provision)
      const self = await actor(asRole(parent))
      const profile = await openProfile(self.page, suite, coach)
      const body = words(`Rude words. ${UNSAFE}`)
      await write(profile, 1, body)

      await expect(profile.myReviewBlocked()).toContainText(t('reviews.blocked'))
      expect((await provision.reviewOf(coach, parent))?.status).toBe('BLOCKED')
      const guest = await openProfile(page, suite, coach)
      await expect(guest.name()).toBeVisible()
      await expect(guest.reviewBodies().filter({ hasText: body })).toHaveCount(0)
      await expect(guest.rating()).toHaveText(t('marketplace.noReviewsYet'))
    },
  )

  test(
    'someone not yet able to review is told why instead of offered the button',
    scenario({
      id: 'REV-16',
      priority: 'P1',
      type: 'neg',
      tags: ['@regression', '@parent', '@coach', '@reviews'],
    }),
    async ({ actor, suite, provision }) => {
      const coach = await provision.coach()

      // No session with the coach at all.
      const newcomer = await provision.parent()
      const fresh = await actor(asRole(newcomer))
      const none = await openProfile(fresh.page, suite, coach)
      await expect(none.ineligible()).toHaveText(t('reviews.noQualifyingSession'))
      await expect(none.writeReview()).toHaveCount(0)

      // A session that ended too recently to count yet.
      const recent = await provision.parent()
      await provision.saveCard(recent)
      await provision.completedSession(recent, coach)
      const soon = await actor(asRole(recent))
      const tooSoon = await openProfile(soon.page, suite, coach)
      await expect(tooSoon.ineligible()).toHaveText(t('reviews.noQualifyingSession'))

      // A qualifying session, but a dispute about it is open.
      const disputing: ParentAccount = await provision.parent()
      await provision.saveCard(disputing)
      const bookingId = await provision.reviewableSession(disputing, coach)
      await provision.raiseDispute(disputing, bookingId)
      const upset = await actor(asRole(disputing))
      const blocked = await openProfile(upset.page, suite, coach)
      await expect(blocked.ineligible()).toHaveText(t('reviews.activeDispute'))
      await expect(blocked.writeReview()).toHaveCount(0)
    },
  )
})
