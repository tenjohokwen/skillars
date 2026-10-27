// skillars-deferred-165 AC5: CoachPublicProfilePage.vue — the review "Flag" action is hidden on
// the viewer's own review and shown on everyone else's.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

vi.mock('src/api/marketplace.api', () => ({
  getCoachProfile: vi.fn(),
  getProfileBuilderStatus: vi.fn(),
}))

vi.mock('src/api/reviews.api', () => ({
  listCoachReviews: vi.fn(),
  getMyReviewForCoach: vi.fn(),
  checkReviewEligibility: vi.fn(),
  submitReview: vi.fn(),
  updateReview: vi.fn(),
  flagReview: vi.fn(),
}))

import { getCoachProfile, getProfileBuilderStatus } from 'src/api/marketplace.api'
import { getMyReviewForCoach } from 'src/api/reviews.api'
import CoachPublicProfilePage from 'src/pages/marketplace/CoachPublicProfilePage.vue'
import ReviewFlagAction from 'src/components/marketplace/ReviewFlagAction.vue'
import ReliabilityIndicator from 'src/components/marketplace/ReliabilityIndicator.vue'

const mounted = []

const MY_REVIEW_ID = 'review-mine'
const OTHER_REVIEW_ID = 'review-other'

const PROFILE = {
  displayName: 'Coach A',
  bio: '',
  photos: [],
  reviews: {
    reviews: [
      {
        reviewId: MY_REVIEW_ID,
        authorRole: 'PARENT',
        rating: 5,
        body: 'Great!',
        createdAt: '2026-01-01T00:00:00Z',
      },
      {
        reviewId: OTHER_REVIEW_ID,
        authorRole: 'PARENT',
        rating: 4,
        body: 'Good',
        createdAt: '2026-01-02T00:00:00Z',
      },
    ],
    totalElements: 2,
    hasNext: false,
    page: 0,
  },
}

let lastRouter = null

async function mountPage(coachId = 'coach-1', role = 'PARENT', { query = '', booking } = {}) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { role }, ...(booking ? { booking } : {}) },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/marketplace/coaches/:coachId', component: CoachPublicProfilePage },
      { path: '/parent/coaches/:coachId/request-booking', component: { template: '<div />' } },
    ],
  })
  router.push(`/marketplace/coaches/${coachId}${query}`)
  await router.isReady()
  lastRouter = router

  const wrapper = mount(CoachPublicProfilePage, {
    shallow: true,
    global: { plugins: [pinia, router], renderStubDefaultSlot: true },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('CoachPublicProfilePage.vue — review flag visibility (deferred-165 AC5)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it("hides the flag action on the viewer's own review and shows it on another's", async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockResolvedValue({ reviewId: MY_REVIEW_ID, rating: 5, body: 'Great!' })

    const wrapper = await mountPage()

    const flagActions = wrapper.findAllComponents(ReviewFlagAction)
    const flaggedReviewIds = flagActions.map((c) => c.props('reviewId'))

    expect(flaggedReviewIds).toContain(OTHER_REVIEW_ID)
    expect(flaggedReviewIds).not.toContain(MY_REVIEW_ID)
  })

  it('shows the flag action on every review when the viewer has no review of their own', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })

    const wrapper = await mountPage()

    const flagActions = wrapper.findAllComponents(ReviewFlagAction)
    expect(flagActions.map((c) => c.props('reviewId'))).toEqual([MY_REVIEW_ID, OTHER_REVIEW_ID])
  })

  // Code review 2026-10-27: the profile page is public and flagging requires auth — anonymous
  // visitors and non-parent/player roles must not get a Flag action that can only fail.
  it.each([
    ['an anonymous visitor', null],
    ['a coach', 'COACH'],
  ])('hides every flag action for %s', async (_label, role) => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })

    const wrapper = await mountPage('coach-1', role)

    expect(wrapper.findAllComponents(ReviewFlagAction)).toHaveLength(0)
  })
})

// Navigation reachability: ReliabilityIndicator's coachContext (a link to /coach/reliability) is
// enabled only for a coach viewing their OWN public profile.
// Mutation check: pass `:coach-context="authStore.isCoach"` instead of isOwnProfile → the "another
// coach's profile" case goes RED.
describe('CoachPublicProfilePage.vue — own-profile reliability link (navigation reachability)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  const coachContextOf = (wrapper) =>
    wrapper.findComponent(ReliabilityIndicator).props('coachContext')

  it('enables coachContext when a COACH views their own profile', async () => {
    getCoachProfile.mockResolvedValue({ ...PROFILE, reliabilityStrikeCount: 1 })
    getProfileBuilderStatus.mockResolvedValue({ coachId: 'coach-1' })

    const wrapper = await mountPage('coach-1', 'COACH')

    expect(coachContextOf(wrapper)).toBe(true)
  })

  it("keeps coachContext off when a COACH views another coach's profile", async () => {
    getCoachProfile.mockResolvedValue({ ...PROFILE, reliabilityStrikeCount: 1 })
    getProfileBuilderStatus.mockResolvedValue({ coachId: 'coach-2' })

    const wrapper = await mountPage('coach-1', 'COACH')

    expect(coachContextOf(wrapper)).toBe(false)
  })

  it('keeps coachContext off (and never asks for coach status) for a non-coach viewer', async () => {
    getCoachProfile.mockResolvedValue({ ...PROFILE, reliabilityStrikeCount: 1 })
    getMyReviewForCoach.mockResolvedValue(null)

    const wrapper = await mountPage('coach-1', 'PARENT')

    expect(coachContextOf(wrapper)).toBe(false)
    expect(getProfileBuilderStatus).not.toHaveBeenCalled()
  })

  it('keeps coachContext off when the coach-status lookup fails', async () => {
    getCoachProfile.mockResolvedValue({ ...PROFILE, reliabilityStrikeCount: 1 })
    getProfileBuilderStatus.mockRejectedValue({ response: { status: 404 } })

    const wrapper = await mountPage('coach-1', 'COACH')

    expect(coachContextOf(wrapper)).toBe(false)
  })
})

// A coach's shared-slot link lands here with ?slot=<coach-local YYYY-MM-DDTHH:mm>: the slot is
// shown and forwarded to the booking request page (which preselects it).
describe('CoachPublicProfilePage.vue — shared slot', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('shows the shared slot and forwards it to request-booking', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
    const wrapper = await mountPage('coach-1', 'PARENT', {
      query: '?slot=2026-11-04T09:30',
      booking: {
        sessionPacks: [
          { coachId: 'coach-1', status: 'ACTIVE', creditsRemaining: 2, sessionCount: 5 },
        ],
      },
    })

    expect(wrapper.find('.shared-slot-banner').exists()).toBe(true)

    wrapper.vm.handleCta()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/parent/coaches/coach-1/request-booking')
    expect(lastRouter.currentRoute.value.query.slot).toBe('2026-11-04T09:30')
  })

  it('ignores a malformed slot value', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
    const wrapper = await mountPage('coach-1', 'PARENT', { query: '?slot=garbage' })
    expect(wrapper.find('.shared-slot-banner').exists()).toBe(false)
  })
})
