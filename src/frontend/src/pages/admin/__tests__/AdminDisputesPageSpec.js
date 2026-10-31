// skillars-deferred-165 AC3: AdminDisputesPage.vue — queue render, detail load, and both
// resolve/dismiss happy paths.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getDisputeQueue: vi.fn(),
    getDisputeDetail: vi.fn(),
    resolveDispute: vi.fn(),
    dismissDispute: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminDisputesPage from 'src/pages/admin/AdminDisputesPage.vue'

const QUEUE_ROW = {
  disputeId: 'd1',
  bookingId: 'b1',
  raisedByRole: 'PARENT',
  reason: 'COACH_NO_SHOW',
  status: 'OPEN',
  createdAt: '2026-01-01T10:00:00Z',
}

const DETAIL = {
  disputeId: 'd1',
  bookingId: 'b1',
  raisedBy: 1,
  raisedByRole: 'PARENT',
  reason: 'COACH_NO_SHOW',
  details: 'Coach never showed up',
  status: 'OPEN',
  resolution: null,
  resolutionNote: null,
  creditAmount: null,
  coachId: 'c1',
  coachName: 'Coach A',
  sessionDate: '2026-01-01T09:00:00Z',
  bookingStatus: 'NO_SHOW_COACH',
  creditDebited: 20,
  stripeCharged: 30,
  sessionPrice: 50,
  cancellationHistory: [],
}

