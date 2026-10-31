// skillars-deferred-165 AC5: CoachPublicProfilePage.vue — the review "Flag" action is hidden on
// the viewer's own review and shown on everyone else's.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// Notify is not registered under test — stub useQuasar so $q.notify is callable on error paths.
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ ...actual.useQuasar?.(), notify: vi.fn() }) }
})

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
import { getMyReviewForCoach, listCoachReviews } from 'src/api/reviews.api'
import CoachPublicProfilePage from 'src/pages/marketplace/CoachPublicProfilePage.vue'
import ReviewFlagAction from 'src/components/marketplace/ReviewFlagAction.vue'
import ReliabilityIndicator from 'src/components/marketplace/ReliabilityIndicator.vue'
import AppealAction from 'src/components/common/AppealAction.vue'
import { usePlayerStore } from 'src/stores/playerStore'

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

async function mountPage(
  coachId = 'coach-1',
  role = 'PARENT',
  { query = '', beforeMount, ...extraState } = {},
) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { role }, ...extraState },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/marketplace/coaches/:coachId', component: CoachPublicProfilePage },
      { path: '/parent/coaches/:coachId/request-booking', component: { template: '<div />' } },
      { path: '/parent/coaches/:coachId/purchase-sessions', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  router.push(`/marketplace/coaches/${coachId}${query}`)
  await router.isReady()
  lastRouter = router
  if (beforeMount) beforeMount(pinia)

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

  // S-06: a review by an account related to the coach is stored but not published or counted; its author is told why.
  it('tells the author their review is not published or counted when it is flagged related-party', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockResolvedValue({
      reviewId: MY_REVIEW_ID,
      rating: 5,
      body: 'Great!',
      moderationStatus: 'APPROVED',
      relatedPartyHidden: true,
    })

    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="review-related-party-hidden"]').exists()).toBe(true)
  })

  it('shows no related-party notice for an ordinary review', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockResolvedValue({
      reviewId: MY_REVIEW_ID,
      rating: 5,
      body: 'Great!',
      moderationStatus: 'APPROVED',
      relatedPartyHidden: false,
    })

    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="review-related-party-hidden"]').exists()).toBe(false)
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

describe('CoachPublicProfilePage.vue — Message coach entry point (feat/messaging-completion)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  const CHILD = { id: '77', name: 'Kid One', ageTier: 'AGE_13_17' }
  const parentState = (bookings) => ({
    player: { players: [CHILD], activePlayerId: '77' },
    booking: { parentBookings: bookings },
  })

  function arrange() {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
  }

  it('shows Message coach for the selected child once a confirmed booking links them to this coach', async () => {
    arrange()
    const wrapper = await mountPage(
      'coach-1',
      'PARENT',
      parentState([{ coachId: 'coach-1', playerId: '77', status: 'COMPLETED' }]),
    )

    expect(wrapper.find('[data-testid="message-coach-btn"]').exists()).toBe(true)
  })

  it.each([
    [
      'the booking is still only requested',
      [{ coachId: 'coach-1', playerId: '77', status: 'REQUESTED' }],
    ],
    [
      'the booking is with another coach',
      [{ coachId: 'coach-2', playerId: '77', status: 'CONFIRMED' }],
    ],
    [
      'the booking is for another child',
      [{ coachId: 'coach-1', playerId: '78', status: 'CONFIRMED' }],
    ],
    ['there is no booking', []],
  ])('hides Message coach when %s', async (_label, bookings) => {
    arrange()
    const wrapper = await mountPage('coach-1', 'PARENT', parentState(bookings))

    expect(wrapper.find('[data-testid="message-coach-btn"]').exists()).toBe(false)
  })

  it('hides Message coach when the selected child is 18+', async () => {
    arrange()
    const wrapper = await mountPage('coach-1', 'PARENT', {
      player: { players: [{ ...CHILD, ageTier: 'ADULT' }], activePlayerId: '77' },
      booking: { parentBookings: [{ coachId: 'coach-1', playerId: '77', status: 'CONFIRMED' }] },
    })

    expect(wrapper.find('[data-testid="message-coach-btn"]').exists()).toBe(false)
  })

  it('hides Message coach for a coach viewer', async () => {
    arrange()
    const wrapper = await mountPage(
      'coach-1',
      'COACH',
      parentState([{ coachId: 'coach-1', playerId: '77', status: 'CONFIRMED' }]),
    )

    expect(wrapper.find('[data-testid="message-coach-btn"]').exists()).toBe(false)
  })
})

