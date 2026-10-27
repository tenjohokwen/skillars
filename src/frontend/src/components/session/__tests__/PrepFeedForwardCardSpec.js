// skillars-12-6 AC13/AC16 - PrepFeedForwardCard.vue: every apply branch (empty note / non-empty note /
// already-contained / over-cap / not editable / unsaved panel edits) and the error handling.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/booking.api', () => ({
  setCoachPlanNote: vi.fn(),
}))

// Notify is not registered by installQuasarPlugin - stub useQuasar so $q.notify is callable.
const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { setCoachPlanNote } from 'src/api/booking.api'
import PrepFeedForwardCard from 'src/components/session/PrepFeedForwardCard.vue'
import CoachPlanNotePanel from 'src/components/booking/CoachPlanNotePanel.vue'

const PREP = (overrides = {}) => ({
  coachPlanNote: null,
  coachPlanNoteEditable: true,
  suggestedWhatsNext: 'Work on weak foot',
  suggestedWhatsNextSessionDate: '2026-01-01T00:00:00Z',
  ...overrides,
})

const mounted = []
function mountCard(prep) {
  const wrapper = mount(PrepFeedForwardCard, { props: { bookingId: 'b-1', prep } })
  mounted.push(wrapper)
  return wrapper
}
const apply = (w) => w.find('[data-testid="apply-button"]')

