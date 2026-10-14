// skillars-deferred-151 AC1: GET /api/reviews/coaches/me + POST /api/reviews/{id}/response had
// zero frontend caller before this story. First-ever coverage for CoachReviewsPage.vue.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/reviews.api', () => ({
  listMyReviews: vi.fn(),
  submitCoachResponse: vi.fn(),
}))

import { listMyReviews, submitCoachResponse } from 'src/api/reviews.api'
import CoachReviewsPage from 'src/pages/coach/CoachReviewsPage.vue'

const PAGE = (reviews, overrides = {}) => ({
  reviews,
  page: 0,
  totalPages: 1,
  totalElements: reviews.length,
  hasNext: false,
  ...overrides,
})

const APPROVED_NO_RESPONSE = {
  reviewId: 'r1',
  authorRole: 'PARENT',
  rating: 5,
  body: 'Great coach',
  moderationStatus: 'APPROVED',
  coachResponseBody: null,
  coachResponseAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  lastModifiedAt: '2026-01-01T00:00:00Z',
}

const mounted = []
function mountPage() {
  const wrapper = mount(CoachReviewsPage)
  mounted.push(wrapper)
  return wrapper
}

describe('CoachReviewsPage.vue (deferred-151 AC1)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('renders the review list on mount', async () => {
    listMyReviews.mockResolvedValue(PAGE([APPROVED_NO_RESPONSE]))
    const wrapper = mountPage()
    await flushPromises()

    expect(listMyReviews).toHaveBeenCalledWith(0)
    expect(wrapper.text()).toContain('Great coach')
  })

  it('shows the composer only for an APPROVED review with no response yet', async () => {
    listMyReviews.mockResolvedValue(PAGE([APPROVED_NO_RESPONSE]))
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('textarea').exists()).toBe(true)
  })

  it('does not show the composer for a PENDING own-review, and shows the pending copy instead', async () => {
    listMyReviews.mockResolvedValue(
      PAGE([{ ...APPROVED_NO_RESPONSE, reviewId: 'r2', moderationStatus: 'PENDING' }]),
    )
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).toContain('reviews.ownReviewPendingModeration')
  })

  it('does not show the composer for a BLOCKED own-review, and shows the blocked copy instead', async () => {
    listMyReviews.mockResolvedValue(
      PAGE([{ ...APPROVED_NO_RESPONSE, reviewId: 'r3', moderationStatus: 'BLOCKED' }]),
    )
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).toContain('reviews.ownReviewBlocked')
  })

  it('does not show the composer when a response already exists, showing the existing response instead', async () => {
    listMyReviews.mockResolvedValue(
      PAGE([
        {
          ...APPROVED_NO_RESPONSE,
          reviewId: 'r4',
          coachResponseBody: 'Thanks!',
          coachResponseAt: '2026-01-02T00:00:00Z',
        },
      ]),
    )
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).toContain('Thanks!')
  })

  it('submits a coach response and renders it without a page reload', async () => {
    listMyReviews.mockResolvedValue(PAGE([APPROVED_NO_RESPONSE]))
    submitCoachResponse.mockResolvedValue()
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('textarea').setValue('Thank you for the kind words')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(submitCoachResponse).toHaveBeenCalledWith('r1', 'Thank you for the kind words')
    expect(wrapper.text()).toContain('Thank you for the kind words')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })

  it('renders an incomplete-profile state on a 404 (no CoachProfile row yet)', async () => {
    listMyReviews.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('reviews.incompleteProfile')
  })

  it('renders a generic error state on a non-404 failure', async () => {
    listMyReviews.mockRejectedValue({ response: { status: 500 } })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('common.errorGeneric')
  })

  it('paginates via loadMore', async () => {
    listMyReviews.mockResolvedValueOnce(PAGE([APPROVED_NO_RESPONSE], { hasNext: true, page: 0 }))
    const wrapper = mountPage()
    await flushPromises()

    listMyReviews.mockResolvedValueOnce(
      PAGE([{ ...APPROVED_NO_RESPONSE, reviewId: 'r5' }], { hasNext: false, page: 1 }),
    )
    const loadMoreBtn = wrapper.findAll('button').find((b) => b.text() === 'reviews.loadMore')
    await loadMoreBtn.trigger('click')
    await flushPromises()

    expect(listMyReviews).toHaveBeenCalledWith(1)
    expect(wrapper.findAll('.review-item')).toHaveLength(2)
  })
})
