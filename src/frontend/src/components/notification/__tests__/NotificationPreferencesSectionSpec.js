// Notification centre (UI-gap chunk C9) — Profile page email-preference toggles.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/notification.api', () => ({
  notificationApi: { getPreferences: vi.fn(), updatePreference: vi.fn() },
}))

const notify = vi.fn()
vi.mock('quasar', async (importOriginal) => ({
  ...(await importOriginal()),
  useQuasar: () => ({ notify }),
}))

import { notificationApi } from 'src/api/notification.api'
import NotificationPreferencesSection from 'src/components/notification/NotificationPreferencesSection.vue'

const mounted = []
async function mountSection(prefs) {
  notificationApi.getPreferences.mockResolvedValue(prefs)
  const wrapper = mount(NotificationPreferencesSection)
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('NotificationPreferencesSection.vue', () => {
  it('renders one toggle per opt-out-able category returned by the backend', async () => {
    const wrapper = await mountSection([
      { category: 'REMINDER', emailEnabled: true },
      { category: 'DEVELOPMENT', emailEnabled: false },
    ])

    expect(wrapper.find('[data-test="pref-toggle-REMINDER"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="pref-toggle-DEVELOPMENT"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('notifications.preferences.transactionalNote')
  })

  it('saves a toggle through the API', async () => {
    notificationApi.updatePreference.mockResolvedValue({
      category: 'REMINDER',
      emailEnabled: false,
    })
    const wrapper = await mountSection([{ category: 'REMINDER', emailEnabled: true }])

    await wrapper.vm.toggle(wrapper.vm.preferences[0], false)

    expect(notificationApi.updatePreference).toHaveBeenCalledWith('REMINDER', false)
    expect(wrapper.vm.preferences[0].emailEnabled).toBe(false)
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('reverts the toggle when the save fails', async () => {
    notificationApi.updatePreference.mockRejectedValue(new Error('boom'))
    const wrapper = await mountSection([{ category: 'DEVELOPMENT', emailEnabled: true }])

    await wrapper.vm.toggle(wrapper.vm.preferences[0], false)

    expect(wrapper.vm.preferences[0].emailEnabled).toBe(true)
    expect(notify).toHaveBeenCalledWith(expect.objectContaining({ type: 'negative' }))
  })

  it('shows a load error instead of toggles when preferences cannot be fetched', async () => {
    notificationApi.getPreferences.mockRejectedValue(new Error('down'))
    const wrapper = mount(NotificationPreferencesSection)
    mounted.push(wrapper)
    await flushPromises()

    expect(wrapper.text()).toContain('notifications.preferences.loadError')
    expect(wrapper.find('[data-test^="pref-toggle-"]').exists()).toBe(false)
  })
})
