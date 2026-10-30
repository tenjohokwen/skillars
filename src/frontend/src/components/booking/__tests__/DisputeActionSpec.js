// skillars-deferred-165 AC2: DisputeAction.vue — raise/view a dispute on an eligible booking.
// Covers: initial button (no prior dispute), submit happy path (disputeId persisted to
// sessionStorage, button flips to "view"), and the conflict/ineligible error paths
// (disputes.alreadyRaised, disputes.invalidReason, disputes.notEligible, disputes.windowExpired).

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/disputes.api', () => ({
  raiseDispute: vi.fn(),
  getDispute: vi.fn(),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { raiseDispute, getDispute } from 'src/api/disputes.api'
import DisputeAction from 'src/components/booking/DisputeAction.vue'
import AppealAction from 'src/components/common/AppealAction.vue'

const mounted = []
async function mountAction(bookingId = 'booking-1') {
  const wrapper = mount(DisputeAction, {
    props: { bookingId },
    global: {
      plugins: [
        createTestingPinia({ createSpy: vi.fn, initialState: { auth: { userId: 'user-1' } } }),
      ],
      stubs: { QDialog: { template: '<div><slot /></div>' } },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('DisputeAction.vue (skillars-deferred-165 AC2)', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('renders the raise button when no dispute has been raised for this booking yet', async () => {
    const wrapper = await mountAction()

    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-view-btn"]').exists()).toBe(false)
  })

  it('does not inherit a dispute link persisted for the same booking by a different user', async () => {
    sessionStorage.setItem('dispute:user-2:booking-1', 'dispute-other')
    const wrapper = await mountAction('booking-1')

    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-view-btn"]').exists()).toBe(false)
  })

  it('keeps submit disabled until details are filled in (RaiseDisputeRequest.details is @NotBlank)', async () => {
    const wrapper = await mountAction()

    wrapper.vm.openRaiseDialog()
    wrapper.vm.reason = 'COACH_NO_SHOW'
    wrapper.vm.details = '   '
    await flushPromises()
    const submit = wrapper.find('[data-testid="dispute-submit-btn"]')
    expect(submit.attributes('disabled')).toBeDefined()

    wrapper.vm.details = 'Coach never showed up'
    await flushPromises()
    expect(
      wrapper.find('[data-testid="dispute-submit-btn"]').attributes('disabled'),
    ).toBeUndefined()
  })

  it('renders the view button when a disputeId is already persisted for this booking', async () => {
    sessionStorage.setItem('dispute:user-1:booking-1', 'dispute-abc')
    const wrapper = await mountAction('booking-1')

    expect(wrapper.find('[data-testid="dispute-view-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dispute-raise-btn"]').exists()).toBe(false)
  })

  it('submitting the raise dialog persists the disputeId and flips to the view button', async () => {
    // disputes.api.js wrappers resolve to the already-unwrapped response body
    // (boot/axios.js's response interceptor returns response.data), not an axios Response.
    raiseDispute.mockResolvedValue({ disputeId: 'dispute-xyz' })
    const wrapper = await mountAction('booking-2')

    wrapper.vm.openRaiseDialog()
    wrapper.vm.reason = 'COACH_NO_SHOW'
    wrapper.vm.details = 'Coach never showed up'
    await wrapper.vm.submitDispute()
    await flushPromises()

    expect(raiseDispute).toHaveBeenCalledWith('booking-2', 'COACH_NO_SHOW', 'Coach never showed up')
    expect(wrapper.vm.disputeId).toBe('dispute-xyz')
    expect(sessionStorage.getItem('dispute:user-1:booking-2')).toBe('dispute-xyz')
    expect(wrapper.vm.raiseDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('a 409 disputes.alreadyRaised conflict shows the mapped error and keeps the dialog open', async () => {
    raiseDispute.mockRejectedValue({
      response: {
        data: { errorMsg: { errorKey: 'generic.requestError', message: 'disputes.alreadyRaised' } },
      },
    })
    const wrapper = await mountAction()

    wrapper.vm.openRaiseDialog()
    wrapper.vm.reason = 'OTHER'
    await wrapper.vm.submitDispute()

    expect(wrapper.vm.raiseDialogOpen).toBe(true)
    expect(wrapper.vm.disputeId).toBeNull()
    // Global test i18n has no real translations loaded (test/vitest/setup-file.js): t(key) returns
    // the key verbatim, so asserting the key itself is exactly what this harness's own component
    // specs assert against (see that file's comment).
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'disputes.alreadyRaised' }),
    )
  })

  // fix/audit3-completion-dispute-integrity: disputes.maxPerBookingPerRaiser reached.
  it('a 409 disputes.limitReached shows the mapped error', async () => {
    raiseDispute.mockRejectedValue({
      response: {
        data: { errorMsg: { errorKey: 'generic.requestError', message: 'disputes.limitReached' } },
      },
    })
    const wrapper = await mountAction()

    wrapper.vm.openRaiseDialog()
    wrapper.vm.reason = 'OTHER'
    await wrapper.vm.submitDispute()

    expect(wrapper.vm.disputeId).toBeNull()
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'disputes.limitReached' }),
    )
  })

  it('a 400 disputes.invalidReason shows the mapped error', async () => {
    raiseDispute.mockRejectedValue({
      response: {
        data: { errorMsg: { errorKey: 'generic.requestError', message: 'disputes.invalidReason' } },
      },
    })
    const wrapper = await mountAction()

    wrapper.vm.openRaiseDialog()
    wrapper.vm.reason = 'OTHER'
    await wrapper.vm.submitDispute()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'disputes.invalidReason' }),
    )
  })

  it('a 403 disputes.notEligible shows the mapped ineligible error', async () => {
    raiseDispute.mockRejectedValue({
      response: {
        data: {
          errorMsg: {
            errorKey: 'disputes.notEligible',
            message: 'The operation is not granted. You can contact help desk',
          },
        },
      },
    })
    const wrapper = await mountAction()

    wrapper.vm.openRaiseDialog()
    wrapper.vm.reason = 'OTHER'
    await wrapper.vm.submitDispute()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'disputes.notEligible' }),
    )
  })

  it('a 403 disputes.windowExpired shows the mapped window-expired error', async () => {
    raiseDispute.mockRejectedValue({
      response: {
        data: {
          errorMsg: {
            errorKey: 'disputes.windowExpired',
            message: 'The operation is not granted. You can contact help desk',
          },
        },
      },
    })
    const wrapper = await mountAction()

    wrapper.vm.openRaiseDialog()
    wrapper.vm.reason = 'OTHER'
    await wrapper.vm.submitDispute()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'disputes.windowExpired' }),
    )
  })

  it('opening the status dialog loads and renders the dispute via getDispute', async () => {
    sessionStorage.setItem('dispute:user-1:booking-3', 'dispute-abc')
    getDispute.mockResolvedValue({
      disputeId: 'dispute-abc',
      reason: 'SESSION_QUALITY',
      status: 'RESOLVED',
      resolution: 'FULL_CREDIT',
      resolutionNote: 'Refunded in full',
    })
    const wrapper = await mountAction('booking-3')

    await wrapper.vm.openStatusDialog()

    expect(getDispute).toHaveBeenCalledWith('dispute-abc')
    expect(wrapper.vm.disputeStatus.status).toBe('RESOLVED')
    expect(wrapper.find('[data-testid="dispute-status-content"]').exists()).toBe(true)
  })

  it('a failed status load shows the error state', async () => {
    sessionStorage.setItem('dispute:user-1:booking-4', 'dispute-abc')
    getDispute.mockRejectedValue(new Error('network error'))
    const wrapper = await mountAction('booking-4')

    await wrapper.vm.openStatusDialog()

    expect(wrapper.find('[data-testid="dispute-status-error"]').exists()).toBe(true)
  })
  // FR-ADM-004 / UX Journey 5: a resolved or dismissed dispute can be appealed by its raiser.
  it.each([
    ['RESOLVED', true],
    ['DISMISSED', true],
    ['OPEN', false],
  ])('a %s dispute shows the appeal action: %s', async (status, shown) => {
    sessionStorage.setItem('dispute:user-1:booking-5', 'dispute-xyz')
    getDispute.mockResolvedValue({
      disputeId: 'dispute-xyz',
      reason: 'SESSION_QUALITY',
      status,
      resolvedAt: shown ? '2026-10-20T10:00:00Z' : null,
    })
    const wrapper = await mountAction('booking-5')

    await wrapper.vm.openStatusDialog()
    await flushPromises()

    const appeal = wrapper.findComponent(AppealAction)
    expect(appeal.exists()).toBe(shown)
    if (shown) {
      expect(appeal.props()).toMatchObject({
        subjectType: 'DISPUTE_RESOLUTION',
        subjectId: 'dispute-xyz',
        enforcedAt: '2026-10-20T10:00:00Z',
      })
    }
  })
})
