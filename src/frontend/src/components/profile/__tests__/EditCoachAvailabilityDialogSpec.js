// skillars-deferred-139 AC2 — EditCoachAvailabilityDialog.vue.
//
// Covers: prefill from `current` (availabilityWindows + canonicalTimezone), successful submit
// calling saveProfileBuilderStep(4, …), and the required-fields guard (no windows, or a window
// missing a day/time, blocks submit).

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'

vi.mock('src/api/marketplace.api', () => ({
  saveProfileBuilderStep: vi.fn(),
}))

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import { saveProfileBuilderStep } from 'src/api/marketplace.api'
import EditCoachAvailabilityDialog from 'src/components/profile/EditCoachAvailabilityDialog.vue'

const CURRENT = {
  canonicalTimezone: 'Europe/Berlin',
  availabilityWindows: [{ dayOfWeek: 1, startTime: '09:00:00', endTime: '11:00:00' }],
}

const mounted = []
function mountDialog(props = {}) {
  const wrapper = mount(EditCoachAvailabilityDialog, {
    props: { modelValue: true, current: CURRENT, ...props },
    // attachTo: document.body — QDialog teleports its content onto document.body, so DOM-text
    // assertions (the read-only-timezone test below) need it attached to find anything at all.
    attachTo: document.body,
    global: { stubs: { 'q-banner': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('EditCoachAvailabilityDialog.vue (deferred-139 AC2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('prefills windows and timezone from the current prop', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.form.windows).toEqual([
      { dayOfWeek: 1, startTime: '09:00:00', endTime: '11:00:00' },
    ])
    expect(wrapper.vm.form.canonicalTimezone).toBe('Europe/Berlin')
  })

  it('submit calls saveProfileBuilderStep(4, …) with the current timezone stamped onto every window', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.form.windows = [{ dayOfWeek: 2, startTime: '10:00', endTime: '12:00' }]

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(4, {
      windows: [
        { dayOfWeek: 2, startTime: '10:00', endTime: '12:00', canonicalTimezone: 'Europe/Berlin' },
      ],
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('submit is a no-op with no windows', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.windows = []

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })

  it('submit is a no-op when a window is missing a day', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.windows = [{ dayOfWeek: null, startTime: '09:00', endTime: '10:00' }]

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })

  // skillars-deferred-139 review D1: the timezone is shown as plain text, not bound to any
  // editable form control — form.canonicalTimezone is read but never written by this dialog.
  it('renders the profile timezone read-only rather than an editable picker', async () => {
    mountDialog()
    await flushPromises()
    const body = new DOMWrapper(document.body)

    expect(body.text()).toContain('Europe/Berlin')
    expect(body.text()).toContain('profile.availabilityTimezoneReadonlyHint')
  })
})
