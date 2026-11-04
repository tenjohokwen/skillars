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

  it('a price-only save sends sessionPacks: null, so it never touches the offer (V1)', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.form.perSessionPrice = 75

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(3, {
      perSessionPrice: 75,
      sessionDurationMinutes: 60,
      sessionPacks: null,
    })
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('a price-only save from a form that loaded WITHOUT the pack does not withdraw the live offer (V1)', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog({ current: { ...CURRENT, sessionPacks: [] } })
    wrapper.vm.form.perSessionPrice = 75

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(
      3,
      expect.objectContaining({ sessionPacks: null }),
    )
    // Mutation: send packsForRequest(form.sessionPacks) unconditionally -> [] -> RED.
  })

  it('an edited pack is sent', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.form.sessionPacks[0].totalPrice = 180

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(
      3,
      expect.objectContaining({
        sessionPacks: [{ sessionCount: 5, totalPrice: 180, label: 'Starter' }],
      }),
    )
  })

  it('submit is a no-op without a positive perSessionPrice', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.perSessionPrice = 0

    await wrapper.vm.handleSubmit()

    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })

  it('a touched-but-invalid pack blocks submit with a message instead of being dropped', async () => {
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

  // ---- skillars-deferred-168: the pack is the coach's one purchasable offer ----

  it('removing the pack sends [], which withdraws the offer', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.removePack(0)

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(
      3,
      expect.objectContaining({ sessionPacks: [] }),
    )
  })

  it('a pack with no label shows an empty label and, left alone, is not re-sent', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog({
      current: { ...CURRENT, sessionPacks: [{ sessionCount: 5, totalPrice: 200, label: null }] },
    })
    expect(wrapper.vm.form.sessionPacks[0].label).toBe('')

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(
      3,
      expect.objectContaining({ sessionPacks: null }),
    )
  })

  it('an edited pack with no label is sent with a null label', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog({
      current: { ...CURRENT, sessionPacks: [{ sessionCount: 5, totalPrice: 200, label: null }] },
    })
    wrapper.vm.form.sessionPacks[0].totalPrice = 190

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(saveProfileBuilderStep).toHaveBeenCalledWith(
      3,
      expect.objectContaining({
        sessionPacks: [{ sessionCount: 5, totalPrice: 190, label: null }],
      }),
    )
  })

  it('a 200-character label from the tier page is accepted, 201 is blocked', async () => {
    saveProfileBuilderStep.mockResolvedValue({})
    const wrapper = mountDialog({
      current: {
        ...CURRENT,
        sessionPacks: [{ sessionCount: 5, totalPrice: 200, label: 'x'.repeat(200) }],
      },
    })
    await wrapper.vm.handleSubmit()
    expect(saveProfileBuilderStep).toHaveBeenCalledTimes(1)

    wrapper.vm.form.sessionPacks[0].label = 'x'.repeat(201)
    await wrapper.vm.handleSubmit()
    expect(saveProfileBuilderStep).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.packError).toBe('auth.coach.step3PackLabelTooLong')
  })

  it('blocks a price with three decimals and a per-session share that rounds to zero', async () => {
    const wrapper = mountDialog()
    wrapper.vm.form.sessionPacks = [{ sessionCount: 5, totalPrice: 10.005, label: '' }]
    await wrapper.vm.handleSubmit()
    expect(wrapper.vm.packError).toBe('auth.coach.step3PackPriceError')

    wrapper.vm.form.sessionPacks = [{ sessionCount: 100, totalPrice: 0.49, label: '' }]
    await wrapper.vm.handleSubmit()
    expect(wrapper.vm.packError).toBe('auth.coach.step3PackPerSessionError')
    expect(saveProfileBuilderStep).not.toHaveBeenCalled()
  })

  it('shows a nested server field error (sessionPacks[0].totalPrice) in the pack slot', async () => {
    saveProfileBuilderStep.mockRejectedValue({
      response: {
        status: 400,
        data: {
          errorMsg: { errorKey: 'validation.invalidData', message: 'Invalid Data' },
          fieldErrors: [
            {
              objectName: 'profileBuilderStep3Request',
              field: 'sessionPacks[0].totalPrice',
              errorMsg: {
                errorKey: 'invalid.sessionPacks[0].totalPrice',
                message: 'too many decimals',
              },
            },
          ],
        },
      },
    })
    const wrapper = mountDialog()

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(wrapper.vm.packFieldError).toBe('too many decimals')
    // Mutation: go back to hasFieldError('sessionPacks') → the nested key never matches → RED.
  })
})