describe('PrepFeedForwardCard.vue (skillars-12-6)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('renders the suggestion text with its session date', () => {
    const wrapper = mountCard(PREP())

    expect(wrapper.find('[data-testid="suggestion-text"]').text()).toBe('Work on weak foot')
    expect(wrapper.find('[data-testid="suggestion-date"]').exists()).toBe(true)
  })

  it('renders no suggestion section when there is no suggestion, but still the note panel', () => {
    const wrapper = mountCard(
      PREP({ suggestedWhatsNext: null, suggestedWhatsNextSessionDate: null }),
    )

    expect(wrapper.find('[data-testid="suggestion-section"]').exists()).toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(true)
  })

  it('empty note: "use as plan note" saves exactly the suggestion and shows the stored note', async () => {
    setCoachPlanNote.mockResolvedValue({ bookingId: 'b-1', planNote: 'Work on weak foot' })
    const wrapper = mountCard(PREP())

    expect(apply(wrapper).text()).toBe('coach.planNoteUse')
    await apply(wrapper).trigger('click')
    await flushPromises()

    expect(setCoachPlanNote).toHaveBeenCalledWith('b-1', 'Work on weak foot')
    expect(wrapper.find('textarea').element.value).toBe('Work on weak foot')
    expect(wrapper.find('[data-testid="apply-already"]').exists()).toBe(true)
  })

  it('non-empty note: "add to plan note" appends on a new line and never overwrites', async () => {
    setCoachPlanNote.mockResolvedValue({
      bookingId: 'b-1',
      planNote: 'Passing\nWork on weak foot',
    })
    const wrapper = mountCard(PREP({ coachPlanNote: 'Passing' }))

    expect(apply(wrapper).text()).toBe('coach.planNoteAppend')
    await apply(wrapper).trigger('click')
    await flushPromises()

    expect(setCoachPlanNote).toHaveBeenCalledWith('b-1', 'Passing\nWork on weak foot')
    expect(wrapper.find('textarea').element.value).toBe('Passing\nWork on weak foot')
  })

  it('locks the note panel while an apply is in flight, so typing cannot be silently replaced', async () => {
    let resolveSave
    setCoachPlanNote.mockReturnValue(
      new Promise((resolve) => {
        resolveSave = resolve
      }),
    )
    const wrapper = mountCard(PREP())

    await apply(wrapper).trigger('click')

    expect(wrapper.find('textarea').attributes('disabled')).toBeDefined()

    resolveSave({ bookingId: 'b-1', planNote: 'Work on weak foot' })
    await flushPromises()

    expect(wrapper.find('textarea').attributes('disabled')).toBeUndefined()
  })

  it('note already containing the suggestion: no button, "already in your plan note"', () => {
    const wrapper = mountCard(PREP({ coachPlanNote: 'Passing\nWork on weak foot' }))

    expect(apply(wrapper).exists()).toBe(false)
    expect(wrapper.find('[data-testid="apply-already"]').exists()).toBe(true)
  })

  it('over the 500-char cap: the button is disabled with an explanatory caption', () => {
    const wrapper = mountCard(PREP({ coachPlanNote: 'x'.repeat(490) }))

    expect(apply(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="apply-too-long"]').exists()).toBe(true)
  })

  it('exactly at the cap is still allowed', () => {
    const suggestion = 'Work on weak foot'
    const note = 'x'.repeat(500 - suggestion.length - 1)
    const wrapper = mountCard(PREP({ coachPlanNote: note }))

    expect(apply(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('not editable: no Apply button at all and the panel is read-only', () => {
    const wrapper = mountCard(PREP({ coachPlanNoteEditable: false }))

    expect(apply(wrapper).exists()).toBe(false)
    expect(wrapper.find('textarea').exists()).toBe(false)
  })

  it('unsaved edits in the panel disable Apply (it must not discard them) with a caption', async () => {
    const wrapper = mountCard(PREP({ coachPlanNote: 'Passing' }))

    await wrapper.find('textarea').setValue('Passing and shooting')

    expect(apply(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="apply-dirty"]').exists()).toBe(true)
    expect(setCoachPlanNote).not.toHaveBeenCalled()
  })

  it('an apply failure notifies and keeps the note unchanged', async () => {
    setCoachPlanNote.mockRejectedValue({ response: { status: 409, data: {} } })
    const wrapper = mountCard(PREP({ coachPlanNote: 'Passing' }))

    await apply(wrapper).trigger('click')
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'coach.planNoteConflict' }),
    )
    expect(wrapper.find('textarea').element.value).toBe('Passing')
  })

  // skillars-deferred-165 AC8: this card's Apply write path is separate from CoachPlanNotePanel's
  // own save() — a locked 403 here must disable the Apply button too, without a page reload.
  it.each([
    [
      'booking.notEditable',
      { response: { status: 403, data: { errorMsg: { errorKey: 'booking.notEditable' } } } },
    ],
    [
      'SESSION_ALREADY_ENDED',
      { response: { status: 403, data: { errorMsg: { errorKey: 'SESSION_ALREADY_ENDED' } } } },
    ],
  ])(
    'a locked-error response (%s) disables the Apply button without a page reload',
    async (_label, error) => {
      setCoachPlanNote.mockRejectedValue(error)
      const wrapper = mountCard(PREP({ coachPlanNote: 'Passing' }))

      await apply(wrapper).trigger('click')
      await flushPromises()

      expect(apply(wrapper).attributes('disabled')).toBeDefined()
      // Mutation check: drop the `locallyLocked.value = true` assignment in apply()'s catch branch ->
      // this assertion goes RED (the button stays enabled).
    },
  )

  // Code review 2026-10-27: one lock for the whole card — the panel and Apply share it both ways.
  const LOCKED_403 = {
    response: { status: 403, data: { errorMsg: { errorKey: 'booking.notEditable' } } },
  }

  it('a locked Apply response also flips the embedded note panel read-only', async () => {
    setCoachPlanNote.mockRejectedValue(LOCKED_403)
    const wrapper = mountCard(PREP({ coachPlanNote: 'Passing' }))

    await apply(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="plan-note-readonly"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="plan-note-input"]').exists()).toBe(false)
  })

  it("a locked response to the panel's own save (its `locked` event) also disables Apply", async () => {
    const wrapper = mountCard(PREP({ coachPlanNote: 'Passing' }))
    expect(apply(wrapper).attributes('disabled')).toBeUndefined()

    // Emitted directly: driving a real panel save would also leave the panel dirty, which disables
    // Apply on its own and would hide whether the lock itself propagated.
    wrapper.findComponent(CoachPlanNotePanel).vm.$emit('locked')
    await flushPromises()

    expect(apply(wrapper).attributes('disabled')).toBeDefined()
  })

  it('a non-locked apply failure does not disable the Apply button', async () => {
    setCoachPlanNote.mockRejectedValue({ response: { status: 409, data: {} } })
    const wrapper = mountCard(PREP({ coachPlanNote: 'Different note entirely' }))

    await apply(wrapper).trigger('click')
    await flushPromises()

    expect(apply(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('resets the lock when the card is reused for a different booking', async () => {
    setCoachPlanNote.mockRejectedValue({
      response: { status: 403, data: { errorMsg: { errorKey: 'booking.notEditable' } } },
    })
    const wrapper = mount(PrepFeedForwardCard, {
      props: { bookingId: 'b-1', prep: PREP({ coachPlanNote: 'Passing' }) },
    })
    mounted.push(wrapper)
    await apply(wrapper).trigger('click')
    await flushPromises()
    expect(apply(wrapper).attributes('disabled')).toBeDefined()

    await wrapper.setProps({ bookingId: 'b-2', prep: PREP({ coachPlanNote: 'Dribbling' }) })

    expect(apply(wrapper).attributes('disabled')).toBeUndefined()
  })
})
