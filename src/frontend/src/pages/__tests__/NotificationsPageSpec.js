// Notification centre (UI-gap chunk C9) — full inbox page: paging, mark-all-read, open → deep link.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/notification.api', () => ({
  notificationApi: {
    list: vi.fn(),
    unreadCount: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
  },
}))

import { notificationApi } from 'src/api/notification.api'
import NotificationsPage from 'src/pages/NotificationsPage.vue'

const STUB = { template: '<div />' }
const mounted = []

function n(id, readAt = null) {
  return {
    id,
    category: 'BOOKING',
    type: 'BOOKING_CONFIRMED',
    params: { coachDisplayName: 'C' },
    deepLink: '/parent/bookings',
    createdAt: '2026-10-01T10:00:00Z',
    readAt,
  }
}

async function mountPage(page) {
  notificationApi.list.mockResolvedValue(page)
  notificationApi.unreadCount.mockResolvedValue({ count: 0 })
  notificationApi.markRead.mockResolvedValue(undefined)
  notificationApi.markAllRead.mockResolvedValue(undefined)
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/notifications', component: STUB },
      { path: '/parent/bookings', component: STUB },
    ],
  })
  router.push('/notifications')
  await router.isReady()
  const wrapper = mount(NotificationsPage, { global: { plugins: [pinia, router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('NotificationsPage.vue', () => {
  it('loads the first page and renders one item per notification', async () => {
    const { wrapper } = await mountPage({
      content: [n('a'), n('b', '2026-10-02T00:00:00Z')],
      totalPages: 1,
    })

    expect(notificationApi.list).toHaveBeenCalledWith(0, 20)
    expect(wrapper.findAll('.notification-item')).toHaveLength(2)
    expect(wrapper.findAll('.notification-item--unread')).toHaveLength(1)
  })

  it('shows the empty state', async () => {
    const { wrapper } = await mountPage({ content: [], totalPages: 0 })

    expect(wrapper.find('[data-test="page-empty"]').exists()).toBe(true)
  })

  it('requests the selected page (1-based UI → 0-based API)', async () => {
    const { wrapper } = await mountPage({ content: [n('a')], totalPages: 3 })
    notificationApi.list.mockClear()

    wrapper.vm.currentPage = 2
    await wrapper.vm.load()

    expect(notificationApi.list).toHaveBeenCalledWith(1, 20)
  })

  it('mark-all-read clears every unread item', async () => {
    const { wrapper } = await mountPage({ content: [n('a'), n('b')], totalPages: 1 })

    await wrapper.find('[data-test="page-mark-all"]').trigger('click')
    await flushPromises()

    expect(notificationApi.markAllRead).toHaveBeenCalled()
    expect(wrapper.findAll('.notification-item--unread')).toHaveLength(0)
  })

  it('opening an item marks it read and follows its deep link', async () => {
    const { wrapper, router } = await mountPage({ content: [n('a')], totalPages: 1 })

    await wrapper.find('.notification-item').trigger('click')
    await flushPromises()

    expect(notificationApi.markRead).toHaveBeenCalledWith('a')
    expect(router.currentRoute.value.path).toBe('/parent/bookings')
  })
})
