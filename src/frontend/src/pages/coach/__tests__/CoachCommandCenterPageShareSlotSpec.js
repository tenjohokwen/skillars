// "Share slot" used to copy `${origin}/marketplace` — no '#/' (hash router → SPA root) and no coach
// or slot at all (a TODO). It now copies a deep link to the coach's own public profile with the
// slot's coach-local start as ?slot=.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})
vi.mock('src/api/booking.api', async (importOriginal) => ({
  ...(await importOriginal()),
  getMissingRecaps: vi.fn().mockResolvedValue({ count: 0, items: [] }),
}))
vi.mock('src/api/marketplace.api', async (importOriginal) => ({
  ...(await importOriginal()),
  getProfileBuilderStatus: vi.fn(),
}))

import CoachCommandCenterPage from 'src/pages/coach/CoachCommandCenterPage.vue'
import { getProfileBuilderStatus } from 'src/api/marketplace.api'

const COACH_ID = '7b0c1f5e-1111-4222-8333-944455556666'
const mounted = []
let writeText

function mountPage() {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { userId: '1', role: 'COACH' }, booking: { coachSchedule: null } },
  })
  const wrapper = mount(CoachCommandCenterPage, { shallow: true, global: { plugins: [pinia] } })
  mounted.push(wrapper)
  return wrapper
}

beforeEach(() => {
  writeText = vi.fn().mockResolvedValue()
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('CoachCommandCenterPage.vue — share slot deep link', () => {
  it("copies a hash-routed link to the coach's own public profile with the slot preselected", async () => {
    getProfileBuilderStatus.mockResolvedValue({ coachId: COACH_ID })
    const wrapper = mountPage()
    wrapper.vm.selectedWeek = '2026-11-02' // a Monday

    await wrapper.vm.shareSlot({ dayOfWeek: 3, startTime: '09:30:00' })
    await flushPromises()

    const base = window.location.origin + window.location.pathname
    expect(writeText).toHaveBeenCalledWith(
      `${base}#/coaches/${COACH_ID}?slot=${encodeURIComponent('2026-11-04T09:30')}`,
    )
    expect(notifyMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'booking.schedule.slotCopied' }),
    )
  })

  it('reports a copy failure when the coach id cannot be resolved', async () => {
    getProfileBuilderStatus.mockResolvedValue({})
    const wrapper = mountPage()

    await wrapper.vm.shareSlot({ dayOfWeek: 1, startTime: '10:00:00' })
    await flushPromises()

    expect(writeText).not.toHaveBeenCalled()
    expect(notifyMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'booking.schedule.slotCopyFailed' }),
    )
  })
})
