// skillars-deferred-139 AC2 — EditCoachPricingDialog.vue.
//
// Covers: prefill from `current` (including existing session packs), successful submit calling
// saveProfileBuilderStep(3, …), and the touched-but-invalid-pack-row guard reused verbatim from
// ProfileBuilderStep3.vue (deferred-109 AC8.1) — a row the coach touched but left incomplete must
// block submit with a message, not be silently dropped.

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
import EditCoachPricingDialog from 'src/components/profile/EditCoachPricingDialog.vue'

const CURRENT = {
  perSessionPrice: 50,
  sessionDurationMinutes: 60,
  sessionPacks: [{ sessionCount: 5, totalPrice: 200, label: 'Starter' }],
}

const mounted = []
function mountDialog(props = {}) {
  const wrapper = mount(EditCoachPricingDialog, {
    props: { modelValue: true, current: CURRENT, ...props },
    global: { stubs: { 'q-banner': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('EditCoachPricingDialog.vue (deferred-139 AC2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('prefills the form from the current prop, including session packs', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.form.perSessionPrice).toBe(50)
    expect(wrapper.vm.form.sessionDurationMinutes).toBe(60)
    expect(wrapper.vm.form.sessionPacks).toEqual([
      { sessionCount: 5, totalPrice: 200, label: 'Starter' },
    ])
  })

  it('submit calls saveProfileBuilderStep(3, …) with the edited price', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.form.perSessionPrice = 75

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(3, {
      perSessionPrice: 75,
      sessionDurationMinutes: 60,
      sessionPacks: [{ sessionCount: 5, totalPrice: 200, label: 'Starter' }],
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('submit is a no-op without a positive perSessionPrice', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.perSessionPrice = 0

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })

  it('a touched-but-invalid pack row blocks submit with a message instead of being dropped', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.sessionPacks = [{ sessionCount: 5, totalPrice: null, label: '' }]

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
    expect(wrapper.vm.packError).toBe('auth.coach.step3PackInvalid')
  })

  it('removePack clears a standing packError', () => {
    const wrapper = mountDialog()
    wrapper.vm.form.sessionPacks = [{ sessionCount: 5, totalPrice: null, label: '' }]
    wrapper.vm.handleSubmit()
    expect(wrapper.vm.packError).toBe('auth.coach.step3PackInvalid')

    wrapper.vm.removePack(0)

    expect(wrapper.vm.packError).toBe('')
  })
})
