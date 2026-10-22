// skillars-12-6 AC6/AC16 - CoachPlanNotePanel.vue: editable vs read-only rendering, save, dirty
// tracking, and every save-error branch (commit-time 409, flushed 403 concurrent-modification, locked,
// generic).

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
import CoachPlanNotePanel from 'src/components/booking/CoachPlanNotePanel.vue'

const mounted = []
function mountPanel(props = {}) {
  const wrapper = mount(CoachPlanNotePanel, {
    props: { bookingId: 'b-1', note: null, editable: true, ...props },
  })
  mounted.push(wrapper)
  return wrapper
}

async function typeAndSave(wrapper, text = 'new text') {
  await wrapper.find('textarea').setValue(text)
  await wrapper.find('[data-testid="plan-note-save"]').trigger('click')
  await flushPromises()
}

describe('CoachPlanNotePanel.vue (skillars-12-6)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('renders an editable textarea capped at 500 characters when editable', () => {
    const wrapper = mountPanel({ note: 'existing' })

    const textarea = wrapper.find('textarea')
    expect(textarea.element.value).toBe('existing')
    expect(textarea.attributes('maxlength')).toBe('500')
  })

  it('renders read-only text with the locked caption when not editable', () => {
    const wrapper = mountPanel({ note: 'existing', editable: false })

    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('[data-testid="plan-note-readonly"]').text()).toBe('existing')
    expect(wrapper.text()).toContain('coach.planNoteLocked')
  })

  it('shows the empty-state text when read-only with no note', () => {
    const wrapper = mountPanel({ note: null, editable: false })

    expect(wrapper.find('[data-testid="plan-note-readonly"]').text()).toBe('coach.planNoteEmpty')
  })

  it('disables Save until the draft differs from the saved note', async () => {
    const wrapper = mountPanel({ note: 'same' })
    const save = () => wrapper.find('[data-testid="plan-note-save"]')

    expect(save().attributes('disabled')).toBeDefined()
    await wrapper.find('textarea').setValue('changed')
    expect(save().attributes('disabled')).toBeUndefined()
  })

  it('emits dirty-change as the draft diverges from and returns to the saved note', async () => {
    const wrapper = mountPanel({ note: 'same' })

    await wrapper.find('textarea').setValue('changed')
    await wrapper.find('textarea').setValue('same')

    const values = wrapper.emitted('dirty-change').map((e) => e[0])
    expect(values).toEqual([false, true, false])
  })

  it('saves and emits saved with the server-stored (sanitised) value', async () => {
    setCoachPlanNote.mockResolvedValue({ bookingId: 'b-1', planNote: 'sanitised' })
    const wrapper = mountPanel()

    await typeAndSave(wrapper, 'typed')

    expect(setCoachPlanNote).toHaveBeenCalledWith('b-1', 'typed')
    expect(wrapper.emitted('saved')[0]).toEqual(['sanitised'])
    expect(wrapper.find('textarea').element.value).toBe('sanitised')
  })

  it('replaces the draft when the note prop changes (e.g. an applied suggestion)', async () => {
    const wrapper = mountPanel({ note: 'old' })

    await wrapper.setProps({ note: 'applied' })

    expect(wrapper.find('textarea').element.value).toBe('applied')
  })

  it.each([
    ['a commit-time 409', { response: { status: 409, data: {} } }, 'coach.planNoteConflict'],
    [
      'a flushed 403 booking.concurrentModification',
      {
        response: {
          status: 403,
          data: { errorMsg: { errorKey: 'booking.concurrentModification' } },
        },
      },
      'coach.planNoteConflict',
    ],
    [
      'a not-editable 403',
      { response: { status: 403, data: { errorMsg: { errorKey: 'booking.notEditable' } } } },
      'coach.planNoteLocked',
    ],
    [
      'a session-already-ended 403',
      { response: { status: 403, data: { errorMsg: { errorKey: 'SESSION_ALREADY_ENDED' } } } },
      'coach.planNoteLocked',
    ],
    ['an unexpected failure', { response: { status: 500, data: {} } }, 'coach.planNoteSaveError'],
  ])('notifies the right message for %s', async (_label, error, expectedKey) => {
    setCoachPlanNote.mockRejectedValue(error)
    const wrapper = mountPanel()

    await typeAndSave(wrapper)

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: expectedKey }),
    )
    expect(wrapper.emitted('saved')).toBeUndefined()
  })
})
