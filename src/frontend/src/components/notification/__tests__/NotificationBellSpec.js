// Notification centre (UI-gap chunk C9) — header bell: unread badge, refresh triggers, and
// click-to-open (mark read + navigate to the deep link, never to an external URL).

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/notification.api', () => ({
  notificationApi: {
    unreadCount: vi.fn(),
    list: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
  },
}))

import { notificationApi } from 'src/api/notification.api'
import NotificationBell from 'src/components/notification/NotificationBell.vue'
import { useNotificationStore } from 'src/stores/notification.store'

const STUB = { template: '<div />' }
const mounted = []

async function mountBell(unread = 3) {
  notificationApi.unreadCount.mockResolvedValue({ count: unread })
  notificationApi.markRead.mockResolvedValue(undefined)
  notificationApi.markAllRead.mockResolvedValue(undefined)
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/dashboard', component: STUB },
      { path: '/parent/bookings', component: STUB },
      { path: '/notifications', component: STUB },
    ],
  })
  router.push('/dashboard')
  await router.isReady()
  const wrapper = mount(NotificationBell, { global: { plugins: [pinia, router] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router, store: useNotificationStore() }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('NotificationBell.vue', () => {
  it('shows the unread count as a badge on mount', async () => {
    const { wrapper } = await mountBell(3)

    expect(notificationApi.unreadCount).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="notification-badge"]').text()).toBe('3')
  })

  it('hides the badge at zero and caps it at 99+', async () => {
    const zero = await mountBell(0)
    expect(zero.wrapper.find('[data-test="notification-badge"]').exists()).toBe(false)

    const many = await mountBell(250)
    expect(many.wrapper.find('[data-test="notification-badge"]').text()).toBe('99+')
  })

  it('refreshes the count on route change and on window focus', async () => {
    const { router } = await mountBell(1)
    notificationApi.unreadCount.mockClear()

    await router.push('/parent/bookings')
    await flushPromises()
    expect(notificationApi.unreadCount).toHaveBeenCalledTimes(1)

    window.dispatchEvent(new Event('focus'))
    await flushPromises()
    expect(notificationApi.unreadCount).toHaveBeenCalledTimes(2)
  })

  it('opening a notification marks it read and navigates to its deep link', async () => {
    const { wrapper, router, store } = await mountBell(1)
    const n = { id: 'n1', deepLink: '/parent/bookings', readAt: null, category: 'BOOKING' }
    store.recent = [n]
    notificationApi.unreadCount.mockResolvedValue({ count: 0 })

    await wrapper.vm.openNotification(n)
    await flushPromises()

    expect(notificationApi.markRead).toHaveBeenCalledWith('n1')
    expect(n.readAt).not.toBeNull()
    expect(router.currentRoute.value.path).toBe('/parent/bookings')
    expect(store.unreadCount).toBe(0)
  })

  it('never navigates to an external or protocol-relative deep link', async () => {
    const { wrapper, router } = await mountBell(1)

    await wrapper.vm.openNotification({ id: 'x', deepLink: '//evil.example.com', readAt: null })
    await wrapper.vm.openNotification({
      id: 'y',
      deepLink: 'https://evil.example.com',
      readAt: null,
    })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/dashboard')
  })

  it('resets the store on unmount so the next user never sees a stale count', async () => {
    const { wrapper, store } = await mountBell(4)
    expect(store.unreadCount).toBe(4)

    wrapper.unmount()
    mounted.splice(mounted.indexOf(wrapper), 1)

    expect(store.unreadCount).toBe(0)
  })
})

describe('NotificationBell.vue — failure handling', () => {
  it('still navigates when mark-read fails, and reverts the item to unread', async () => {
    const { wrapper, router } = await mountBell(1)
    notificationApi.markRead.mockRejectedValue(new Error('500'))
    const n = { id: 'n2', deepLink: '/parent/bookings', readAt: null }

    await wrapper.vm.openNotification(n)
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/parent/bookings')
    expect(n.readAt).toBeNull()
  })

  it('does not refetch the count on a query-only route change', async () => {
    const { router } = await mountBell(1)
    notificationApi.unreadCount.mockClear()

    await router.push('/dashboard?page=2')
    await flushPromises()

    expect(notificationApi.unreadCount).not.toHaveBeenCalled()
  })
})
