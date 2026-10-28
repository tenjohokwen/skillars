// Notification centre (UI-gap chunk C9) — public unsubscribe landing page. Must require an explicit
// click (link scanners render pages), and handle a missing / rejected token.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/notification.api', () => ({
  notificationApi: { unsubscribe: vi.fn() },
}))

import { notificationApi } from 'src/api/notification.api'
import UnsubscribePage from 'src/pages/UnsubscribePage.vue'

const mounted = []
async function mountAt(path) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/unsubscribe', component: UnsubscribePage }],
  })
  router.push(path)
  await router.isReady()
  const wrapper = mount(UnsubscribePage, { global: { plugins: [router] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('UnsubscribePage.vue', () => {
  it('does not unsubscribe on load — it waits for an explicit click', async () => {
    const wrapper = await mountAt('/unsubscribe?token=v1.a.b')

    expect(notificationApi.unsubscribe).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="unsub-confirm"]').exists()).toBe(true)
  })

  it('posts the token on confirm and shows the success state', async () => {
    notificationApi.unsubscribe.mockResolvedValue({ category: 'REMINDER' })
    const wrapper = await mountAt('/unsubscribe?token=v1.a.b')

    await wrapper.find('[data-test="unsub-confirm"]').trigger('click')
    await flushPromises()

    expect(notificationApi.unsubscribe).toHaveBeenCalledWith('v1.a.b')
    expect(wrapper.find('[data-test="unsub-done"]').exists()).toBe(true)
  })

  it('shows an error for a rejected token and lets the user retry', async () => {
    notificationApi.unsubscribe.mockRejectedValue({ response: { status: 400 } })
    const wrapper = await mountAt('/unsubscribe?token=tampered')

    await wrapper.find('[data-test="unsub-confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="unsub-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="unsub-confirm"]').exists()).toBe(true)
  })

  it('explains a link with no token and offers no button', async () => {
    const wrapper = await mountAt('/unsubscribe')

    expect(wrapper.find('[data-test="unsub-missing"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="unsub-confirm"]').exists()).toBe(false)
  })
})
