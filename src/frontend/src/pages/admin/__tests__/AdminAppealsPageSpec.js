// FR-ADM-004: AdminAppealsPage.vue — queue render + status filter, detail load, both decisions,
// the already-decided race, and the stale-response guards.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getAppealQueue: vi.fn(),
    getAppealDetail: vi.fn(),
    decideAppeal: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminAppealsPage from 'src/pages/admin/AdminAppealsPage.vue'

const QUEUE_ROW = {
  appealId: 'a1',
  subjectType: 'RELIABILITY_STRIKE',
  subjectId: 's1',
  appellantUserId: 7,
  appellantName: 'Coach Carla',
  enforcedAt: '2026-10-20T10:00:00Z',
  reason: 'The player cancelled, not me',
  status: 'OPEN',
  createdAt: '2026-10-21T10:00:00Z',
  subject: null,
}

const DETAIL = {
  ...QUEUE_ROW,
  subject: { strikeReason: 'COACH_NO_SHOW', bookingId: 'b1', coachName: 'Coach Carla' },
}

const mounted = []
async function mountPage() {
  const wrapper = mount(AdminAppealsPage, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

function deferred() {
  let resolve
  const promise = new Promise((r) => {
    resolve = r
  })
  return { promise, resolve }
}

describe('AdminAppealsPage.vue (FR-ADM-004)', () => {
  beforeEach(() => {
    adminApi.getAppealQueue.mockResolvedValue({ content: [QUEUE_ROW], totalPages: 1 })
    adminApi.getAppealDetail.mockResolvedValue(DETAIL)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads the OPEN queue on mount', async () => {
    const wrapper = await mountPage()
    expect(adminApi.getAppealQueue).toHaveBeenCalledWith('OPEN', 0)
    expect(wrapper.vm.rows).toEqual([QUEUE_ROW])
    expect(wrapper.find('[data-testid="appeal-row-view-btn"]').exists()).toBe(true)
  })

  it('a failed queue load shows the error state', async () => {
    adminApi.getAppealQueue.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage()
    expect(wrapper.vm.loadError).toBe(true)
  })

  it('changing the status filter reloads from the first page', async () => {
    const wrapper = await mountPage()
    wrapper.vm.currentPage = 3
    await wrapper.vm.changeStatus('OVERTURNED')
    expect(adminApi.getAppealQueue).toHaveBeenLastCalledWith('OVERTURNED', 0)
    expect(wrapper.vm.currentPage).toBe(1)
  })

  it('a late queue response for an earlier filter is discarded', async () => {
    const wrapper = await mountPage()
    const slow = deferred()
    adminApi.getAppealQueue.mockReturnValueOnce(slow.promise)
    adminApi.getAppealQueue.mockResolvedValueOnce({ content: [], totalPages: 0 })
    const first = wrapper.vm.changeStatus('UPHELD')
    const second = wrapper.vm.changeStatus('OVERTURNED')
    await second
    slow.resolve({ content: [QUEUE_ROW], totalPages: 1 })
    await first
    await flushPromises()
    expect(wrapper.vm.rows).toEqual([])
  })

  it('opening a row loads and renders its detail, including the subject summary', async () => {
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('a1')
    await flushPromises()
    expect(adminApi.getAppealDetail).toHaveBeenCalledWith('a1')
    expect(wrapper.find('[data-testid="appeal-detail-content"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="appeal-detail-subject"]').text()).toContain('b1')
  })

  it('shows a "record gone" note when the subject summary is null', async () => {
    adminApi.getAppealDetail.mockResolvedValue({ ...QUEUE_ROW, subject: null })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('a1')
    await flushPromises()
    expect(wrapper.find('[data-testid="appeal-detail-subject-gone"]').exists()).toBe(true)
  })

  it('overturn submits the outcome with a trimmed note and refreshes the queue', async () => {
    adminApi.decideAppeal.mockResolvedValue({})
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('a1')
    wrapper.vm.decisionNote = '  Player cancelled — strike removed  '
    await wrapper.vm.decide('OVERTURNED')
    await flushPromises()

    expect(adminApi.decideAppeal).toHaveBeenCalledWith('a1', {
      outcome: 'OVERTURNED',
      note: 'Player cancelled — strike removed',
    })
    expect(wrapper.vm.detailDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'admin.appeals.overturnSuccess' }),
    )
    expect(adminApi.getAppealQueue).toHaveBeenCalledTimes(2)
  })

  it('a decision needs a note', async () => {
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('a1')
    wrapper.vm.decisionNote = '   '
    await wrapper.vm.decide('UPHELD')
    expect(adminApi.decideAppeal).not.toHaveBeenCalled()
  })

  it('an already-decided appeal (409) closes the dialog and refreshes the queue', async () => {
    adminApi.decideAppeal.mockRejectedValue({
      response: { data: { errorMsg: { message: 'appeals.alreadyDecided' } } },
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('a1')
    wrapper.vm.decisionNote = 'Stands'
    await wrapper.vm.decide('UPHELD')
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'admin.appeals.alreadyDecided' }),
    )
    expect(wrapper.vm.detailDialogOpen).toBe(false)
    expect(adminApi.getAppealQueue).toHaveBeenCalledTimes(2)
  })

  it('a decided appeal shows whether the reversal was applied and no decision buttons', async () => {
    adminApi.getAppealDetail.mockResolvedValue({
      ...DETAIL,
      status: 'OVERTURNED',
      decidedAt: '2026-10-22T10:00:00Z',
      decisionNote: 'ok',
      reversalApplied: true,
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('a1')
    await flushPromises()
    expect(wrapper.find('[data-testid="appeal-detail-reversal"]').text()).toBe(
      'admin.appeals.reversalApplied',
    )
    expect(wrapper.find('[data-testid="appeal-overturn-btn"]').exists()).toBe(false)
  })
})
