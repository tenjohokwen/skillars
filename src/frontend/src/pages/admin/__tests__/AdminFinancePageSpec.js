// feat/admin-operations A6: AdminFinancePage.vue — read-only finance overview for a period, and the
// per-coach revenue drill-down (coach list from the admin coach list, or a coach id).

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getFinanceOverview: vi.fn(),
    getCoachRevenue: vi.fn(),
    getEnforcementCoaches: vi.fn(),
  },
}))

import { adminApi } from 'src/api/admin.api'
import AdminFinancePage from 'src/pages/admin/AdminFinancePage.vue'

const COACH_ID = '11111111-2222-3333-4444-555555555555'

const OVERVIEW = {
  totalGrossVolume: 1234.5,
  totalCommissionCollected: 98.76,
  totalRefundCredit: 10,
  totalCashOuts: 5,
  totalStripeFees: 20.1,
  activeCoachSubscriptions: { ACADEMY: 3 },
  activePlayerSubscriptions: {},
  subscriptionRevenue: 0,
}

const REVENUE = {
  grossEarnings: 500,
  commissionDeducted: 40,
  stripeFees: 8,
  netPayout: 452,
  sessionCount: 10,
  refundsIssued: 0,
  currency: 'EUR',
  reliabilityStrikeCount: 1,
  outstandingDisputeCount: 0,
  pendingReleaseAmount: 50,
  pendingReleaseCount: 1,
}

const mounted = []
async function mountPage() {
  const wrapper = mount(AdminFinancePage, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminFinancePage.vue', () => {
  beforeEach(() => {
    adminApi.getFinanceOverview.mockResolvedValue(OVERVIEW)
    adminApi.getCoachRevenue.mockResolvedValue(REVENUE)
    adminApi.getEnforcementCoaches.mockResolvedValue({
      content: [{ coachId: COACH_ID, coachName: 'Coach A' }],
      totalPages: 1,
    })
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads the default-period overview and the active coaches on mount', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getFinanceOverview).toHaveBeenCalledWith('', '')
    expect(adminApi.getEnforcementCoaches).toHaveBeenCalledWith('ACTIVE', 0)
    expect(wrapper.find('[data-testid="finance-overview"]').exists()).toBe(true)
    expect(wrapper.vm.overviewMetrics.map((m) => m.key)).toEqual([
      'gross',
      'commission',
      'stripeFees',
      'refundCredit',
      'cashOuts',
    ])
  })

  it('does not show the always-zero subscription revenue figure', async () => {
    const wrapper = await mountPage()

    expect(wrapper.vm.overviewMetrics.some((m) => m.key === 'subscriptionRevenue')).toBe(false)
  })

  it('applies the chosen period to the overview', async () => {
    const wrapper = await mountPage()
    wrapper.vm.fromDate = '2026-09-01'
    wrapper.vm.toDate = '2026-09-30'

    await wrapper.vm.applyPeriod()

    expect(adminApi.getFinanceOverview).toHaveBeenLastCalledWith('2026-09-01', '2026-09-30')
  })

  it('refuses an inverted period', async () => {
    const wrapper = await mountPage()
    wrapper.vm.fromDate = '2026-10-01'
    wrapper.vm.toDate = '2026-09-01'

    await wrapper.vm.applyPeriod()

    expect(wrapper.vm.rangeInvalid).toBe(true)
    expect(adminApi.getFinanceOverview).toHaveBeenCalledTimes(1)
  })

  it('opens coach revenue for a listed coach using the applied period', async () => {
    const wrapper = await mountPage()
    wrapper.vm.fromDate = '2026-09-01'
    await wrapper.vm.applyPeriod()

    await wrapper.vm.openCoach(COACH_ID, 'Coach A')

    expect(adminApi.getCoachRevenue).toHaveBeenCalledWith(COACH_ID, '2026-09-01', '')
    expect(wrapper.find('[data-testid="finance-coach-revenue"]').exists()).toBe(true)
    expect(wrapper.vm.revenueMetrics.find((m) => m.key === 'sessions').value).toBe(10)
  })

  it('opens coach revenue by a typed coach id only when it is a UUID', async () => {
    const wrapper = await mountPage()
    wrapper.vm.coachIdInput = 'nope'
    await wrapper.vm.openCoachById()
    expect(adminApi.getCoachRevenue).not.toHaveBeenCalled()

    wrapper.vm.coachIdInput = ` ${COACH_ID} `
    await wrapper.vm.openCoachById()
    expect(adminApi.getCoachRevenue).toHaveBeenCalledWith(COACH_ID, '', '')
  })

  it('shows a not-found message for an unknown coach', async () => {
    adminApi.getCoachRevenue.mockRejectedValue({ response: { status: 404 } })
    const wrapper = await mountPage()

    await wrapper.vm.openCoach(COACH_ID, '')

    expect(wrapper.vm.revenueError).toBe('admin.finance.coachNotFound')
  })

  it('reloads the coach list from page 1 when the status changes', async () => {
    const wrapper = await mountPage()
    wrapper.vm.coachPage = 2
    wrapper.vm.coachStatus = 'SUSPENDED'

    await wrapper.vm.onCoachStatusChange()

    expect(adminApi.getEnforcementCoaches).toHaveBeenLastCalledWith('SUSPENDED', 0)
  })

  it("requires a start date when only 'to' is set (the backend would default 'from' to this month)", async () => {
    const wrapper = await mountPage()
    wrapper.vm.toDate = '2026-09-30'

    await wrapper.vm.applyPeriod()

    expect(wrapper.vm.rangeInvalid).toBe(true)
    expect(wrapper.vm.rangeError).toBe('admin.finance.fromRequired')
    expect(adminApi.getFinanceOverview).toHaveBeenCalledTimes(1)
  })
})
