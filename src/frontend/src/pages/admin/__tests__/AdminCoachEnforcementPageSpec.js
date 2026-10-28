// feat/admin-moderation-and-enforcement A3 / D6: AdminCoachEnforcementPage.vue — coach list and
// status filter, enforcement profile (deep-linked via ?coachId=), suspend / reinstate / manual strike
// / delete strike confirmations, and the verification-tier editor.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getEnforcementCoaches: vi.fn(),
    getCoachEnforcementProfile: vi.fn(),
    suspendCoach: vi.fn(),
    reinstateCoach: vi.fn(),
    issueCoachStrike: vi.fn(),
    deleteCoachStrike: vi.fn(),
    setCoachVerificationTier: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminCoachEnforcementPage from 'src/pages/admin/AdminCoachEnforcementPage.vue'

const COACH_ID = '11111111-2222-3333-4444-555555555555'
const BOOKING_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

const ROW = {
  coachId: COACH_ID,
  coachName: 'Coach A',
  status: 'PENDING_REVIEW',
  verificationTier: 'BASIC',
  activeStrikes: 3,
  statusChangedAt: '2026-01-01T10:00:00Z',
}

const PROFILE = {
  coachId: COACH_ID,
  coachName: 'Coach A',
  currentStatus: 'PENDING_REVIEW',
  verificationTier: 'BASIC',
  activeStrikes: 3,
  strikeHistory: [
    {
      strikeId: 's1',
      reason: 'COACH_NO_SHOW',
      bookingId: BOOKING_ID,
      createdAt: '2026-01-01T10:00:00Z',
      acknowledged: false,
    },
  ],
  cancellationHistory: [],
  openAlerts: 1,
}

