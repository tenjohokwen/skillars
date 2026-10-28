// feat/admin-moderation-and-enforcement A2: ReviewModerationPanel.vue — UNDER_REVIEW review queue,
// approve / block (reason required), and the 403 "already decided by another admin" path.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getReviewQueue: vi.fn(),
    approveReview: vi.fn(),
    blockReview: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import ReviewModerationPanel from 'src/components/admin/ReviewModerationPanel.vue'

const REVIEW = {
  reviewId: 'r1',
  coachId: 'c1',
  coachName: 'Coach A',
  authorRole: 'PARENT',
  rating: 1,
  body: 'Terrible',
  createdAt: '2026-01-01T10:00:00Z',
  lastModifiedAt: null,
  heldReason: 'FLAG_THRESHOLD',
  flagCount: 3,
  flags: [{ reason: 'OFFENSIVE', details: null, createdAt: '2026-01-02T10:00:00Z' }],
}

const mounted = []
async function mountPanel() {
  const wrapper = mount(ReviewModerationPanel, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('ReviewModerationPanel.vue', () => {
  beforeEach(() => {
    adminApi.getReviewQueue.mockResolvedValue({ content: [REVIEW], totalPages: 1 })
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads the under-review queue on mount', async () => {
    const wrapper = await mountPanel()

    expect(adminApi.getReviewQueue).toHaveBeenCalledWith(0)
    expect(wrapper.vm.rows).toEqual([REVIEW])
    expect(wrapper.find('[data-testid="review-row-view-btn"]').exists()).toBe(true)
  })

  it('approves the selected review, emits changed and reloads', async () => {
    adminApi.approveReview.mockResolvedValue(undefined)
    const wrapper = await mountPanel()

    wrapper.vm.openDetail(REVIEW)
    await wrapper.vm.approve()
    await flushPromises()

    expect(adminApi.approveReview).toHaveBeenCalledWith('r1')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.vm.detailDialogOpen).toBe(false)
    expect(adminApi.getReviewQueue).toHaveBeenCalledTimes(2)
  })

  it('blocks the selected review with the trimmed reason', async () => {
    adminApi.blockReview.mockResolvedValue(undefined)
    const wrapper = await mountPanel()

    wrapper.vm.openDetail(REVIEW)
    wrapper.vm.openBlockDialog()
    wrapper.vm.blockReason = ' Hate speech '
    await wrapper.vm.block()

    expect(adminApi.blockReview).toHaveBeenCalledWith('r1', 'Hate speech')
    expect(wrapper.vm.blockDialogOpen).toBe(false)
  })

  it('a 409 (already decided by another admin) closes the dialogs and refreshes', async () => {
    adminApi.approveReview.mockRejectedValue({ response: { status: 409 } })
    const wrapper = await mountPanel()

    wrapper.vm.openDetail(REVIEW)
    await wrapper.vm.approve()
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'negative',
        message: 'admin.reviewModeration.alreadyDecided',
      }),
    )
    expect(wrapper.vm.detailDialogOpen).toBe(false)
    expect(adminApi.getReviewQueue).toHaveBeenCalledTimes(2)
  })

  it('a generic failure keeps the dialog open', async () => {
    adminApi.approveReview.mockRejectedValue({ response: { status: 500 } })
    const wrapper = await mountPanel()

    wrapper.vm.openDetail(REVIEW)
    await wrapper.vm.approve()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'admin.reviewModeration.actionFailed' }),
    )
    expect(wrapper.vm.detailDialogOpen).toBe(true)
  })
})
