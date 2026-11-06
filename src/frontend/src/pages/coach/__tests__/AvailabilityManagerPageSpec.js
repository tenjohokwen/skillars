// Owner decision 2026-11-06 (Pitchside SES-10, option A): deleting or changing an availability window
// never touches its booked sessions, but the coach is first told how many would fall outside their
// hours. First coverage for AvailabilityManagerPage.vue, scoped to that confirmation.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

// booking.store → payment.api → @stripe/stripe-js fetches at import time in happy-dom.
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))
vi.mock('src/api/marketplace.api', () => ({
  getProfileBuilderStatus: vi.fn().mockResolvedValue({ coachId: 'coach-1' }),
}))
vi.mock('src/api/booking.api', async (importOriginal) => ({
  ...(await importOriginal()),
  getAvailabilityWindowImpact: vi.fn(),
}))

import { getAvailabilityWindowImpact } from 'src/api/booking.api'
import { getProfileBuilderStatus } from 'src/api/marketplace.api'
import { useBookingStore } from 'src/stores/booking.store'
import AvailabilityManagerPage from 'src/pages/coach/AvailabilityManagerPage.vue'
import WeeklyCalendar from 'src/components/availability/WeeklyCalendar.vue'

const WINDOW = { id: 'w1', dayOfWeek: 2, startTime: '09:00:00', endTime: '17:00:00' }

const mounted = []
async function mountPage() {
  const wrapper = mount(AvailabilityManagerPage, {
    attachTo: document.body,
    global: {
      plugins: [
        createTestingPinia({
          createSpy: vi.fn,
          initialState: { booking: { windows: [WINDOW], blocks: [], loading: false } },
        }),
      ],
      stubs: { WeeklyCalendar: true },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, store: useBookingStore() }
}

const dialog = () => document.querySelector('[data-testid="availability-impact-dialog"]')
const byTestId = (id) => document.querySelector(`[data-testid="${id}"]`)

describe('AvailabilityManagerPage.vue: booked sessions before a delete or edit', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  // Found by Pitchside SES-08: a save before the coach's id was known reloaded coach "null" (400).
  it('offers no panel until the coach is known', async () => {
    let resolve
    getProfileBuilderStatus.mockReturnValueOnce(new Promise((r) => (resolve = r)))
    const wrapper = mount(AvailabilityManagerPage, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: { WeeklyCalendar: true },
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.find('[data-testid="availability-window-save"]').exists()).toBe(false)

    resolve({ coachId: 'coach-1' })
    await flushPromises()
    expect(wrapper.find('[data-testid="availability-window-save"]').exists()).toBe(true)
    expect(useBookingStore().loadAvailability).toHaveBeenCalledWith('coach-1', expect.any(String))
  })

  it('a failed coach lookup shows an error, never the panel', async () => {
    getProfileBuilderStatus.mockRejectedValueOnce(new Error('network'))
    const wrapper = mount(AvailabilityManagerPage, {
      global: {
        plugins: [createTestingPinia({ createSpy: vi.fn })],
        stubs: { WeeklyCalendar: true },
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    expect(wrapper.find('[data-testid="availability-load-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="availability-window-save"]').exists()).toBe(false)
    expect(useBookingStore().loadAvailability).not.toHaveBeenCalled()
  })

  it('a delete states how many booked sessions fall in the window, and deletes only on confirm', async () => {
    getAvailabilityWindowImpact.mockResolvedValue({ sessionsLeftOutside: 2 })
    const { wrapper, store } = await mountPage()

    wrapper.findComponent(WeeklyCalendar).vm.$emit('delete-window', 'w1')
    await flushPromises()

    expect(getAvailabilityWindowImpact).toHaveBeenCalledWith('w1', undefined)
    expect(byTestId('availability-impact-message').textContent).toContain(
      'booking.availability.deleteWindowImpact',
    )
    expect(store.removeWindow).not.toHaveBeenCalled()

    byTestId('availability-impact-confirm').click()
    await flushPromises()
    expect(store.removeWindow).toHaveBeenCalledWith('coach-1', 'w1')
  })

  it('a delete with nothing booked says so, and Cancel keeps the window', async () => {
    getAvailabilityWindowImpact.mockResolvedValue({ sessionsLeftOutside: 0 })
    const { wrapper, store } = await mountPage()

    wrapper.findComponent(WeeklyCalendar).vm.$emit('delete-window', 'w1')
    await flushPromises()
    expect(byTestId('availability-impact-message').textContent).toContain(
      'booking.availability.deleteWindowNoSessions',
    )

    byTestId('availability-impact-cancel').click()
    await flushPromises()
    expect(store.removeWindow).not.toHaveBeenCalled()
  })

  it('when the count cannot be loaded, nothing is deleted and the error is shown', async () => {
    getAvailabilityWindowImpact.mockRejectedValue(new Error('network'))
    const { wrapper, store } = await mountPage()

    wrapper.findComponent(WeeklyCalendar).vm.$emit('delete-window', 'w1')
    await flushPromises()

    expect(dialog()).toBeNull()
    expect(wrapper.find('[data-testid="availability-window-error"]').exists()).toBe(true)
    expect(store.removeWindow).not.toHaveBeenCalled()
  })

  it('an edit that leaves sessions outside asks first, with the proposed hours', async () => {
    getAvailabilityWindowImpact.mockResolvedValue({ sessionsLeftOutside: 1 })
    const { wrapper, store } = await mountPage()

    wrapper.findComponent(WeeklyCalendar).vm.$emit('edit-window', WINDOW)
    await flushPromises()
    wrapper.vm.$.setupState.form.endTime = '12:00'
    await wrapper.find('[data-testid="availability-window-save"]').trigger('click')
    await flushPromises()

    expect(getAvailabilityWindowImpact).toHaveBeenCalledWith('w1', {
      dayOfWeek: 2,
      startTime: '09:00:00',
      endTime: '12:00:00',
    })
    expect(byTestId('availability-impact-message').textContent).toContain(
      'booking.availability.editWindowImpact',
    )
    expect(store.editWindow).not.toHaveBeenCalled()

    byTestId('availability-impact-confirm').click()
    await flushPromises()
    expect(store.editWindow).toHaveBeenCalledWith('coach-1', 'w1', {
      dayOfWeek: 2,
      startTime: '09:00:00',
      endTime: '12:00:00',
    })
  })

  it('an edit that leaves nothing outside saves straight away', async () => {
    getAvailabilityWindowImpact.mockResolvedValue({ sessionsLeftOutside: 0 })
    const { wrapper, store } = await mountPage()

    wrapper.findComponent(WeeklyCalendar).vm.$emit('edit-window', WINDOW)
    await flushPromises()
    await wrapper.find('[data-testid="availability-window-save"]').trigger('click')
    await flushPromises()

    expect(dialog()).toBeNull()
    expect(store.editWindow).toHaveBeenCalledOnce()
  })
})
