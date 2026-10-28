// feat/admin-operations A7: AdminBookingsPage.vue — look up a booking by id (or ?bookingId= deep
// link), and force-complete a stuck one behind a consequences confirmation with a required note.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/admin.api', () => ({
  adminApi: { getAdminBooking: vi.fn(), forceCompleteBooking: vi.fn() },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminBookingsPage from 'src/pages/admin/AdminBookingsPage.vue'

const BOOKING_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

const STUCK = {
  bookingId: BOOKING_ID,
  status: 'COMPLETED_PENDING_CONFIRMATION',
  coachId: '11111111-2222-3333-4444-555555555555',
  playerId: 42,
  parentId: 7,
  requestedStartTime: '2026-10-01T10:00:00Z',
  requestedEndTime: '2026-10-01T11:00:00Z',
  updatedAt: '2026-10-01T11:00:00Z',
  completionRecorded: false,
  forceCompletable: true,
}

const mounted = []
async function mountPage(query = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }],
  })
  router.push({ path: '/admin/bookings', query })
  await router.isReady()
  const wrapper = mount(AdminBookingsPage, {
    global: { plugins: [router], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminBookingsPage.vue', () => {
  beforeEach(() => {
    adminApi.getAdminBooking.mockResolvedValue(STUCK)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('does not look anything up without a booking id', async () => {
    await mountPage()

    expect(adminApi.getAdminBooking).not.toHaveBeenCalled()
  })

  it('looks the booking up from a ?bookingId= deep link', async () => {
    const wrapper = await mountPage({ bookingId: BOOKING_ID })

    expect(adminApi.getAdminBooking).toHaveBeenCalledWith(BOOKING_ID)
    expect(wrapper.find('[data-testid="booking-lookup-result"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="booking-force-complete-open-btn"]').exists()).toBe(true)
  })

  it('ignores a malformed id', async () => {
    const wrapper = await mountPage({ bookingId: 'not-a-uuid' })
    wrapper.vm.bookingIdInput = 'still-not-a-uuid'
    await wrapper.vm.lookup()

    expect(adminApi.getAdminBooking).not.toHaveBeenCalled()
  })

  it('reports an unknown booking', async () => {
    adminApi.getAdminBooking.mockRejectedValue({ response: { status: 404 } })
    const wrapper = await mountPage({ bookingId: BOOKING_ID })

    expect(wrapper.vm.lookupError).toBe('admin.bookings.notFound')
    expect(wrapper.vm.booking).toBeNull()
  })

  it('offers no force-complete for a booking that is not stuck', async () => {
    adminApi.getAdminBooking.mockResolvedValue({
      ...STUCK,
      status: 'COMPLETED',
      forceCompletable: false,
    })
    const wrapper = await mountPage({ bookingId: BOOKING_ID })

    expect(wrapper.find('[data-testid="booking-force-complete-open-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="booking-not-stuck-hint"]').text()).toContain(
      'admin.bookings.notStuckHint',
    )
  })

  it('the confirmation lists the consequences and requires a note', async () => {
    const wrapper = await mountPage({ bookingId: BOOKING_ID })
    wrapper.vm.openConfirm()
    await flushPromises()

    const dialog = wrapper.find('[data-testid="booking-confirm-dialog"]')
    expect(dialog.text()).toContain('admin.bookings.consequencePayout')
    expect(dialog.text()).toContain('admin.bookings.consequencePlaceholder')
    expect(dialog.text()).toContain('admin.bookings.consequenceIrreversible')
    expect(
      wrapper.find('[data-testid="booking-force-complete-submit-btn"]').attributes('disabled'),
    ).toBeDefined()
  })

  it('force-completes with the trimmed note, reports the outcome and reloads', async () => {
    adminApi.forceCompleteBooking.mockResolvedValue({ outcome: 'FORCE_COMPLETED' })
    const wrapper = await mountPage({ bookingId: BOOKING_ID })
    wrapper.vm.openConfirm()
    wrapper.vm.note = '  ticket #42  '

    await wrapper.vm.submitForceComplete()
    await flushPromises()

    expect(adminApi.forceCompleteBooking).toHaveBeenCalledWith(BOOKING_ID, 'ticket #42')
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'admin.bookings.outcomeForceCompleted',
        type: 'positive',
      }),
    )
    expect(adminApi.getAdminBooking).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.confirmOpen).toBe(false)
  })

  it('tells the admin when the backend took no action', async () => {
    adminApi.forceCompleteBooking.mockResolvedValue({ outcome: 'ALREADY_RESOLVED' })
    const wrapper = await mountPage({ bookingId: BOOKING_ID })
    wrapper.vm.note = 'x'

    await wrapper.vm.submitForceComplete()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'admin.bookings.outcomeAlreadyResolved' }),
    )
  })

  it('maps a 409 to the conflict message', async () => {
    adminApi.forceCompleteBooking.mockRejectedValue({ response: { status: 409 } })
    const wrapper = await mountPage({ bookingId: BOOKING_ID })
    wrapper.vm.note = 'x'

    await wrapper.vm.submitForceComplete()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'admin.bookings.forceCompleteConflict',
        type: 'negative',
      }),
    )
  })
})