const mounted = []
async function mountPage() {
  const wrapper = mount(AdminDisputesPage, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminDisputesPage.vue (skillars-deferred-165 AC3)', () => {
  beforeEach(() => {
    // admin.api.js wrappers resolve to the already-unwrapped response body (boot/axios.js's
    // response interceptor returns response.data), not an axios Response.
    adminApi.getDisputeQueue.mockResolvedValue({ content: [QUEUE_ROW], totalPages: 1 })
    adminApi.getDisputeDetail.mockResolvedValue(DETAIL)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads and renders the open-dispute queue on mount', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getDisputeQueue).toHaveBeenCalledWith(0)
    expect(wrapper.vm.rows).toEqual([QUEUE_ROW])
    expect(wrapper.find('[data-testid="dispute-row-view-btn"]').exists()).toBe(true)
  })

  it('a failed queue load shows the error banner', async () => {
    adminApi.getDisputeQueue.mockRejectedValue(new Error('network error'))
    const wrapper = await mountPage()

    expect(wrapper.vm.loadError).toBe(true)
  })

  it('opening a row detail loads and renders it via getDisputeDetail', async () => {
    const wrapper = await mountPage()

    await wrapper.vm.openDetail('d1')

    expect(adminApi.getDisputeDetail).toHaveBeenCalledWith('d1')
    expect(wrapper.vm.detail.coachName).toBe('Coach A')
    expect(wrapper.find('[data-testid="dispute-detail-content"]').exists()).toBe(true)
  })

  it('resolve happy path: submits the resolution and refreshes the queue', async () => {
    adminApi.resolveDispute.mockResolvedValue({})
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')

    wrapper.vm.openResolveDialog()
    wrapper.vm.resolution = 'FULL_CREDIT'
    wrapper.vm.resolutionNote = 'Refunded in full'
    await wrapper.vm.submitResolve()
    await flushPromises()

    expect(adminApi.resolveDispute).toHaveBeenCalledWith('d1', {
      resolution: 'FULL_CREDIT',
      creditAmount: null,
      resolutionNote: 'Refunded in full',
    })
    expect(wrapper.vm.resolveDialogOpen).toBe(false)
    expect(wrapper.vm.detailDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
    expect(adminApi.getDisputeQueue).toHaveBeenCalledTimes(2)
  })

  it('dismiss happy path: submits the dismissal and refreshes the queue', async () => {
    adminApi.dismissDispute.mockResolvedValue({})
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')

    wrapper.vm.openDismissDialog()
    wrapper.vm.dismissReason = 'No evidence of a no-show'
    await wrapper.vm.submitDismiss()
    await flushPromises()

    expect(adminApi.dismissDispute).toHaveBeenCalledWith('d1', {
      reason: 'No evidence of a no-show',
    })
    expect(wrapper.vm.dismissDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('resolving an already-resolved dispute shows the mapped conflict error', async () => {
    adminApi.resolveDispute.mockRejectedValue({
      response: {
        data: {
          errorMsg: { errorKey: 'generic.requestError', message: 'disputes.alreadyResolved' },
        },
      },
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')

    wrapper.vm.openResolveDialog()
    wrapper.vm.resolution = 'NO_ACTION'
    wrapper.vm.resolutionNote = 'No action needed'
    await wrapper.vm.submitResolve()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'admin.disputes.alreadyResolved' }),
    )
    // Code review 2026-10-27: the stale dispute is dropped and the worklist refreshed.
    expect(wrapper.vm.resolveDialogOpen).toBe(false)
    expect(wrapper.vm.detailDialogOpen).toBe(false)
    expect(adminApi.getDisputeQueue).toHaveBeenCalledTimes(2)
  })

  // ── Code review 2026-10-27 ────────────────────────────────────────────────

  it('does not send a hidden credit amount left over from a previously selected resolution', async () => {
    adminApi.resolveDispute.mockResolvedValue({})
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')

    wrapper.vm.openResolveDialog()
    wrapper.vm.resolution = 'PARTIAL_CREDIT'
    wrapper.vm.creditAmount = 15
    wrapper.vm.resolution = 'NO_ACTION'
    wrapper.vm.resolutionNote = 'Changed my mind'
    await wrapper.vm.submitResolve()

    expect(adminApi.resolveDispute).toHaveBeenCalledWith('d1', {
      resolution: 'NO_ACTION',
      creditAmount: null,
      resolutionNote: 'Changed my mind',
    })
  })

  it('PARTIAL_CREDIT cannot be submitted without a positive amount', async () => {
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')

    wrapper.vm.openResolveDialog()
    wrapper.vm.resolution = 'PARTIAL_CREDIT'
    wrapper.vm.resolutionNote = 'Half back'
    expect(wrapper.vm.canSubmitResolve).toBe(false)
    wrapper.vm.creditAmount = 10
    expect(wrapper.vm.canSubmitResolve).toBe(true)
  })

  it('steps back a page when resolving empties the last page', async () => {
    adminApi.getDisputeQueue
      .mockResolvedValueOnce({ content: [QUEUE_ROW], totalPages: 2 })
      .mockResolvedValueOnce({ content: [QUEUE_ROW], totalPages: 2 })
      .mockResolvedValueOnce({ content: [], totalPages: 1 })
      .mockResolvedValueOnce({ content: [QUEUE_ROW], totalPages: 1 })
    adminApi.resolveDispute.mockResolvedValue({})
    const wrapper = await mountPage()
    await wrapper.vm.loadPage(2)

    await wrapper.vm.openDetail('d1')
    wrapper.vm.openResolveDialog()
    wrapper.vm.resolution = 'FULL_CREDIT'
    wrapper.vm.resolutionNote = 'Refunded'
    await wrapper.vm.submitResolve()
    await flushPromises()

    expect(adminApi.getDisputeQueue).toHaveBeenLastCalledWith(0)
    expect(wrapper.vm.currentPage).toBe(1)
    expect(wrapper.vm.rows).toEqual([QUEUE_ROW])
  })

  it('a late detail response for a previously opened dispute does not overwrite the current one', async () => {
    let resolveFirst
    adminApi.getDisputeDetail
      .mockImplementationOnce(() => new Promise((r) => (resolveFirst = r)))
      .mockResolvedValueOnce({ ...DETAIL, disputeId: 'd2', coachName: 'Coach B' })
    const wrapper = await mountPage()

    const first = wrapper.vm.openDetail('d1')
    await wrapper.vm.openDetail('d2')
    resolveFirst(DETAIL)
    await first
    await flushPromises()

    expect(wrapper.vm.selectedDisputeId).toBe('d2')
    expect(wrapper.vm.detail.disputeId).toBe('d2')
  })

  it('renders raisedByRole and creditAmount in the detail view, and a dash for a missing amount', async () => {
    adminApi.getDisputeDetail.mockResolvedValue({
      ...DETAIL,
      creditAmount: 12.5,
      stripeCharged: null,
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')
    await flushPromises()

    const text = wrapper.find('[data-testid="dispute-detail-content"]').text()
    expect(text).toContain('PARENT')
    expect(text).toContain('€12.50')
    expect(text).toMatch(/admin\.disputes\.stripeChargedLabel: —/)
  })

  // ── feat/payment-forfeit-payouts-and-clawback: UPHOLD_COACH ───────────────

  it('offers UPHOLD_COACH with its explanation, and submits it without a credit amount', async () => {
    adminApi.resolveDispute.mockResolvedValue({})
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')

    wrapper.vm.openResolveDialog()
    expect(wrapper.vm.resolutionOptions.map((o) => o.value)).toContain('UPHOLD_COACH')
    wrapper.vm.resolution = 'UPHOLD_COACH'
    wrapper.vm.resolutionNote = 'Session took place'
    await flushPromises()
    expect(wrapper.find('[data-testid="dispute-uphold-coach-help"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-credit-amount-input"]').exists()).toBe(false)
    await wrapper.vm.submitResolve()

    expect(adminApi.resolveDispute).toHaveBeenCalledWith('d1', {
      resolution: 'UPHOLD_COACH',
      creditAmount: null,
      resolutionNote: 'Session took place',
    })
  })

  it('maps the no-refund rejection to its copy', async () => {
    adminApi.resolveDispute.mockRejectedValue({
      response: { data: { errorMsg: { message: 'disputes.noRefundToReverse' } } },
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')
    wrapper.vm.openResolveDialog()
    wrapper.vm.resolution = 'UPHOLD_COACH'
    wrapper.vm.resolutionNote = 'x'
    await wrapper.vm.submitResolve()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'admin.disputes.noRefundToReverse' }),
    )
  })

  it('shows recovered and absorbed amounts of a resolved UPHOLD_COACH dispute', async () => {
    adminApi.getDisputeDetail.mockResolvedValue({
      ...DETAIL,
      status: 'RESOLVED',
      resolution: 'UPHOLD_COACH',
      recoveredAmount: 12,
      absorbedAmount: 18,
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('d1')
    await flushPromises()
    expect(wrapper.find('[data-testid="dispute-recovered-amount"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-absorbed-amount"]').exists()).toBe(true)
  })
})

// S-28: a limited administrator may read disputes but the resolve/dismiss actions are full-admin only.
describe('AdminDisputesPage.vue - limited admin (S-28)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  async function mountAs(limitedAdmin) {
    adminApi.getDisputeQueue.mockResolvedValue({ content: [QUEUE_ROW], totalPages: 1 })
    adminApi.getDisputeDetail.mockResolvedValue(DETAIL)
    const wrapper = mount(AdminDisputesPage, {
      global: {
        plugins: [
          createTestingPinia({
            createSpy: vi.fn,
            initialState: { auth: { userId: '1', role: 'ADMIN', limitedAdmin } },
          }),
        ],
        stubs: { QDialog: { template: '<div><slot /></div>' } },
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    await wrapper.vm.openDetail('d1')
    await flushPromises()
    return wrapper
  }

  it('hides resolve and dismiss for a limited admin but still shows the detail', async () => {
    const wrapper = await mountAs(true)
    expect(wrapper.find('[data-testid="dispute-detail-content"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-resolve-open-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="dispute-dismiss-open-btn"]').exists()).toBe(false)
  })

  it('shows resolve and dismiss for a full admin', async () => {
    const wrapper = await mountAs(false)
    expect(wrapper.find('[data-testid="dispute-resolve-open-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-dismiss-open-btn"]').exists()).toBe(true)
  })
})
