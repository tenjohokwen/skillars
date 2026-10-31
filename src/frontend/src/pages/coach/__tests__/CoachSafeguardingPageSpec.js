// S-09: CoachSafeguardingPage.vue - status rendering, optional upload request, error mapping, withdraw.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/marketplace.api', () => ({
  getSafeguardingStatus: vi.fn(),
  requestSafeguardingCheck: vi.fn(),
  withdrawSafeguardingCheck: vi.fn(),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import {
  getSafeguardingStatus,
  requestSafeguardingCheck,
  withdrawSafeguardingCheck,
} from 'src/api/marketplace.api'
import CoachSafeguardingPage from 'src/pages/coach/CoachSafeguardingPage.vue'

const NONE = {
  status: 'NONE',
  checkType: 'DE_EFZ',
  validUntil: null,
  renewalPending: false,
  requestedAt: null,
  uploadOnFile: false,
  uploadDeleteAt: null,
  uploadRetentionDays: 14,
  canRequest: true,
}

const mounted = []
async function mountPage() {
  const wrapper = mount(CoachSafeguardingPage, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('CoachSafeguardingPage.vue (S-09)', () => {
  beforeEach(() => {
    getSafeguardingStatus.mockResolvedValue(NONE)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('offers the request with no withdraw button when nothing is on file', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="safeguarding-status"]').text()).toContain(
      'safeguarding.coach.status.NONE',
    )
    expect(wrapper.find('[data-testid="safeguarding-request-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="safeguarding-withdraw-btn"]').exists()).toBe(false)
  })

  it('requests without a file (the video-call route) and shows the pending state', async () => {
    requestSafeguardingCheck.mockResolvedValue({
      ...NONE,
      status: 'PENDING',
      requestedAt: '2026-11-01T10:00:00Z',
      canRequest: false,
    })
    const wrapper = await mountPage()
    await wrapper.vm.submit()
    await flushPromises()

    expect(requestSafeguardingCheck).toHaveBeenCalledWith(null)
    expect(wrapper.find('[data-testid="safeguarding-pending"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="safeguarding-request-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="safeguarding-withdraw-btn"]').exists()).toBe(true)
  })

  it('sends the chosen file', async () => {
    requestSafeguardingCheck.mockResolvedValue({ ...NONE, status: 'PENDING', canRequest: false })
    const wrapper = await mountPage()
    const file = new File(['%PDF-1.7'], 'cert.pdf', { type: 'application/pdf' })
    wrapper.vm.file = file
    await wrapper.vm.submit()
    expect(requestSafeguardingCheck).toHaveBeenCalledWith(file)
  })

  it('maps a server error key to its own message', async () => {
    requestSafeguardingCheck.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'marketplace.safeguardingFileType' } } },
    })
    const wrapper = await mountPage()
    await wrapper.vm.submit()
    await flushPromises()
    expect(wrapper.find('[data-testid="safeguarding-message"]').text()).toBe(
      'safeguarding.coach.errors.fileType',
    )
  })

  it('shows the valid-until month for a verified check and the renewal button', async () => {
    getSafeguardingStatus.mockResolvedValue({
      ...NONE,
      status: 'VERIFIED',
      validUntil: '2031-03-15',
      canRequest: true,
    })
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="safeguarding-valid"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="safeguarding-request-btn"]').text()).toContain(
      'safeguarding.coach.renewBtn',
    )
  })

  it('shows neutral copy for an expired and a rejected check', async () => {
    getSafeguardingStatus.mockResolvedValue({
      ...NONE,
      status: 'EXPIRED',
      validUntil: '2026-01-01',
    })
    let wrapper = await mountPage()
    expect(wrapper.find('[data-testid="safeguarding-expired"]').exists()).toBe(true)

    getSafeguardingStatus.mockResolvedValue({ ...NONE, status: 'REJECTED' })
    wrapper = await mountPage()
    expect(wrapper.find('[data-testid="safeguarding-rejected"]').exists()).toBe(true)
  })

  it('withdraw deletes and reloads', async () => {
    getSafeguardingStatus.mockResolvedValueOnce({ ...NONE, status: 'PENDING', canRequest: false })
    withdrawSafeguardingCheck.mockResolvedValue()
    const wrapper = await mountPage()
    await wrapper.vm.withdraw()
    await flushPromises()
    expect(withdrawSafeguardingCheck).toHaveBeenCalledTimes(1)
    expect(getSafeguardingStatus).toHaveBeenCalledTimes(2)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('a failed status load shows the error banner', async () => {
    getSafeguardingStatus.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="safeguarding-load-error"]').exists()).toBe(true)
  })
})
