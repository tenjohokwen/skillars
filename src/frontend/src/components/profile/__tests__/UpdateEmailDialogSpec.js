// skillars-deferred-172: UpdateEmailDialog.vue — requesting an email change no longer signs the user out (nothing
// changes until the link mailed to the new address is opened), and the dialog words the two refusals that carry
// no translatable key itself: the undo-window hold (409, with its end date) and the per-account limit (429).

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US/index.js'

vi.mock('src/api/profile.api', () => ({
  profileApi: { updateEmail: vi.fn() },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { profileApi } from 'src/api/profile.api'
import UpdateEmailDialog from 'src/components/profile/UpdateEmailDialog.vue'

const mounted = []
async function mountDialog() {
  const i18n = createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': enUS } })
  const wrapper = mount(UpdateEmailDialog, {
    props: { modelValue: false, currentEmail: 'Me@Example.com' },
    global: { plugins: [i18n], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  const state = wrapper.vm.$.setupState
  state.form.newEmail = 'new@example.com'
  state.form.password = 'secret-pass'
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('UpdateEmailDialog.vue', () => {
  it('on success tells the user to open the link and stays signed in', async () => {
    profileApi.updateEmail.mockResolvedValue({ msgKey: 'email.change.requested' })
    const wrapper = await mountDialog()

    await wrapper.vm.$.setupState.handleSubmit()
    await flushPromises()

    expect(profileApi.updateEmail).toHaveBeenCalledWith(
      'Me@Example.com',
      'new@example.com',
      'secret-pass',
    )
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'positive', message: enUS.success.emailChangeInitiated }),
    )
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('shows the undo-window refusal with its end date', async () => {
    profileApi.updateEmail.mockRejectedValue({
      response: {
        status: 409,
        data: {
          errorMsg: { errorKey: 'email.change.recentChange', message: '2026-11-12T10:00:00Z' },
        },
      },
    })
    const wrapper = await mountDialog()

    await wrapper.vm.$.setupState.handleSubmit()
    await flushPromises()

    const text = wrapper.find('[data-test="email-own-error"]').text()
    expect(text).toContain('You changed your email recently.')
    expect(text).toContain('2026')
    expect(text).not.toContain('{date}')
  })

  it('shows the per-account limit', async () => {
    profileApi.updateEmail.mockRejectedValue({ response: { status: 429, data: {} } })
    const wrapper = await mountDialog()

    await wrapper.vm.$.setupState.handleSubmit()
    await flushPromises()

    expect(wrapper.find('[data-test="email-own-error"]').text()).toBe(
      enUS.profile.emailChange.tooManyRequests,
    )
  })

  it('treats the current address in other letters as the same address', async () => {
    const wrapper = await mountDialog()

    expect(wrapper.vm.$.setupState.notSameEmail(' me@EXAMPLE.com')).toBe(enUS.validation.sameEmail)
    expect(wrapper.vm.$.setupState.notSameEmail('other@example.com')).toBe(true)
  })
})
