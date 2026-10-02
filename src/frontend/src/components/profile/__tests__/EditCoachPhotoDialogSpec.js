// skillars-deferred-139 AC3 — EditCoachPhotoDialog.vue.
//
// Upload/replace reuses CoachProfileBuilderPlaceholderPage.vue's already-tested sign/confirm/step5
// mechanics verbatim (smoke-tested here only). The new behavior this AC adds is the delete action:
// it must require an explicit confirm step before calling deleteCoachPhoto(), matching the
// confirm-before-destructive-action pattern Toggle2faDialog.vue already establishes.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/marketplace.api', () => ({
  signUpload: vi.fn(),
  confirmUpload: vi.fn(),
  saveProfileBuilderStep: vi.fn(),
  deleteCoachPhoto: vi.fn(),
}))

global.fetch = vi.fn().mockResolvedValue({ ok: true })

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import {
  signUpload,
  confirmUpload,
  saveProfileBuilderStep,
  deleteCoachPhoto,
} from 'src/api/marketplace.api'
import EditCoachPhotoDialog from 'src/components/profile/EditCoachPhotoDialog.vue'

const mounted = []
function mountDialog(props = {}, initialState = {}) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { auth: { userId: '42', role: 'COACH' }, ...initialState },
  })
  const wrapper = mount(EditCoachPhotoDialog, {
    props: { modelValue: true, current: { photoUrl: 'coach_profile/42/old.jpg' }, ...props },
    // skillars-deferred-139 review Patch 13: q-banner is deliberately NOT stubbed here — the
    // confirm-delete gate's Cancel/Confirm buttons live in q-banner's #action slot, and the
    // confirm-gate tests below need to actually click them, not just poke component state.
    // attachTo: document.body is required to reach them at all — QDialog teleports its content
    // (QPortal) onto document.body rather than rendering inside the wrapper's own root.
    attachTo: document.body,
    global: { plugins: [pinia], stubs: { 'q-file': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

function bodyText() {
  return new DOMWrapper(document.body).text()
}

function findButtonByLabel(label) {
  return new DOMWrapper(document.body).findAll('button').find((b) => b.text() === label)
}

describe('EditCoachPhotoDialog.vue (deferred-139 AC3)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('upload reuses sign -> fetch -> confirm -> saveStep5 and emits updated', async () => {
    signUpload.mockResolvedValue({ key: 'coach_profile/42/new.jpg', uploadUrl: 'https://upload' })
    confirmUpload.mockResolvedValue({ key: 'coach_profile/42/new.jpg' })
    saveProfileBuilderStep.mockResolvedValue({})

    const wrapper = mountDialog()
    const file = new File(['x'], 'photo.jpg', { type: 'image/jpeg' })
    wrapper.vm.selectedFile = file

    await wrapper.vm.handleUpload()
    await flushPromises()

    expect(signUpload).toHaveBeenCalledWith(
      expect.objectContaining({ entity: 'coach_profile', entityId: '42' }),
    )
    expect(saveProfileBuilderStep).toHaveBeenCalledWith(5, { photoUrl: 'coach_profile/42/new.jpg' })
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  // skillars-deferred-139 review Patch 13: these three tests now actually click the real
  // "Remove photo" / confirm-banner buttons rather than poking `confirmingDelete` directly — the
  // original versions asserted "deleteCoachPhoto was not called" without ever invoking anything
  // that could call it, so they would have passed even if the gate were broken.

  it('the confirm banner is not shown until "Remove photo" is clicked, and delete is not called', async () => {
    mountDialog()
    await flushPromises()

    expect(bodyText()).not.toContain('profile.confirmDeletePhoto')
    expect(deleteCoachPhoto).not.toHaveBeenCalled()
  })

  it('clicking "Remove photo" shows the confirm banner without deleting yet', async () => {
    mountDialog()
    await flushPromises()

    await findButtonByLabel('profile.removePhoto').trigger('click')
    await flushPromises()

    expect(bodyText()).toContain('profile.confirmDeletePhoto')
    expect(deleteCoachPhoto).not.toHaveBeenCalled()
  })

  it('confirming the delete calls deleteCoachPhoto and emits updated', async () => {
    deleteCoachPhoto.mockResolvedValue({})
    const wrapper = mountDialog()
    await flushPromises()
    await findButtonByLabel('profile.removePhoto').trigger('click')
    await flushPromises()

    await findButtonByLabel('common.confirm').trigger('click')
    await flushPromises()

    expect(deleteCoachPhoto).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('clicking Cancel on the confirm banner never calls delete', async () => {
    mountDialog()
    await flushPromises()
    await findButtonByLabel('profile.removePhoto').trigger('click')
    await flushPromises()

    await findButtonByLabel('common.cancel').trigger('click')
    await flushPromises()

    expect(bodyText()).not.toContain('profile.confirmDeletePhoto')
    expect(deleteCoachPhoto).not.toHaveBeenCalled()
  })

  // skillars-deferred-139 review Patch 9
  it('a rejected file (wrong type/too large) shows feedback instead of staying silently inert', async () => {
    const wrapper = mountDialog()

    wrapper.vm.onFileRejected([{ failedPropValidation: 'max-file-size' }])
    await flushPromises()

    expect(bodyText()).toContain('profile.photoRejected')
  })

  // skillars-deferred-139 review Patch 10
  it('revokes the previous preview URL when a new file is selected', () => {
    const createSpy = vi
      .spyOn(URL, 'createObjectURL')
      .mockReturnValueOnce('blob:one')
      .mockReturnValueOnce('blob:two')
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const wrapper = mountDialog()

    wrapper.vm.onFileSelected(new File(['a'], 'one.jpg', { type: 'image/jpeg' }))
    wrapper.vm.onFileSelected(new File(['b'], 'two.jpg', { type: 'image/jpeg' }))

    expect(revokeSpy).toHaveBeenCalledWith('blob:one')
    expect(wrapper.vm.previewUrl).toBe('blob:two')
    createSpy.mockRestore()
    revokeSpy.mockRestore()
  })

  it('revokes the preview URL on unmount', () => {
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const wrapper = mountDialog()
    wrapper.vm.onFileSelected(new File(['a'], 'one.jpg', { type: 'image/jpeg' }))
    const url = wrapper.vm.previewUrl

    wrapper.unmount()
    mounted.pop()

    expect(revokeSpy).toHaveBeenCalledWith(url)
    revokeSpy.mockRestore()
  })

  // skillars-deferred-139 review Patch 12
  it('derives the extension from the last dot, not the whole dotless basename', async () => {
    signUpload.mockResolvedValue({ key: 'coach_profile/42/new', uploadUrl: 'https://upload' })
    confirmUpload.mockResolvedValue({ key: 'coach_profile/42/new' })
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.selectedFile = new File(['x'], 'myphoto', { type: 'image/jpeg' })

    await wrapper.vm.handleUpload()
    await flushPromises()

    expect(signUpload).toHaveBeenCalledWith(expect.objectContaining({ extension: '' }))
  })
})
