// skillars-deferred-139 AC2 — EditCoachIdentityDialog.vue.
//
// Covers: prefill from the `current` prop (the AC1 CoachProfileSelfResponse shape), a successful
// submit calling saveProfileBuilderStep(1, …) with the edited fields and emitting 'updated', and
// the required-field guard (no displayName/languages/timezone → no API call).

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/marketplace.api', () => ({
  saveProfileBuilderStep: vi.fn(),
  getSupportedTimezones: vi.fn().mockResolvedValue(['Europe/Berlin', 'America/New_York']),
  sanitizePreview: vi.fn().mockResolvedValue({ detectionFound: false }),
}))

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import { saveProfileBuilderStep } from 'src/api/marketplace.api'
import EditCoachIdentityDialog from 'src/components/profile/EditCoachIdentityDialog.vue'

const CURRENT = {
  displayName: 'Coach Name',
  bio: 'Bio text',
  city: 'Berlin',
  district: 'Mitte',
  languages: ['English'],
  canonicalTimezone: 'Europe/Berlin',
}

const mounted = []
function mountDialog(props = {}) {
  const wrapper = mount(EditCoachIdentityDialog, {
    props: { modelValue: true, current: CURRENT, ...props },
    global: { stubs: { 'q-banner': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('EditCoachIdentityDialog.vue (deferred-139 AC2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('prefills the form from the current prop', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.form.displayName).toBe('Coach Name')
    expect(wrapper.vm.form.city).toBe('Berlin')
    expect(wrapper.vm.form.languages).toEqual(['English'])
    expect(wrapper.vm.form.canonicalTimezone).toBe('Europe/Berlin')
  })

  it('submit calls saveProfileBuilderStep(1, …) with the edited fields and emits updated', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.form.displayName = 'New Name'

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(1, {
      displayName: 'New Name',
      bio: 'Bio text',
      city: 'Berlin',
      district: 'Mitte',
      languages: ['English'],
      canonicalTimezone: 'Europe/Berlin',
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('submit is a no-op without a displayName', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.displayName = ''

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
    expect(wrapper.emitted('updated')).toBeUndefined()
  })

  it('submit is a no-op without a timezone', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.canonicalTimezone = null

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })
})
