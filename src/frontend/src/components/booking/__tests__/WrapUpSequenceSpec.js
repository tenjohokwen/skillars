// skillars-12-2 Task 5.1 — first-ever coverage for WrapUpSequence.vue. Scoped narrowly to this
// story's own new behavior (Step 5, the session recap): the submit button's disabled state, the
// payload sent to bookingStore.handleSubmitWrapUp, and the new error-handling catch (Task 4.2)
// that surfaces a notification instead of leaving the wizard silently stuck open on a rejected
// submit.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

// booking.store → payment.api → @stripe/stripe-js runs a network fetch at import time in
// happy-dom (see ParentBookingsPageSpec.js for the established precedent of this exact mock).
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))

// WrapUpSequence.vue calls getDrillSuggestions on mount — stub it so onMounted resolves
// deterministically without a real HTTP round-trip. Other booking.api exports (used by
// booking.store.js) are left real, since this story's tests never call them.
vi.mock('src/api/booking.api', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, getDrillSuggestions: vi.fn().mockResolvedValue([]) }
})

// WrapUpSequence.vue also fetches session DNA on mount.
vi.mock('src/api/session.api', () => ({
  sessionApi: { getSessionPlanByBooking: vi.fn().mockResolvedValue({ sessionDna: null }) },
}))

// Notify is not registered by installQuasarPlugin — stub useQuasar so $q.notify is callable
// (see ParentBookingsPageSpec.js for the established precedent of this exact mock).
const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import WrapUpSequence from 'src/components/booking/WrapUpSequence.vue'

/** Read the component's bookingStore instance regardless of <script setup> expose shape. */
function storeOf(wrapper) {
  return wrapper.vm.bookingStore ?? wrapper.vm.$.setupState.bookingStore
}

const mounted = []
function mountComponent() {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const wrapper = mount(WrapUpSequence, {
    props: {
      bookingId: 'booking-1',
      playerId: 1,
      playerName: 'Test Player',
      isLiveMode: true,
    },
    global: {
      plugins: [pinia],
      stubs: { SessionDNAChart: true },
    },
  })
  mounted.push(wrapper)
  return wrapper
}

async function goToRecapStep(wrapper) {
  await flushPromises()
  wrapper.vm.$.setupState.step = 4
  await flushPromises()
}

function findStep5SubmitBtn(wrapper) {
  return wrapper.findAll('button').find((b) => b.text() === 'booking.wrapUp.step5Done')
}