// A12: review sort — resets to page 0 with the chosen sort and discards stale responses.
describe('CoachPublicProfilePage.vue — review sort (A12)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  const page = (reviewId, n) => ({
    reviews: [{ reviewId, authorRole: 'PARENT', rating: n, body: '', createdAt: null }],
    totalElements: 2,
    hasNext: true,
    page: 0,
  })

  it('changing the sort refetches page 0 with that sort and replaces the list', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
    listCoachReviews.mockResolvedValue(page('low', 1))
    const wrapper = await mountPage()

    await wrapper.vm.changeReviewSort('lowest')

    expect(listCoachReviews).toHaveBeenCalledWith('coach-1', 0, 'lowest')
    expect(wrapper.vm.reviews.map((r) => r.reviewId)).toEqual(['low'])
    expect(wrapper.vm.reviewsSorting).toBe(false)
  })

  it('"load more" keeps the selected sort', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
    listCoachReviews.mockResolvedValue(page('x', 5))
    const wrapper = await mountPage()
    await wrapper.vm.changeReviewSort('highest')
    await wrapper.vm.loadMoreReviews()
    expect(listCoachReviews).toHaveBeenLastCalledWith('coach-1', 1, 'highest')
  })

  it('a failed re-sort restores the previous sort so "load more" stays consistent', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
    listCoachReviews.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountPage()

    await wrapper.vm.changeReviewSort('lowest')

    expect(wrapper.vm.reviewSort).toBe('newest')
  })

  it('a slower response for an earlier sort does not overwrite the newer one', async () => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
    let resolveSlow
    listCoachReviews
      .mockReturnValueOnce(new Promise((r) => (resolveSlow = r)))
      .mockResolvedValueOnce(page('newer', 1))
    const wrapper = await mountPage()

    const first = wrapper.vm.changeReviewSort('highest')
    await wrapper.vm.changeReviewSort('lowest')
    resolveSlow(page('stale', 5))
    await first
    await flushPromises()

    expect(wrapper.vm.reviews.map((r) => r.reviewId)).toEqual(['newer'])
  })
})

// FR-ADM-004: the author of a moderation-blocked review gets an appeal action next to the notice.
describe('CoachPublicProfilePage.vue — blocked-review appeal', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it.each([
    ['BLOCKED', 1],
    ['APPROVED', 0],
  ])('own review %s renders %i appeal action(s)', async (moderationStatus, count) => {
    getCoachProfile.mockResolvedValue(PROFILE)
    getMyReviewForCoach.mockResolvedValue({
      reviewId: MY_REVIEW_ID,
      rating: 1,
      body: 'x',
      moderationStatus,
      lastModifiedAt: '2026-10-20T10:00:00Z',
    })
    const wrapper = await mountPage()
    const actions = wrapper.findAllComponents(AppealAction)
    expect(actions).toHaveLength(count)
    if (count) {
      expect(actions[0].props()).toMatchObject({
        subjectType: 'REVIEW_BLOCK',
        subjectId: MY_REVIEW_ID,
        enforcedAt: '2026-10-20T10:00:00Z',
      })
    }
  })
})

