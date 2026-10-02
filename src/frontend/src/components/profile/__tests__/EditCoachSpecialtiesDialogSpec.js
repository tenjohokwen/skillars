// skillars-deferred-139 AC2 — EditCoachSpecialtiesDialog.vue.
//
// Covers: prefill from `current`, successful submit calling saveProfileBuilderStep(2, …), and the
// required-field guard (empty specialties or ageGroups blocks submit).

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/marketplace.api', () => ({
  saveProfileBuilderStep: vi.fn(),
}))

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import { saveProfileBuilderStep } from 'src/api/marketplace.api'
import EditCoachSpecialtiesDialog from 'src/components/profile/EditCoachSpecialtiesDialog.vue'

const CURRENT = { specialties: ['Dribbling'], ageGroups: ['ADULT'] }

const mounted = []
function mountDialog(props = {}) {
  const wrapper = mount(EditCoachSpecialtiesDialog, {
    props: { modelValue: true, current: CURRENT, ...props },
    global: { stubs: { 'q-banner': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('EditCoachSpecialtiesDialog.vue (deferred-139 AC2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('prefills the form from the current prop', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.form.specialties).toEqual(['Dribbling'])
    expect(wrapper.vm.form.ageGroups).toEqual(['ADULT'])
  })

  it('submit calls saveProfileBuilderStep(2, …) and emits updated', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.form.specialties = ['Passing']

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(2, {
      specialties: ['Passing'],
      ageGroups: ['ADULT'],
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('submit is a no-op with no specialties', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.specialties = []

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })

  it('submit is a no-op with no age groups', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.ageGroups = []

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })
})
