// skillars-deferred-172: the public landing pages of the email-change confirm and undo links. They must wait for
// an explicit click (link scanners render pages), end this browser's session after a success (the server
// revoked it, and the guest-only /login and /reset-password routes would otherwise bounce the user back in),
// and show one message for any unusable link.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import { createTestingPinia } from '@pinia/testing'
import enUS from 'src/i18n/en-US/index.js'

vi.mock('src/api/emailChange.api', () => ({
  emailChangeApi: { confirm: vi.fn(), undo: vi.fn() },
}))

const endSessionLocallySpy = vi.fn()
vi.mock('src/composables/useSession', () => ({
  useSession: () => ({ endSessionLocally: endSessionLocallySpy }),
}))

import { emailChangeApi } from 'src/api/emailChange.api'
import EmailChangeConfirmPage from 'src/pages/EmailChangeConfirmPage.vue'
import EmailChangeUndoPage from 'src/pages/EmailChangeUndoPage.vue'

const mounted = []
async function mountAt(component, path, { signedIn = false } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/email-change/confirm', component: EmailChangeConfirmPage },
      { path: '/email-change/undo', component: EmailChangeUndoPage },
      { path: '/login', component: { template: '<div />' } },
    ],
  })
  router.push(path)
  await router.isReady()
  const i18n = createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': enUS } })
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { userId: signedIn ? 7 : null } },
  })
  const wrapper = mount(component, { global: { plugins: [router, i18n, pinia] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('EmailChangeConfirmPage.vue', () => {
  it('does not confirm on load, only after the click', async () => {
    const wrapper = await mountAt(EmailChangeConfirmPage, '/email-change/confirm?token=abc')

    expect(emailChangeApi.confirm).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain(enUS.emailChangeLink.confirmPrompt)
  })

  it('confirms, ends the signed-in session locally and points to sign-in', async () => {
    emailChangeApi.confirm.mockResolvedValue({ code: 'email.change.confirmed' })
    const wrapper = await mountAt(EmailChangeConfirmPage, '/email-change/confirm?token=abc', {
      signedIn: true,
    })

    await wrapper.find('[data-test="ec-submit"]').trigger('click')
    await flushPromises()

    expect(emailChangeApi.confirm).toHaveBeenCalledWith('abc')
    expect(endSessionLocallySpy).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="ec-done"]').text()).toBe(enUS.emailChangeLink.confirmed)
    expect(wrapper.find('[data-test="ec-sign-in"]').exists()).toBe(true)
  })

  it('leaves a signed-out browser alone', async () => {
    emailChangeApi.confirm.mockResolvedValue({ code: 'email.change.confirmed' })
    const wrapper = await mountAt(EmailChangeConfirmPage, '/email-change/confirm?token=abc')

    await wrapper.find('[data-test="ec-submit"]').trigger('click')
    await flushPromises()

    expect(endSessionLocallySpy).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="ec-done"]').exists()).toBe(true)
  })

  it('shows the one invalid-link message and lets the user retry', async () => {
    emailChangeApi.confirm.mockRejectedValue({ response: { status: 400 } })
    const wrapper = await mountAt(EmailChangeConfirmPage, '/email-change/confirm?token=used', {
      signedIn: true,
    })

    await wrapper.find('[data-test="ec-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="ec-error"]').text()).toBe(enUS.emailChangeLink.invalid)
    expect(wrapper.find('[data-test="ec-submit"]').exists()).toBe(true)
    expect(endSessionLocallySpy).not.toHaveBeenCalled()
  })

  it('explains a link with no token and offers no button', async () => {
    const wrapper = await mountAt(EmailChangeConfirmPage, '/email-change/confirm')

    expect(wrapper.find('[data-test="ec-missing"]').text()).toBe(enUS.emailChangeLink.missingToken)
    expect(wrapper.find('[data-test="ec-submit"]').exists()).toBe(false)
  })
})

describe('EmailChangeUndoPage.vue', () => {
  it('undoes after the click and says to check the restored inbox for the reset link', async () => {
    emailChangeApi.undo.mockResolvedValue({ code: 'email.change.undone', passwordResetSent: true })
    const wrapper = await mountAt(EmailChangeUndoPage, '/email-change/undo?token=u1', {
      signedIn: true,
    })

    expect(emailChangeApi.undo).not.toHaveBeenCalled()
    await wrapper.find('[data-test="ec-submit"]').trigger('click')
    await flushPromises()

    expect(emailChangeApi.undo).toHaveBeenCalledWith('u1')
    expect(endSessionLocallySpy).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="ec-done"]').text()).toBe(enUS.emailChangeLink.undone)
  })

  it('words a suspended account differently: no reset link was sent', async () => {
    emailChangeApi.undo.mockResolvedValue({ code: 'email.change.undone', passwordResetSent: false })
    const wrapper = await mountAt(EmailChangeUndoPage, '/email-change/undo?token=u1')

    await wrapper.find('[data-test="ec-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="ec-done"]').text()).toBe(enUS.emailChangeLink.undoneSuspended)
  })

  it('shows the invalid-link message for an expired or used link', async () => {
    emailChangeApi.undo.mockRejectedValue({ response: { status: 400 } })
    const wrapper = await mountAt(EmailChangeUndoPage, '/email-change/undo?token=old')

    await wrapper.find('[data-test="ec-submit"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="ec-error"]').text()).toBe(enUS.emailChangeLink.invalid)
  })
})