// fix/per-session-booking-cta: the primary CTA no longer forces a pack purchase on a family without
// credits when the coach has a per-session price.
describe('CoachPublicProfilePage.vue — booking CTA matrix (per-session booking)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  const PACK = { sessionCount: 5, totalPrice: 180, currency: 'EUR', label: null }
  const CREDITS = [{ coachId: 'coach-1', status: 'ACTIVE', creditsRemaining: 2, sessionCount: 5 }]
  const PRICED = { perSessionPrice: 40, currency: 'EUR', sessionPacks: [] }
  const PRICED_WITH_PACK = { perSessionPrice: 40, currency: 'EUR', sessionPacks: [PACK] }
  const PACK_ONLY = { perSessionPrice: null, currency: 'EUR', sessionPacks: [PACK] }
  const NEITHER = { perSessionPrice: null, currency: 'EUR', sessionPacks: [] }

  const parentState = (sessionPacks = []) => ({
    player: {
      players: [{ id: '77', name: 'Kid One', ageTier: 'AGE_13_17' }],
      activePlayerId: '77',
    },
    booking: { sessionPacks },
  })
  const playerState = (sessionPacks = []) => ({
    booking: { sessionPacks },
    beforeMount: (pinia) => usePlayerStore(pinia).fetchSelfPlayerId.mockResolvedValue('91'),
  })

  async function arrange(pricing, role, state = {}) {
    getCoachProfile.mockResolvedValue({ ...PROFILE, ...pricing })
    getMyReviewForCoach.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.reviewNotFound' } } },
    })
    return mountPage('coach-1', role, state)
  }

  const primary = (w) => w.find('[data-testid="coach-primary-cta"]')
  const secondary = (w) => w.find('[data-testid="coach-buy-sessions-btn"]')

  it('family with credits: "Book a session" → request-booking, even for a pack-only coach', async () => {
    const wrapper = await arrange(PACK_ONLY, 'PARENT', parentState(CREDITS))

    expect(wrapper.vm.ctaAction).toBe('request')
    expect(primary(wrapper).attributes('label')).toBe('marketplace.bookSession')
    expect(secondary(wrapper).exists()).toBe(false)

    wrapper.vm.handleCta()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/parent/coaches/coach-1/request-booking')
    expect(lastRouter.currentRoute.value.query).toMatchObject({
      playerId: '77',
      coachName: 'Coach A',
    })
  })

  it('parent without credits, priced coach: "Book a session" → request-booking, no pack button', async () => {
    const wrapper = await arrange(PRICED, 'PARENT', {
      ...parentState(),
      query: '?slot=2026-11-04T09:30',
    })

    expect(wrapper.vm.ctaAction).toBe('request')
    expect(primary(wrapper).attributes('label')).toBe('marketplace.bookSession')
    expect(secondary(wrapper).exists()).toBe(false)

    wrapper.vm.handleCta()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/parent/coaches/coach-1/request-booking')
    expect(lastRouter.currentRoute.value.query).toMatchObject({
      playerId: '77',
      coachName: 'Coach A',
      slot: '2026-11-04T09:30',
    })
  })

  it('parent without credits, priced coach with a pack: secondary "Buy sessions" → purchase', async () => {
    const wrapper = await arrange(PRICED_WITH_PACK, 'PARENT', parentState())

    expect(primary(wrapper).attributes('label')).toBe('marketplace.bookSession')
    expect(secondary(wrapper).exists()).toBe(true)
    expect(secondary(wrapper).attributes('label')).toBe('booking.packs.buySessions')

    wrapper.vm.handleBuySessions()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/parent/coaches/coach-1/purchase-sessions')
    expect(lastRouter.currentRoute.value.query.playerId).toBe('77')
  })

  it('parent without credits, pack-only coach: "Buy sessions" → purchase (with playerId)', async () => {
    const wrapper = await arrange(PACK_ONLY, 'PARENT', parentState())

    expect(wrapper.vm.ctaAction).toBe('purchase')
    expect(primary(wrapper).attributes('label')).toBe('booking.packs.buySessions')
    expect(secondary(wrapper).exists()).toBe(false)

    wrapper.vm.handleCta()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/parent/coaches/coach-1/purchase-sessions')
    expect(lastRouter.currentRoute.value.query.playerId).toBe('77')
  })

  it.each(['PARENT', 'PLAYER', null])(
    'coach with neither price nor pack: disabled "Not bookable yet" + hint (role %s)',
    async (role) => {
      const state = role === 'PARENT' ? parentState() : role === 'PLAYER' ? playerState() : {}
      const wrapper = await arrange(NEITHER, role, state)

      expect(wrapper.vm.ctaAction).toBe('unbookable')
      expect(primary(wrapper).attributes('label')).toBe('marketplace.notBookable')
      expect(primary(wrapper).attributes('disable')).toBe('true')
      expect(wrapper.find('[data-testid="coach-not-bookable-hint"]').exists()).toBe(true)
      expect(secondary(wrapper).exists()).toBe(false)

      const before = lastRouter.currentRoute.value.fullPath
      wrapper.vm.handleCta()
      await flushPromises()
      expect(lastRouter.currentRoute.value.fullPath).toBe(before)
    },
  )

  it('self-booking player without credits, priced coach: request-booking with own id + slot', async () => {
    const wrapper = await arrange(PRICED_WITH_PACK, 'PLAYER', {
      ...playerState(),
      query: '?slot=2026-11-04T09:30',
    })

    expect(wrapper.vm.ctaAction).toBe('request')
    expect(primary(wrapper).attributes('label')).toBe('marketplace.bookSession')
    expect(secondary(wrapper).exists()).toBe(true)

    wrapper.vm.handleCta()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/parent/coaches/coach-1/request-booking')
    expect(lastRouter.currentRoute.value.query).toMatchObject({
      playerId: '91',
      coachName: 'Coach A',
      slot: '2026-11-04T09:30',
    })
  })

  it('self-booking player without credits, pack-only coach: "Buy sessions" → purchase with own id', async () => {
    const wrapper = await arrange(PACK_ONLY, 'PLAYER', playerState())

    expect(wrapper.vm.ctaAction).toBe('purchase')
    wrapper.vm.handleCta()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/parent/coaches/coach-1/purchase-sessions')
    expect(lastRouter.currentRoute.value.query.playerId).toBe('91')
  })

  it.each([
    ['priced', PRICED_WITH_PACK],
    ['pack-only', PACK_ONLY],
  ])('anonymous viewer, %s coach: "Sign up to book" → login', async (_label, pricing) => {
    const wrapper = await arrange(pricing, null)

    expect(wrapper.vm.ctaAction).toBe('login')
    expect(primary(wrapper).attributes('label')).toBe('marketplace.signUpToBook')
    expect(secondary(wrapper).exists()).toBe(false)

    wrapper.vm.handleCta()
    await flushPromises()
    expect(lastRouter.currentRoute.value.path).toBe('/login')
    expect(lastRouter.currentRoute.value.query.returnUrl).toBe('/marketplace/coaches/coach-1')
  })
})
