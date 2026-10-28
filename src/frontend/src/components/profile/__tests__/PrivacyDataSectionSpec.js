// C6 A5/D11: PrivacyDataSection.vue — GDPR export status/polling/download and the type-to-confirm
// erasure flow, in self scope and in a parent's child scope.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/gdpr.api', () => ({
  listMyGdprRequests: vi.fn(),
  requestMyExport: vi.fn(),
  requestMyErasure: vi.fn(),
  listChildGdprRequests: vi.fn(),
  requestChildExport: vi.fn(),
  requestChildErasure: vi.fn(),
}))

const handleLogoutSpy = vi.fn()
vi.mock('src/composables/useSession', () => ({
  useSession: () => ({ handleLogout: handleLogoutSpy }),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import * as gdprApi from 'src/api/gdpr.api'
import { usePlayerStore } from 'src/stores/playerStore'
import PrivacyDataSection from 'src/components/profile/PrivacyDataSection.vue'

const PENDING_EXPORT = { requestId: 'r1', requestType: 'EXPORT', status: 'PENDING' }
const READY_EXPORT = {
  requestId: 'r1',
  requestType: 'EXPORT',
  status: 'COMPLETED',
  expiresAt: '2099-01-01T00:00:00Z',
  downloadUrl: 'https://files.example/export.zip',
}

const mounted = []
async function mountSection(props = {}) {
  const wrapper = mount(PrivacyDataSection, {
    props,
    global: {
      plugins: [createTestingPinia({ createSpy: vi.fn })],
      stubs: { QDialog: { template: '<div><slot /></div>' } },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

function apiError(message, errorKey = 'generic.requestError') {
  return { response: { data: { errorMsg: { message, errorKey } } } }
}

describe('PrivacyDataSection.vue', () => {
  beforeEach(() => {
    gdprApi.listMyGdprRequests.mockResolvedValue([])
    gdprApi.listChildGdprRequests.mockResolvedValue([])
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('self scope loads the caller own requests, never the child endpoint', async () => {
    await mountSection()
    expect(gdprApi.listMyGdprRequests).toHaveBeenCalledTimes(1)
    expect(gdprApi.listChildGdprRequests).not.toHaveBeenCalled()
  })

  it('renders a download button with the signed URL for a ready export', async () => {
    gdprApi.listMyGdprRequests.mockResolvedValue([READY_EXPORT])
    const wrapper = await mountSection()

    const btn = wrapper.find('[data-testid="privacy-download-btn"]')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('href')).toBe(READY_EXPORT.downloadUrl)
    expect(btn.attributes('target')).toBe('_blank')
  })

  it('a completed export without a URL reads as expired; a failed one as failed', async () => {
    gdprApi.listMyGdprRequests.mockResolvedValue([
      { requestId: 'r1', requestType: 'EXPORT', status: 'COMPLETED', downloadUrl: null },
    ])
    let wrapper = await mountSection()
    expect(wrapper.find('[data-testid="privacy-export-expired"]').exists()).toBe(true)

    gdprApi.listMyGdprRequests.mockResolvedValue([
      { requestId: 'r2', requestType: 'EXPORT', status: 'FAILED' },
    ])
    wrapper = await mountSection()
    expect(wrapper.find('[data-testid="privacy-export-failed"]').exists()).toBe(true)
  })

  it('polls while the export is in flight and stops once it completes', async () => {
    vi.useFakeTimers()
    gdprApi.listMyGdprRequests
      .mockResolvedValueOnce([PENDING_EXPORT])
      .mockResolvedValueOnce([PENDING_EXPORT])
      .mockResolvedValue([READY_EXPORT])
    const wrapper = await mountSection()

    expect(wrapper.find('[data-testid="privacy-export-btn"]').attributes('disabled')).toBeDefined()

    await vi.advanceTimersByTimeAsync(3000)
    await vi.advanceTimersByTimeAsync(3000)
    expect(gdprApi.listMyGdprRequests).toHaveBeenCalledTimes(3)

    await vi.advanceTimersByTimeAsync(30000)
    expect(gdprApi.listMyGdprRequests).toHaveBeenCalledTimes(3)
    expect(wrapper.find('[data-testid="privacy-download-btn"]').exists()).toBe(true)
  })

  it('stops polling after the cap and offers a manual refresh', async () => {
    vi.useFakeTimers()
    gdprApi.listMyGdprRequests.mockResolvedValue([PENDING_EXPORT])
    const wrapper = await mountSection()

    await vi.advanceTimersByTimeAsync(3000 * 25)
    // initial load + 20 polls, then no more
    expect(gdprApi.listMyGdprRequests).toHaveBeenCalledTimes(21)
    expect(wrapper.find('[data-testid="privacy-poll-hint"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="privacy-refresh-btn"]').exists()).toBe(true)
  })

  it('stops polling when unmounted', async () => {
    vi.useFakeTimers()
    gdprApi.listMyGdprRequests.mockResolvedValue([PENDING_EXPORT])
    const wrapper = await mountSection()
    wrapper.unmount()
    mounted.length = 0

    await vi.advanceTimersByTimeAsync(30000)
    expect(gdprApi.listMyGdprRequests).toHaveBeenCalledTimes(1)
  })

  it('a 409 on export notifies already-pending and reloads the list', async () => {
    gdprApi.requestMyExport.mockRejectedValue(apiError('gdpr.requestAlreadyPending'))
    const wrapper = await mountSection()

    await wrapper.vm.$.setupState.handleRequestExport()
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'privacy.errors.alreadyPending' }),
    )
    expect(gdprApi.listMyGdprRequests).toHaveBeenCalledTimes(2)
  })

  it('keeps erasure disabled until the confirm word is typed', async () => {
    const wrapper = await mountSection()
    wrapper.vm.$.setupState.openErasureDialog()
    await flushPromises()

    const submit = () => wrapper.find('[data-testid="privacy-erasure-submit-btn"]')
    expect(submit().attributes('disabled')).toBeDefined()

    wrapper.vm.$.setupState.confirmText = 'nope'
    await flushPromises()
    expect(submit().attributes('disabled')).toBeDefined()

    // i18n mocks return the key, so the "word" is the key itself
    wrapper.vm.$.setupState.confirmText = 'privacy.confirmWord'
    await flushPromises()
    expect(submit().attributes('disabled')).toBeUndefined()
  })

  it('self erasure logs the user out after the request succeeds', async () => {
    gdprApi.requestMyErasure.mockResolvedValue({ requestId: 'e1' })
    const wrapper = await mountSection()
    wrapper.vm.$.setupState.confirmText = 'privacy.confirmWord'

    await wrapper.vm.$.setupState.handleErasure()
    await flushPromises()

    expect(gdprApi.requestMyErasure).toHaveBeenCalledTimes(1)
    expect(handleLogoutSpy).toHaveBeenCalledTimes(1)
  })

  it('maps a blocked erasure (active bookings) to an inline error and does not log out', async () => {
    gdprApi.requestMyErasure.mockRejectedValue(apiError('gdpr.activeBookingsExist'))
    const wrapper = await mountSection()
    wrapper.vm.$.setupState.confirmText = 'privacy.confirmWord'

    await wrapper.vm.$.setupState.handleErasure()
    await flushPromises()

    expect(handleLogoutSpy).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="privacy-erasure-error"]').text()).toBe(
      'privacy.errors.activeBookings',
    )
  })

  it('child scope uses the child endpoints, refreshes the child list and never logs out', async () => {
    gdprApi.requestChildExport.mockResolvedValue({ requestId: 'x1' })
    gdprApi.requestChildErasure.mockResolvedValue({ requestId: 'x2' })
    const wrapper = await mountSection({ playerId: 42, playerName: 'Kid' })
    const playerStore = usePlayerStore()

    expect(gdprApi.listChildGdprRequests).toHaveBeenCalledWith(42)
    expect(gdprApi.listMyGdprRequests).not.toHaveBeenCalled()

    await wrapper.vm.$.setupState.handleRequestExport()
    expect(gdprApi.requestChildExport).toHaveBeenCalledWith(42)
    expect(gdprApi.requestMyExport).not.toHaveBeenCalled()

    wrapper.vm.$.setupState.confirmText = 'privacy.confirmWord'
    await wrapper.vm.$.setupState.handleErasure()
    await flushPromises()

    expect(gdprApi.requestChildErasure).toHaveBeenCalledWith(42)
    expect(gdprApi.requestMyErasure).not.toHaveBeenCalled()
    expect(playerStore.fetchPlayers).toHaveBeenCalled()
    expect(handleLogoutSpy).not.toHaveBeenCalled()
    expect(wrapper.emitted('erased')).toEqual([[42]])
  })

  it('child scope maps active bookings to the child-specific message', async () => {
    gdprApi.requestChildErasure.mockRejectedValue(apiError('gdpr.activeBookingsExist'))
    const wrapper = await mountSection({ playerId: 42, playerName: 'Kid' })
    wrapper.vm.$.setupState.confirmText = 'privacy.confirmWord'

    await wrapper.vm.$.setupState.handleErasure()
    await flushPromises()

    expect(wrapper.vm.$.setupState.erasureError).toBe('privacy.errors.activeBookingsChild')
  })
})
