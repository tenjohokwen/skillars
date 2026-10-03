// skillars-deferred-141 AC4.1/AC7 — PlayerDashboardPage.vue's three tiles.
//
// AC4's decision: "pending approvals" is the player's own REQUESTED bookings, not
// videoApi.getMyApprovals() (a parent-consent concept that doesn't apply to a self-registered
// adult player). The "approvals" tile below asserts that filter directly.
//
// createTestingPinia stubs store actions by default, so loadParentBookings()/fetchCreditBalance()
// become no-ops in onMounted — tests pre-seed store state via initialState instead (same pattern
// as ParentBookingsPageSpec.js), rather than mocking the underlying API calls.
//
// Mutation checks (each re-run both ways):
//   - approvalsCount: change `=== 'REQUESTED'` to `=== 'PENDING'` → the "counts REQUESTED
//     bookings" assertion goes red (no booking in the fixture has that status). VERIFIED.
//   - formattedBalance: delete the `balance != null` guard → the "balance resolved as null"
//     case below renders "€NaN" instead of the tileUnavailable copy. This claim was originally
//     recorded here WITHOUT such a case existing: the only null-balance fixture also set
//     `error.creditBalance`, so the template's error branch pre-empted formattedBalance and the
//     mutant survived all four tests. Added in code review; now genuinely red under the mutation.

import { describe, it, expect, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
// booking.store → payment.api → @stripe/stripe-js runs a network fetch at import time in happy-dom.
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))

import PlayerDashboardPage from 'src/pages/player/PlayerDashboardPage.vue'

const mounted = []

function mountPage(initialState = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState })
  const wrapper = mount(PlayerDashboardPage, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  mounted.push(wrapper)
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

const UPCOMING = { id: 1, status: 'CONFIRMED' }
const ANOTHER_UPCOMING = { id: 2, status: 'UPCOMING' }
const REQUESTED = { id: 3, status: 'REQUESTED' }
const DECLINED = { id: 4, status: 'DECLINED' }

describe('PlayerDashboardPage.vue — tiles (deferred-141 AC4)', () => {
  it('happy path: renders upcoming-session count, credit balance, and REQUESTED-booking count', async () => {
    const wrapper = mountPage({
      booking: {
        parentBookings: [UPCOMING, ANOTHER_UPCOMING, REQUESTED, DECLINED],
        bookingsLoading: false,
        bookingsError: null,
      },
      payment: {
        creditBalance: { balance: 12.5, currency: 'EUR' },
        loading: { creditBalance: false },
        error: { creditBalance: null },
      },
    })
    await flushPromises()

    expect(wrapper.vm.upcomingSessionsCount).toBe(2)
    expect(wrapper.vm.approvalsCount).toBe(1)
    expect(wrapper.vm.formattedBalance).toBe('€12.50')
    expect(wrapper.text()).toContain('player.dashboard.upcomingSessionsCount')
    expect(wrapper.text()).toContain('player.dashboard.approvalsPending')
  })

  it('zero/empty state: no REQUESTED bookings renders the "all caught up" copy, not a count', async () => {
    const wrapper = mountPage({
      booking: { parentBookings: [UPCOMING], bookingsLoading: false, bookingsError: null },
      payment: {
        creditBalance: { balance: 0, currency: 'EUR' },
        loading: { creditBalance: false },
        error: { creditBalance: null },
      },
    })
    await flushPromises()

    expect(wrapper.vm.approvalsCount).toBe(0)
    expect(wrapper.text()).toContain('player.dashboard.approvalsNone')
    expect(wrapper.text()).not.toContain('player.dashboard.approvalsPending')
  })

  it('booking fetch failed: upcoming + approvals tiles show "unavailable", independently of the credit tile', async () => {
    const wrapper = mountPage({
      booking: { parentBookings: [], bookingsLoading: false, bookingsError: new Error('boom') },
      payment: {
        creditBalance: { balance: 5, currency: 'EUR' },
        loading: { creditBalance: false },
        error: { creditBalance: null },
      },
    })
    await flushPromises()

    const unavailableCount = wrapper
      .findAll('.player-dashboard__tile')
      .filter((tile) => tile.text().includes('player.dashboard.tileUnavailable')).length
    expect(unavailableCount).toBe(2) // upcoming-sessions tile + approvals tile, both booking-sourced
    expect(wrapper.vm.formattedBalance).toBe('€5.00') // credit tile unaffected by the booking failure
  })

  // Added in code review: the one state that actually exercises formattedBalance's `balance != null`
  // guard — the fetch RESOLVED (no error, not loading) but came back with no balance. Without this,
  // deleting the guard broke nothing, because every other null-balance fixture also sets an error and
  // the template's error branch renders instead of formattedBalance.
  it('balance resolved as null with no error: falls back to the tileUnavailable copy, not €NaN', async () => {
    const wrapper = mountPage({
      booking: { parentBookings: [UPCOMING], bookingsLoading: false, bookingsError: null },
      payment: {
        creditBalance: { balance: null, currency: 'EUR' },
        loading: { creditBalance: false },
        error: { creditBalance: null },
      },
    })
    await flushPromises()

    expect(wrapper.vm.formattedBalance).toBe('player.dashboard.tileUnavailable')
    expect(wrapper.vm.formattedBalance).not.toContain('NaN')
  })

  // Added in code review: first paint, before onMounted's fetch has flipped any store flag.
  // loading.creditBalance starts false and creditBalance starts null, which used to render the
  // "Unavailable" error copy instead of a spinner.
  it('first paint (nothing fetched yet): credit tile shows a spinner, not the unavailable copy', async () => {
    const wrapper = mountPage({
      booking: { parentBookings: [], bookingsLoading: false, bookingsError: null },
      payment: {
        creditBalance: null,
        loading: { creditBalance: false },
        error: { creditBalance: null },
      },
    })
    await flushPromises()

    expect(wrapper.vm.creditPending).toBe(true)
    const creditTile = wrapper.findAll('.player-dashboard__tile')[1]
    expect(creditTile.find('.q-spinner').exists()).toBe(true)
    expect(creditTile.text()).not.toContain('player.dashboard.tileUnavailable')
  })

  it('credit fetch failed: credit tile shows "unavailable", independently of the booking tiles', async () => {
    const wrapper = mountPage({
      booking: { parentBookings: [UPCOMING], bookingsLoading: false, bookingsError: null },
      payment: {
        creditBalance: null,
        loading: { creditBalance: false },
        error: { creditBalance: new Error('boom') },
      },
    })
    await flushPromises()

    const unavailableCount = wrapper
      .findAll('.player-dashboard__tile')
      .filter((tile) => tile.text().includes('player.dashboard.tileUnavailable')).length
    expect(unavailableCount).toBe(1) // only the credit-wallet tile
    expect(wrapper.vm.upcomingSessionsCount).toBe(1) // booking tiles unaffected by the credit failure
  })
})
