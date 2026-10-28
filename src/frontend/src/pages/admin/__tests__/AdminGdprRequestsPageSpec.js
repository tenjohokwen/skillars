// feat/admin-operations A5: AdminGdprRequestsPage.vue — GDPR export / erasure request tracker:
// filters, paging, the 30-day overdue flag, and the player-scope column that only appears once the
// backend returns `playerId` (GDPR child-scope change).

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({
  adminApi: { getGdprRequests: vi.fn() },
}))

import { adminApi } from 'src/api/admin.api'
import AdminGdprRequestsPage from 'src/pages/admin/AdminGdprRequestsPage.vue'

const DAY = 24 * 60 * 60 * 1000

function row(overrides = {}) {
  return {
    requestId: '11111111-2222-3333-4444-555555555555',
    userId: 9001,
    requestType: 'EXPORT',
    status: 'COMPLETED',
    createdAt: new Date(Date.now() - 2 * DAY).toISOString(),
    completedAt: new Date(Date.now() - DAY).toISOString(),
    ...overrides,
  }
}

const mounted = []
async function mountPage() {
  const wrapper = mount(AdminGdprRequestsPage)
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminGdprRequestsPage.vue', () => {
  beforeEach(() => {
    adminApi.getGdprRequests.mockResolvedValue({ content: [row()], totalPages: 1 })
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads the first page unfiltered on mount', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getGdprRequests).toHaveBeenCalledWith({
      type: null,
      status: null,
      page: 0,
      size: 20,
    })
    expect(wrapper.vm.rows).toHaveLength(1)
  })

  it('changing a filter reloads from page 0 with both filters', async () => {
    const wrapper = await mountPage()
    wrapper.vm.currentPage = 3
    wrapper.vm.typeFilter = 'ERASURE'
    wrapper.vm.statusFilter = 'FAILED'

    await wrapper.vm.onFilterChange()

    expect(adminApi.getGdprRequests).toHaveBeenLastCalledWith({
      type: 'ERASURE',
      status: 'FAILED',
      page: 0,
      size: 20,
    })
  })

  it('flags open requests older than 30 days as overdue, not closed or recent ones', async () => {
    adminApi.getGdprRequests.mockResolvedValue({
      content: [
        row({
          requestId: 'a',
          status: 'PENDING',
          createdAt: new Date(Date.now() - 31 * DAY).toISOString(),
        }),
        row({
          requestId: 'b',
          status: 'PENDING',
          createdAt: new Date(Date.now() - 5 * DAY).toISOString(),
        }),
        row({
          requestId: 'c',
          status: 'COMPLETED',
          createdAt: new Date(Date.now() - 60 * DAY).toISOString(),
        }),
      ],
      totalPages: 1,
    })
    const wrapper = await mountPage()

    const [overdue, recent, closed] = wrapper.vm.rows
    expect(wrapper.vm.isOverdue(overdue)).toBe(true)
    expect(wrapper.vm.isOverdue(recent)).toBe(false)
    expect(wrapper.vm.isOverdue(closed)).toBe(false)
    expect(wrapper.findAll('[data-testid="gdpr-overdue-chip"]')).toHaveLength(1)
  })

  it('hides the child-profile column when rows carry no playerId field', async () => {
    const wrapper = await mountPage()

    expect(wrapper.vm.columns.map((c) => c.name)).not.toContain('playerId')
  })

  it('shows the child-profile column once rows carry a playerId field', async () => {
    adminApi.getGdprRequests.mockResolvedValue({
      content: [row({ playerId: null }), row({ requestId: 'x', playerId: 4242 })],
      totalPages: 1,
    })
    const wrapper = await mountPage()

    expect(wrapper.vm.columns.map((c) => c.name)).toContain('playerId')
    expect(wrapper.text()).toContain('4242')
  })

  it('shows an error banner when loading fails', async () => {
    adminApi.getGdprRequests.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage()

    expect(wrapper.vm.loadError).toBe(true)
    expect(wrapper.text()).toContain('admin.gdpr.loadError')
  })

  it('steps back a page when the current page came back empty', async () => {
    const wrapper = await mountPage()
    adminApi.getGdprRequests
      .mockResolvedValueOnce({ content: [], totalPages: 2 })
      .mockResolvedValueOnce({ content: [row()], totalPages: 2 })

    await wrapper.vm.loadPage(3)

    expect(adminApi.getGdprRequests).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
    expect(wrapper.vm.currentPage).toBe(2)
  })
})
