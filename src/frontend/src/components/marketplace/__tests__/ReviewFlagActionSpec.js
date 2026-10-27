// skillars-deferred-165 AC5: ReviewFlagAction.vue — flag dialog submit happy path and the
// already-flagged error path.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/reviews.api', () => ({
  flagReview: vi.fn(),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { flagReview } from 'src/api/reviews.api'
import ReviewFlagAction from 'src/components/marketplace/ReviewFlagAction.vue'

const mounted = []
function mountAction(reviewId = 'review-1') {
  const wrapper = mount(ReviewFlagAction, {
    props: { reviewId },
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('ReviewFlagAction.vue (skillars-deferred-165 AC5)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('submitting the dialog calls flagReview with the selected reason and details', async () => {
    flagReview.mockResolvedValue({ flagId: 'f1' })
    const wrapper = mountAction('review-1')

    wrapper.vm.openDialog()
    wrapper.vm.reason = 'FAKE_REVIEW'
    wrapper.vm.details = 'This looks fabricated'
    await wrapper.vm.submitFlag()
    await flushPromises()

    expect(flagReview).toHaveBeenCalledWith('review-1', {
      reason: 'FAKE_REVIEW',
      details: 'This looks fabricated',
    })
    expect(wrapper.vm.dialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('an already-flagged 403 shows the mapped error and keeps the dialog open', async () => {
    flagReview.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'reviews.alreadyFlagged' } } },
    })
    const wrapper = mountAction()

    wrapper.vm.openDialog()
    wrapper.vm.reason = 'OTHER'
    await wrapper.vm.submitFlag()

    expect(wrapper.vm.dialogOpen).toBe(true)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'reviews.alreadyFlagged' }),
    )
  })

  it('a generic failure shows the fallback error message', async () => {
    flagReview.mockRejectedValue(new Error('network error'))
    const wrapper = mountAction()

    wrapper.vm.openDialog()
    wrapper.vm.reason = 'OTHER'
    await wrapper.vm.submitFlag()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'reviews.flagFailed' }),
    )
  })
})
