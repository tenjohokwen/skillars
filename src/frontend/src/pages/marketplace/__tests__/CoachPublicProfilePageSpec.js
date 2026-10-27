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
}))

vi.mock('src/api/reviews.api', () => ({
  listCoachReviews: vi.fn(),
  getMyReviewForCoach: vi.fn(),
  checkReviewEligibility: vi.fn(),
  submitReview: vi.fn(),
  updateReview: vi.fn(),
  flagReview: vi.fn(),
}))

import { getCoachProfile } from 'src/api/marketplace.api'
import { getMyReviewForCoach } from 'src/api/reviews.api'
import CoachPublicProfilePage from 'src/pages/marketplace/CoachPublicProfilePage.vue'
import ReviewFlagAction from 'src/components/marketplace/ReviewFlagAction.vue'

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

async function mountPage(coachId = 'coach-1', role = 'PARENT') {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { role } },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/marketplace/coaches/:coachId', component: CoachPublicProfilePage }],
  })
  router.push(`/marketplace/coaches/${coachId}`)
  await router.isReady()

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