describe('WrapUpSequence.vue — session recap step (skillars-12-2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  // skillars-12-2 code review (P7): the other specs reach step 5 via a direct setupState
  // assignment, so a typo'd `@click="step = 5"` on step 4's Next button — or a wrong pip count —
  // would leave every other spec in this file green while the recap step was actually
  // unreachable through the real UI. This test drives the transition through the real button.
  // skillars-12-7 AC12: the voice-note step is gone, so the wizard is 4 steps (attendance, ratings,
  // homework, recap) and the homework Next button leads straight to the recap.
  it('advances from the homework step to the recap step via the Next button, and renders 4 pips', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    wrapper.vm.$.setupState.step = 3
    await flushPromises()

    expect(wrapper.findAll('.wrap-up__pip')).toHaveLength(4)
    expect(wrapper.text()).toContain('booking.wrapUp.step4Title')

    const step4NextBtn = wrapper.findAll('button').find((b) => b.text() === 'common.next')
    await step4NextBtn.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('booking.wrapUp.step5Title')
    expect(findStep5SubmitBtn(wrapper)).toBeTruthy()
  })

  it('has no voice-note step, recorder or upload any more', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    for (const n of [1, 2, 3, 4]) {
      wrapper.vm.$.setupState.step = n
      await flushPromises()
      expect(wrapper.text()).not.toContain('booking.wrapUp.step3')
    }
    expect(wrapper.vm.$.setupState.startRecording).toBeUndefined()
    expect(wrapper.vm.$.setupState.voiceNoteText).toBeUndefined()
  })

  it('treats "what went well" as optional and sends it (trimmed) only when filled', async () => {
    const wrapper = mountComponent()
    const store = storeOf(wrapper)
    await goToRecapStep(wrapper)

    const textareas = wrapper.findAll('textarea')
    expect(textareas).toHaveLength(3)
    await textareas[0].setValue('Worked on first touch')
    await textareas[1].setValue('Continue weak-foot drills')
    expect(findStep5SubmitBtn(wrapper).element.disabled).toBe(false)

    await findStep5SubmitBtn(wrapper).trigger('click')
    await flushPromises()
    expect(store.handleSubmitWrapUp.mock.calls[0][1].recapWhatWentWell).toBeNull()
    expect(store.handleSubmitWrapUp.mock.calls[0][1]).not.toHaveProperty('voiceNoteText')

    store.handleSubmitWrapUp.mockClear()
    await textareas[2].setValue('  Great tempo  ')
    await findStep5SubmitBtn(wrapper).trigger('click')
    await flushPromises()
    expect(store.handleSubmitWrapUp.mock.calls[0][1].recapWhatWentWell).toBe('Great tempo')
  })

  it('blocks submit when "what went well" exceeds 500 characters', async () => {
    const wrapper = mountComponent()
    await goToRecapStep(wrapper)
    const textareas = wrapper.findAll('textarea')
    await textareas[0].setValue('a')
    await textareas[1].setValue('b')
    wrapper.vm.$.setupState.recapWhatWentWell = 'x'.repeat(501)
    await flushPromises()
    expect(findStep5SubmitBtn(wrapper).element.disabled).toBe(true)
  })

  it('disables the submit button until both recap fields are non-blank and within 500 characters', async () => {
    const wrapper = mountComponent()
    await goToRecapStep(wrapper)

    expect(findStep5SubmitBtn(wrapper).element.disabled).toBe(true)

    const textareas = wrapper.findAll('textarea')
    await textareas[0].setValue('Worked on first touch')
    await textareas[1].setValue('Continue weak-foot drills')

    expect(findStep5SubmitBtn(wrapper).element.disabled).toBe(false)

    // Mutation check (run both ways, see Dev Agent Record): a pasted overlong value past the
    // maxlength attribute must still re-disable submit via the length guard.
    wrapper.vm.$.setupState.recapWhatWeDid = 'x'.repeat(501)
    await flushPromises()
    expect(findStep5SubmitBtn(wrapper).element.disabled).toBe(true)
  })

  it('includes recapWhatWeDid and recapWhatsNext in the submit payload', async () => {
    const wrapper = mountComponent()
    const store = storeOf(wrapper)
    await goToRecapStep(wrapper)

    const textareas = wrapper.findAll('textarea')
    await textareas[0].setValue('Worked on first touch')
    await textareas[1].setValue('Continue weak-foot drills')

    await findStep5SubmitBtn(wrapper).trigger('click')
    await flushPromises()

    expect(store.handleSubmitWrapUp).toHaveBeenCalledWith(
      'booking-1',
      expect.objectContaining({
        recapWhatWeDid: 'Worked on first touch',
        recapWhatsNext: 'Continue weak-foot drills',
      }),
    )
  })

  it('surfaces a notification instead of throwing when the submit is rejected', async () => {
    const wrapper = mountComponent()
    const store = storeOf(wrapper)
    store.handleSubmitWrapUp = vi.fn().mockRejectedValue(new Error('network error'))
    await goToRecapStep(wrapper)

    const textareas = wrapper.findAll('textarea')
    await textareas[0].setValue('Worked on first touch')
    await textareas[1].setValue('Continue weak-foot drills')

    await findStep5SubmitBtn(wrapper).trigger('click')
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'booking.wrapUp.step5SubmitError' }),
    )
  })
})
