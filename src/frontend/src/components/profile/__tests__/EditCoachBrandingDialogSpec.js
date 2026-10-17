// skillars-deferred-151 AC3 — EditCoachBrandingDialog.vue.
//
// Mirrors EditCoachPhotoDialog.vue's upload mechanics but with `entity: 'coach_branding'`, plus a
// hex-colour field. Covers the colour-only-edit-preserves-logo fix (Finding 3 #3): saveBranding is
// an unconditional full replace, so resending `null` for logoKey on a colour-only edit would
// silently wipe an existing logo — the dialog must resend `current.logoKey` unchanged when no new
// file is selected.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/marketplace.api', () => ({
  signUpload: vi.fn(),
  confirmUpload: vi.fn(),
}))
vi.mock('src/api/development.api', () => ({
  saveCoachBranding: vi.fn(),
}))

global.fetch = vi.fn().mockResolvedValue({ ok: true })

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import { signUpload, confirmUpload } from 'src/api/marketplace.api'
import { saveCoachBranding } from 'src/api/development.api'
import EditCoachBrandingDialog from 'src/components/profile/EditCoachBrandingDialog.vue'

const mounted = []
function mountDialog(props = {}) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { userId: '42', role: 'COACH' } },
  })
  const wrapper = mount(EditCoachBrandingDialog, {
    props: {
      modelValue: true,
      current: { logoSignedUrl: null, logoKey: null, brandColour: null },
      ...props,
    },
    attachTo: document.body,
    global: { plugins: [pinia], stubs: { 'q-file': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

function findButtonByLabel(label) {
  return new DOMWrapper(document.body).findAll('button').find((b) => b.text() === label)
}

describe('EditCoachBrandingDialog.vue (deferred-151 AC3)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
    // skillars-deferred-152 code review (2026-10-17): restore the default success stub here, not
    // at the end of the test body that overrides it. vi.clearAllMocks() clears calls, not
    // implementations, so a failing assertion in the res.ok test below used to leave
    // { ok: false, status: 500 } installed for every later test in this file.
    global.fetch = vi.fn().mockResolvedValue({ ok: true })
  })

  it('prefills brandColour from the current prop on open', () => {
    const wrapper = mountDialog({ current: { logoKey: 'k', brandColour: '#112233' } })
    expect(wrapper.vm.brandColour).toBe('#112233')
  })

  it('uploads a new logo via sign -> fetch -> confirm, then saves with the new key', async () => {
    signUpload.mockResolvedValue({ key: 'coach_branding/42/new.png', uploadUrl: 'https://upload' })
    confirmUpload.mockResolvedValue({ key: 'coach_branding/42/new.png' })
    saveCoachBranding.mockResolvedValue()

    const wrapper = mountDialog()
    wrapper.vm.selectedFile = new File(['x'], 'logo.png', { type: 'image/png' })
    wrapper.vm.brandColour = '#3B82F6'

    await wrapper.vm.handleSave()
    await flushPromises()

    expect(signUpload).toHaveBeenCalledWith(
      expect.objectContaining({ entity: 'coach_branding', entityId: '42' }),
    )
    expect(saveCoachBranding).toHaveBeenCalledWith('coach_branding/42/new.png', '#3B82F6')
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('a colour-only edit resends the EXISTING logoKey unchanged, not null', async () => {
    saveCoachBranding.mockResolvedValue()
    const wrapper = mountDialog({
      current: {
        logoSignedUrl: 'https://cdn/old.png',
        logoKey: 'coach_branding/42/old.png',
        brandColour: '#000000',
      },
    })
    wrapper.vm.brandColour = '#ffffff'

    await wrapper.vm.handleSave()
    await flushPromises()

    expect(signUpload).not.toHaveBeenCalled()
    expect(saveCoachBranding).toHaveBeenCalledWith('coach_branding/42/old.png', '#ffffff')
  })

  it('rejects an invalid hex colour client-side and does not call the API', async () => {
    const wrapper = mountDialog()
    wrapper.vm.brandColour = 'not-a-colour'
    await flushPromises()

    const saveBtn = findButtonByLabel('common.save')
    expect(saveBtn.attributes('disabled')).toBeDefined()
  })

  it('an empty colour is valid (clears the colour)', async () => {
    saveCoachBranding.mockResolvedValue()
    const wrapper = mountDialog({ current: { logoKey: 'k', brandColour: '#000000' } })
    wrapper.vm.brandColour = ''

    await wrapper.vm.handleSave()
    await flushPromises()

    expect(saveCoachBranding).toHaveBeenCalledWith('k', null)
  })

  // skillars-deferred-152 AC9: the global.fetch mock above (`{ ok: true }`) tested nothing about
  // response handling until this story's `if (!res.ok) throw ...` check was added -- a failed PUT
  // previously flowed straight through to confirmUpload/saveCoachBranding unchecked.
  it('a failed logo upload PUT (res.ok false) surfaces an error and does not proceed to confirm/save', async () => {
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500 })
    signUpload.mockResolvedValue({ key: 'coach_branding/42/new.png', uploadUrl: 'https://upload' })

    const wrapper = mountDialog()
    wrapper.vm.selectedFile = new File(['x'], 'logo.png', { type: 'image/png' })

    await wrapper.vm.handleSave()
    await flushPromises()

    expect(confirmUpload).not.toHaveBeenCalled()
    expect(saveCoachBranding).not.toHaveBeenCalled()
    expect(wrapper.vm.hasError).toBe(true)
    expect(wrapper.emitted('updated')).toBeUndefined()
  })

  // skillars-deferred-153 AC9: the thrown Error carries no .response, so the generic
  // error.network banner was already correct user-facing behavior (not changed by this story) —
  // the gap was that nothing logged the captured status, making a failed upload undiagnosable
  // from logs. Spies on console.error rather than asserting on hasError/the banner text (already
  // covered by the test above).
  it('a failed logo upload PUT logs the error via console.error', async () => {
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    global.fetch = vi.fn().mockResolvedValue({ ok: false, status: 500 })
    signUpload.mockResolvedValue({ key: 'coach_branding/42/new.png', uploadUrl: 'https://upload' })

    const wrapper = mountDialog()
    wrapper.vm.selectedFile = new File(['x'], 'logo.png', { type: 'image/png' })

    await wrapper.vm.handleSave()
    await flushPromises()

    expect(consoleErrorSpy).toHaveBeenCalledWith(
      'Coach branding logo upload failed',
      expect.objectContaining({ message: expect.stringContaining('500') }),
    )

    consoleErrorSpy.mockRestore()
  })

  it('shows a security.featureGated error if the tier changes mid-session', async () => {
    saveCoachBranding.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'security.featureGated', message: 'gated' } } },
    })
    const wrapper = mountDialog()

    await wrapper.vm.handleSave()
    await flushPromises()

    expect(wrapper.vm.hasError).toBe(true)
  })
})
