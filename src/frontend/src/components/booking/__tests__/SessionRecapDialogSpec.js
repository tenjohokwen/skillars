// skillars-12-7 AC6 -- SessionRecapDialog.vue: load, editable / closed-window / error states, validation,
// save payload, errorKey-mapped failures, and the stale-response guard.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/booking.api', () => ({
  getSessionRecap: vi.fn(),
  updateSessionRecap: vi.fn(),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { getSessionRecap, updateSessionRecap } from 'src/api/booking.api'
import SessionRecapDialog from 'src/components/booking/SessionRecapDialog.vue'

const RECAP = (o = {}) => ({
  data: {
    bookingId: 'b1',
    whatWeDid: 'Passing',
    whatsNext: 'Finishing',
    whatWentWell: null,
    editable: true,
    editableUntil: '2030-01-01T00:00:00Z',
    ...o,
  },
})

const mounted = []
async function mountDialog(props = {}) {
  const wrapper = mount(SessionRecapDialog, {
    props: { modelValue: true, bookingId: 'b1', playerName: 'Alex', ...props },
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const save = (w) => w.find('[data-testid="recap-save"]')

describe('SessionRecapDialog.vue (skillars-12-7)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads the recap and shows it editable with a save button', async () => {
    getSessionRecap.mockResolvedValue(RECAP())
    const w = await mountDialog()

    expect(getSessionRecap).toHaveBeenCalledWith('b1')
    expect(w.find('textarea[data-testid="recap-what-we-did"]').element.value).toBe('Passing')
    expect(save(w).exists()).toBe(true)
    expect(w.find('[data-testid="recap-closed"]').exists()).toBe(false)
  })

  it('shows the closed-window message read-only, with no save button, when not editable', async () => {
    getSessionRecap.mockResolvedValue(RECAP({ editable: false }))
    const w = await mountDialog()

    expect(w.find('[data-testid="recap-closed"]').exists()).toBe(true)
    expect(save(w).exists()).toBe(false)
    expect(w.find('textarea[data-testid="recap-what-we-did"]').element.readOnly).toBe(true)
  })

  it('shows the erased message, not the window message, when the server says the recap was erased', async () => {
    getSessionRecap.mockResolvedValue(
      RECAP({ editable: false, notEditableReason: 'booking.recapNotEditableErased' }),
    )
    const w = await mountDialog()

    const closed = w.find('[data-testid="recap-closed"]')
    expect(closed.text()).toBe('booking.recap.erasedError')
    expect(save(w).exists()).toBe(false)
  })

  it('shows the disputed message when the server says a dispute locks the recap (skillars-deferred-162)', async () => {
    getSessionRecap.mockResolvedValue(
      RECAP({ editable: false, notEditableReason: 'booking.recapNotEditableDisputed' }),
    )
    const w = await mountDialog()

    expect(w.find('[data-testid="recap-closed"]').text()).toBe('booking.recap.disputedError')
    expect(save(w).exists()).toBe(false)
  })

  it('maps the disputed errorKey on save to the disputed message', async () => {
    getSessionRecap.mockResolvedValue(RECAP())
    updateSessionRecap.mockRejectedValueOnce({
      response: {
        status: 403,
        data: { errorMsg: { errorKey: 'booking.recapNotEditableDisputed' } },
      },
    })
    const w = await mountDialog()

    await save(w).trigger('click')
    await flushPromises()
    expect(notifySpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: 'negative', message: 'booking.recap.disputedError' }),
    )
    expect(w.emitted('saved')).toBeUndefined()
  })

  it('shows the dated closed-window message when the window closed and editableUntil is known', async () => {
    getSessionRecap.mockResolvedValue(
      RECAP({ editable: false, notEditableReason: 'booking.recapEditWindowClosed' }),
    )
    const w = await mountDialog()

    expect(w.find('[data-testid="recap-closed"]').text()).toBe('booking.recap.closedWindow')
  })

  // No editableUntil must not render "…until ." — fall back to the date-free key.
  it('falls back to the date-free closed message when editableUntil is missing', async () => {
    getSessionRecap.mockResolvedValue(
      RECAP({
        editable: false,
        editableUntil: null,
        notEditableReason: 'booking.recapEditWindowClosed',
      }),
    )
    const w = await mountDialog()

    expect(w.find('[data-testid="recap-closed"]').text()).toBe('booking.recap.closedWindowNoDate')
  })

  it('renders a load error state', async () => {
    getSessionRecap.mockRejectedValue(new Error('boom'))
    const w = await mountDialog()

    expect(w.find('[data-testid="recap-load-error"]').exists()).toBe(true)
  })

  it('disables save until both required fields are non-blank; what went well stays optional', async () => {
    getSessionRecap.mockResolvedValue(RECAP({ whatWeDid: null, whatsNext: null }))
    const w = await mountDialog()
    expect(save(w).element.disabled).toBe(true)

    await w.find('textarea[data-testid="recap-what-we-did"]').setValue('did')
    expect(save(w).element.disabled).toBe(true)
    await w.find('textarea[data-testid="recap-whats-next"]').setValue('next')
    expect(save(w).element.disabled).toBe(false)
  })

  it('saves trimmed values, sends null for a blank what-went-well, then emits saved and closes', async () => {
    getSessionRecap.mockResolvedValue(RECAP())
    updateSessionRecap.mockResolvedValue({})
    const w = await mountDialog()
    await w.find('textarea[data-testid="recap-what-we-did"]').setValue('  new did  ')

    await save(w).trigger('click')
    await flushPromises()

    expect(updateSessionRecap).toHaveBeenCalledWith('b1', {
      whatWeDid: 'new did',
      whatsNext: 'Finishing',
      whatWentWell: null,
    })
    expect(w.emitted('saved')).toEqual([['b1']])
    expect(w.emitted('update:modelValue')).toEqual([[false]])
  })

  it('maps the closed-window errorKey to its dedicated message and stays open', async () => {
    getSessionRecap.mockResolvedValue(RECAP())
    updateSessionRecap.mockRejectedValue({
      response: { status: 403, data: { errorMsg: { errorKey: 'booking.recapEditWindowClosed' } } },
    })
    const w = await mountDialog()

    await save(w).trigger('click')
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'booking.recap.windowClosedError' }),
    )
    expect(w.emitted('saved')).toBeUndefined()
  })

  it('maps the erased errorKey, and falls back to the generic message otherwise', async () => {
    getSessionRecap.mockResolvedValue(RECAP())
    updateSessionRecap.mockRejectedValueOnce({
      response: { status: 403, data: { errorMsg: { errorKey: 'booking.recapNotEditableErased' } } },
    })
    updateSessionRecap.mockRejectedValueOnce(new Error('network'))
    const w = await mountDialog()

    await save(w).trigger('click')
    await flushPromises()
    expect(notifySpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ message: 'booking.recap.erasedError' }),
    )
    await save(w).trigger('click')
    await flushPromises()
    expect(notifySpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ message: 'booking.recap.saveError' }),
    )
  })

  // skillars-deferred-163 AC2.2: a force-completed placeholder's GET response has empty recap
  // fields, so the prefill applies safely into all three.
  it('applies the prefill into fields the load left empty', async () => {
    getSessionRecap.mockResolvedValue(
      RECAP({ whatWeDid: null, whatsNext: null, whatWentWell: null }),
    )
    const w = await mountDialog({
      prefill: {
        whatWeDid: 'Recovered did',
        whatsNext: 'Recovered next',
        whatWentWell: 'Recovered well',
      },
    })

    expect(w.find('textarea[data-testid="recap-what-we-did"]').element.value).toBe('Recovered did')
    expect(w.find('textarea[data-testid="recap-whats-next"]').element.value).toBe('Recovered next')
    expect(w.find('textarea[data-testid="recap-what-went-well"]').element.value).toBe(
      'Recovered well',
    )
  })

  // The prefill must never clobber real, already-saved recap text.
  it('does not overwrite fields the load already populated with real text', async () => {
    getSessionRecap.mockResolvedValue(RECAP({ whatWeDid: 'Real did', whatsNext: 'Real next' }))
    const w = await mountDialog({
      prefill: { whatWeDid: 'Recovered did', whatsNext: 'Recovered next' },
    })

    expect(w.find('textarea[data-testid="recap-what-we-did"]').element.value).toBe('Real did')
    expect(w.find('textarea[data-testid="recap-whats-next"]').element.value).toBe('Real next')
  })

  // Code review 2026-10-27: a read-only recap (window closed, disputed, erased) must not show the
  // carried-over text -- it could never be saved and would look like a saved recap.
  it('does not apply the prefill when the recap is not editable', async () => {
    getSessionRecap.mockResolvedValue(
      RECAP({ whatWeDid: null, whatsNext: null, whatWentWell: null, editable: false }),
    )
    const w = await mountDialog({
      prefill: { whatWeDid: 'Recovered did', whatsNext: 'Recovered next' },
    })

    expect(w.find('textarea[data-testid="recap-what-we-did"]').element.value).toBe('')
    expect(w.find('textarea[data-testid="recap-whats-next"]').element.value).toBe('')
  })

  // A slow GET for booking A must not overwrite the fields once the dialog has moved to booking B.
  it('ignores a stale load response after the booking changed', async () => {
    let resolveA
    getSessionRecap.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolveA = r
        }),
    )
    getSessionRecap.mockResolvedValueOnce(RECAP({ bookingId: 'b2', whatWeDid: 'B did' }))
    const w = await mountDialog({ bookingId: 'a' })

    await w.setProps({ bookingId: 'b' })
    await flushPromises()
    resolveA(RECAP({ whatWeDid: 'A did' }))
    await flushPromises()

    expect(w.find('textarea[data-testid="recap-what-we-did"]').element.value).toBe('B did')
  })
})
