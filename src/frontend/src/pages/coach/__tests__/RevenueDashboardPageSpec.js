// fix/audit3-wallet-cashout-integrity: a new coach's first payouts are held longer than the normal hold
// (payment.payout.newCoach.releasedCount / .holdHours). The revenue page tells the coach, with the numbers
// taken from the API (RevenueSummaryDto.payoutHold) — never hard-coded — and shows when a pending payout
// is due for release.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

import RevenueDashboardPage from 'src/pages/coach/RevenueDashboardPage.vue'

const mounted = []

const summary = (payoutHold) => ({
  currency: 'EUR',
  grossEarnings: 0,
  commissionDeducted: 0,
  netPayout: 0,
  refundsIssued: 0,
  sessionCount: 0,
  heldCount: 0,
  payoutHold,
})

async function mountPage(state) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: RevenueDashboardPage }],
  })
  router.push('/')
  await router.isReady()
  const i18n = createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': enUS } })
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { payment: { transactions: [], ...state } },
  })
  const wrapper = mount(RevenueDashboardPage, { global: { plugins: [pinia, router, i18n] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('RevenueDashboardPage.vue — new-coach payout hold', () => {
  it('tells a new coach their first payouts are held longer, with the configured numbers', async () => {
    const wrapper = await mountPage({
      revenueSummary: summary({
        normalHoldHours: 48,
        newCoachHoldHours: 168,
        newCoachPayoutCount: 5,
        newCoachPayoutsRemaining: 3,
        newCoach: true,
        minReleasedNet: 20,
      }),
    })
    const banner = wrapper.find('.new-coach-hold-banner')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('your first 5 payouts are held for 7 days')
    expect(banner.text()).toContain('instead of 2 days')
    expect(wrapper.find('.new-coach-hold-remaining').text()).toBe('3 remaining.')
    // fix review: only payouts of at least minReleasedNet count — shown with the platform currency
    expect(wrapper.find('.new-coach-hold-min').text()).toContain('€20.00')
  })

  it('renders other configured values, in hours when not whole days', async () => {
    const wrapper = await mountPage({
      revenueSummary: summary({
        normalHoldHours: 36,
        newCoachHoldHours: 240,
        newCoachPayoutCount: 3,
        newCoachPayoutsRemaining: 1,
        newCoach: true,
      }),
    })
    const text = wrapper.find('.new-coach-hold-banner').text()
    expect(text).toContain(
      'first 3 payouts are held for 10 days after the session instead of 36 hours',
    )
    expect(text).toContain('1 remaining.')
  })

  it('shows no banner once the coach is past their first payouts', async () => {
    const wrapper = await mountPage({
      revenueSummary: summary({
        normalHoldHours: 48,
        newCoachHoldHours: 168,
        newCoachPayoutCount: 5,
        newCoachPayoutsRemaining: 0,
        newCoach: false,
      }),
    })
    expect(wrapper.find('.new-coach-hold-banner').exists()).toBe(false)
  })

  it('shows when a pending payout is due for release', async () => {
    const wrapper = await mountPage({
      revenueSummary: summary(null),
      transactions: [
        {
          bookingId: 'b1',
          playerName: 'P',
          sessionDate: '2026-10-20T10:00:00Z',
          grossAmount: 50,
          commissionAmount: 5,
          netAmount: 45,
          creditUsed: 0,
          status: 'PENDING_RELEASE',
          releaseAfter: '2026-10-27T10:00:00Z',
        },
        {
          bookingId: 'b2',
          playerName: 'Q',
          sessionDate: '2026-10-01T10:00:00Z',
          grossAmount: 50,
          commissionAmount: 5,
          netAmount: 45,
          creditUsed: 0,
          status: 'RELEASED',
          releaseAfter: null,
        },
      ],
    })
    const due = wrapper.findAll('.release-after')
    expect(due).toHaveLength(1)
    expect(due[0].text()).toContain('Releases')
    expect(wrapper.find('.new-coach-hold-banner').exists()).toBe(false)
  })
})