const mounted = []
async function mountPage(query = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }],
  })
  router.push({ path: '/admin/coaches', query })
  await router.isReady()
  const wrapper = mount(AdminCoachEnforcementPage, {
    global: { plugins: [router], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminCoachEnforcementPage.vue', () => {
  beforeEach(() => {
    adminApi.getEnforcementCoaches.mockResolvedValue({ content: [ROW], totalPages: 1 })
    adminApi.getCoachEnforcementProfile.mockResolvedValue(PROFILE)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads coaches under enforcement on mount', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getEnforcementCoaches).toHaveBeenCalledWith('ALL', 0)
    expect(wrapper.vm.rows).toEqual([ROW])
    expect(adminApi.getCoachEnforcementProfile).not.toHaveBeenCalled()
  })

  it('changing the status filter reloads from page 0 with that status', async () => {
    const wrapper = await mountPage()

    wrapper.vm.statusFilter = 'ACTIVE'
    await wrapper.vm.onStatusFilterChange()

    expect(adminApi.getEnforcementCoaches).toHaveBeenLastCalledWith('ACTIVE', 0)
  })

  it('opens the profile directly from a ?coachId= deep link (moderation queue link)', async () => {
    const wrapper = await mountPage({ coachId: COACH_ID })

    expect(adminApi.getCoachEnforcementProfile).toHaveBeenCalledWith(COACH_ID)
    expect(wrapper.find('[data-testid="coach-profile-content"]').text()).toContain('Coach A')
    expect(wrapper.vm.tierDraft).toBe('BASIC')
  })

  it('ignores a malformed coachId query', async () => {
    await mountPage({ coachId: 'not-a-uuid' })

    expect(adminApi.getCoachEnforcementProfile).not.toHaveBeenCalled()
  })

  it('suspend requires a reason and sends it with the notify flag', async () => {
    adminApi.suspendCoach.mockResolvedValue({
      newlySuspended: true,
      cancelledBookings: 3,
      alertedBookings: 0,
    })
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.openAction('suspend')
    expect(wrapper.vm.canSubmitAction).toBe(false)
    wrapper.vm.actionReason = '  Repeated no-shows '
    wrapper.vm.notifyCoach = false
    expect(wrapper.vm.canSubmitAction).toBe(true)
    await wrapper.vm.submitAction()
    await flushPromises()

    expect(adminApi.suspendCoach).toHaveBeenCalledWith(COACH_ID, {
      reason: 'Repeated no-shows',
      notifyCoach: false,
      cancelUpcomingBookings: true,
    })
    expect(wrapper.vm.actionDialogOpen).toBe(false)
    expect(adminApi.getCoachEnforcementProfile).toHaveBeenCalledTimes(2)
    // C2: the toast reports how many sessions the suspension cancelled and refunded.
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'admin.coaches.suspendSuccess' }),
    )
  })

  it('suspend cancels upcoming sessions by default; the admin can opt out (C2)', async () => {
    adminApi.suspendCoach.mockResolvedValue({
      newlySuspended: true,
      cancelledBookings: 1,
      alertedBookings: 0,
    })
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.openAction('suspend')
    expect(wrapper.vm.cancelUpcomingBookings).toBe(true)
    wrapper.vm.actionReason = 'Policy'
    wrapper.vm.cancelUpcomingBookings = false
    await wrapper.vm.submitAction()

    expect(adminApi.suspendCoach).toHaveBeenCalledWith(COACH_ID, {
      reason: 'Policy',
      notifyCoach: true,
      cancelUpcomingBookings: false,
    })
    // Re-opening the dialog resets the opt-out to the default.
    wrapper.vm.openAction('suspend')
    expect(wrapper.vm.cancelUpcomingBookings).toBe(true)
  })

  it('suspending an already-suspended coach says nothing changed', async () => {
    adminApi.suspendCoach.mockResolvedValue({
      newlySuspended: false,
      cancelledBookings: 0,
      alertedBookings: 0,
    })
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.openAction('suspend')
    wrapper.vm.actionReason = 'Policy'
    await wrapper.vm.submitAction()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: 'admin.coaches.alreadySuspended' }),
    )
  })

  it('a suspend refused because a payment is in flight shows the retry hint (C2)', async () => {
    adminApi.suspendCoach.mockRejectedValue({
      response: { status: 409, data: { errorMsg: { message: 'admin.coaches.paymentInProgress' } } },
    })
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.openAction('suspend')
    wrapper.vm.actionReason = 'Policy'
    await wrapper.vm.submitAction()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({
        type: 'negative',
        message: 'admin.coaches.paymentInProgress',
      }),
    )
    expect(wrapper.vm.actionDialogOpen).toBe(true)
  })

  it('reinstate sends the reason', async () => {
    adminApi.reinstateCoach.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    expect(wrapper.vm.canReinstate).toBe(true)
    wrapper.vm.openAction('reinstate')
    wrapper.vm.actionReason = 'Cleared'
    await wrapper.vm.submitAction()

    expect(adminApi.reinstateCoach).toHaveBeenCalledWith(COACH_ID, { reason: 'Cleared' })
  })

  it('a manual strike needs a valid booking UUID and a reason', async () => {
    adminApi.issueCoachStrike.mockResolvedValue({ strikeId: 's2' })
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.openAction('strike')
    wrapper.vm.strikeBookingId = 'nope'
    wrapper.vm.strikeReason = 'COACH_NO_SHOW'
    expect(wrapper.vm.canSubmitAction).toBe(false)

    wrapper.vm.strikeBookingId = BOOKING_ID
    expect(wrapper.vm.canSubmitAction).toBe(true)
    await wrapper.vm.submitAction()

    expect(adminApi.issueCoachStrike).toHaveBeenCalledWith(COACH_ID, {
      bookingId: BOOKING_ID,
      reason: 'COACH_NO_SHOW',
    })
  })

  it('a rejected manual strike (400) shows the booking-specific error', async () => {
    adminApi.issueCoachStrike.mockRejectedValue({ response: { status: 400 } })
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.openAction('strike')
    wrapper.vm.strikeBookingId = BOOKING_ID
    wrapper.vm.strikeReason = 'COACH_NO_SHOW'
    await wrapper.vm.submitAction()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'admin.coaches.strikeInvalid' }),
    )
    expect(wrapper.vm.actionDialogOpen).toBe(true)
  })

  it('deleting a strike sends its id and the reason', async () => {
    adminApi.deleteCoachStrike.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.openAction('deleteStrike', PROFILE.strikeHistory[0])
    wrapper.vm.actionReason = 'Issued in error'
    await wrapper.vm.submitAction()

    expect(adminApi.deleteCoachStrike).toHaveBeenCalledWith(COACH_ID, 's1', 'Issued in error')
  })

  it('saving a new verification tier sends tier and note, then reloads', async () => {
    adminApi.setCoachVerificationTier.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.tierDraft = 'TRUSTED'
    wrapper.vm.tierReason = ' ID verified '
    await wrapper.vm.saveTier()
    await flushPromises()

    expect(adminApi.setCoachVerificationTier).toHaveBeenCalledWith(COACH_ID, {
      tier: 'TRUSTED',
      reason: 'ID verified',
    })
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
    expect(adminApi.getEnforcementCoaches).toHaveBeenCalledTimes(2)
  })

  it('an empty tier note is sent as null', async () => {
    adminApi.setCoachVerificationTier.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openProfile(COACH_ID)

    wrapper.vm.tierDraft = 'FEATURED'
    await wrapper.vm.saveTier()

    expect(adminApi.setCoachVerificationTier).toHaveBeenCalledWith(COACH_ID, {
      tier: 'FEATURED',
      reason: null,
    })
  })
})
