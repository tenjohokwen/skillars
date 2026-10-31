// S-09: AdminSafeguardingPage.vue - request queue, document view, inspect / reject / revoke, race handling.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getSafeguardingRequests: vi.fn(),
    getSafeguardingRecords: vi.fn(),
    getSafeguardingDocument: vi.fn(),
    inspectSafeguardingRequest: vi.fn(),
    rejectSafeguardingRequest: vi.fn(),
    revokeSafeguardingRecord: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminSafeguardingPage from 'src/pages/admin/AdminSafeguardingPage.vue'

const REQUEST_ROW = {
  coachId: 'c1',
  coachDisplayName: 'Coach Kim',
  checkType: 'DE_EFZ',
  requestedAt: '2026-11-01T10:00:00Z',
  hasUpload: true,
  uploadStoredAt: '2026-11-01T10:00:00Z',
  hasValidCheck: false,
}

const RECORD_ROW = {
  coachId: 'c2',
  coachDisplayName: 'Coach Lee',
  checkType: 'DE_EFZ',
  inspectedAt: '2026-10-01T10:00:00Z',
  expiresAt: '2031-10-01T10:00:00Z',
  inspectedBy: 9,
}

const mounted = []
async function mountPage() {
  const wrapper = mount(AdminSafeguardingPage, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminSafeguardingPage.vue (S-09)', () => {
  beforeEach(() => {
    adminApi.getSafeguardingRequests.mockResolvedValue({ content: [REQUEST_ROW], totalPages: 1 })
    adminApi.getSafeguardingRecords.mockResolvedValue({ content: [RECORD_ROW], totalPages: 1 })
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('loads the pending requests on mount and offers view / inspect / reject', async () => {
    const wrapper = await mountPage()
    expect(adminApi.getSafeguardingRequests).toHaveBeenCalledWith(0)
    expect(wrapper.vm.rows).toEqual([REQUEST_ROW])
    expect(wrapper.find('[data-testid="safeguarding-view-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="safeguarding-inspect-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="safeguarding-reject-btn"]').exists()).toBe(true)
  })

  it('a request without an upload has no view button (the inspect-another-way route)', async () => {
    adminApi.getSafeguardingRequests.mockResolvedValue({
      content: [{ ...REQUEST_ROW, hasUpload: false }],
      totalPages: 1,
    })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="safeguarding-view-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="safeguarding-inspect-btn"]').exists()).toBe(true)
  })

  it('a failed load shows the error state', async () => {
    adminApi.getSafeguardingRequests.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage()
    expect(wrapper.vm.loadError).toBe(true)
  })

  it('opens the decrypted document in a new tab and releases the object URL later', async () => {
    const blob = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
    adminApi.getSafeguardingDocument.mockResolvedValue(blob)
    const createUrl = vi.fn(() => 'blob:doc')
    vi.stubGlobal('URL', { createObjectURL: createUrl, revokeObjectURL: vi.fn() })
    const open = vi.fn()
    vi.stubGlobal('open', open)
    const wrapper = await mountPage()

    await wrapper.vm.viewDocument(REQUEST_ROW)

    expect(adminApi.getSafeguardingDocument).toHaveBeenCalledWith('c1')
    expect(open).toHaveBeenCalledWith('blob:doc', '_blank', 'noopener')
  })

  it('a document that is gone shows a message and reloads', async () => {
    adminApi.getSafeguardingDocument.mockRejectedValue(new Error('404'))
    const wrapper = await mountPage()
    await wrapper.vm.viewDocument(REQUEST_ROW)
    await flushPromises()
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'safeguarding.admin.viewFailed' }),
    )
    expect(adminApi.getSafeguardingRequests).toHaveBeenCalledTimes(2)
  })

  it('inspect confirms through the dialog, calls the API and reloads', async () => {
    adminApi.inspectSafeguardingRequest.mockResolvedValue({})
    const wrapper = await mountPage()
    wrapper.vm.openAction('inspect', REQUEST_ROW)
    await flushPromises()
    await wrapper.vm.confirmAction()
    await flushPromises()

    expect(adminApi.inspectSafeguardingRequest).toHaveBeenCalledWith('c1')
    expect(wrapper.vm.actionOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'safeguarding.admin.inspectedDone' }),
    )
    expect(adminApi.getSafeguardingRequests).toHaveBeenCalledTimes(2)
  })

  it('reject sends the trimmed note (or null)', async () => {
    adminApi.rejectSafeguardingRequest.mockResolvedValue()
    const wrapper = await mountPage()
    wrapper.vm.openAction('reject', REQUEST_ROW)
    wrapper.vm.reason = '  not legible  '
    await wrapper.vm.confirmAction()
    expect(adminApi.rejectSafeguardingRequest).toHaveBeenCalledWith('c1', { reason: 'not legible' })

    wrapper.vm.openAction('reject', REQUEST_ROW)
    wrapper.vm.reason = '   '
    await wrapper.vm.confirmAction()
    expect(adminApi.rejectSafeguardingRequest).toHaveBeenLastCalledWith('c1', { reason: null })
  })

  it('a lost race (404/409) tells the admin and reloads the list', async () => {
    adminApi.inspectSafeguardingRequest.mockRejectedValue({ response: { status: 409 } })
    const wrapper = await mountPage()
    wrapper.vm.openAction('inspect', REQUEST_ROW)
    await wrapper.vm.confirmAction()
    await flushPromises()
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'safeguarding.admin.actionFailed' }),
    )
    expect(wrapper.vm.actionOpen).toBe(false)
    expect(adminApi.getSafeguardingRequests).toHaveBeenCalledTimes(2)
  })

  it('the On record tab lists records and revoke calls the API', async () => {
    adminApi.revokeSafeguardingRecord.mockResolvedValue()
    const wrapper = await mountPage()
    wrapper.vm.tab = 'records'
    wrapper.vm.onTabChange()
    await flushPromises()
    expect(adminApi.getSafeguardingRecords).toHaveBeenCalledWith(0)
    expect(wrapper.vm.rows).toEqual([RECORD_ROW])

    wrapper.vm.openAction('revoke', RECORD_ROW)
    await wrapper.vm.confirmAction()
    expect(adminApi.revokeSafeguardingRecord).toHaveBeenCalledWith('c2', { reason: null })
  })
})
