// skillars-deferred-165 AC7: SessionTemplateVault.vue's rename dialog name input must match the
// backend's RenameTemplateRequest @Size(max=200) with a client-side maxlength.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import SessionTemplateVault from 'src/pages/coach/SessionTemplateVault.vue'

const TEMPLATE = { id: 't1', name: 'My Template', drillCount: 3, deployCount: 1 }

const mounted = []
async function mountPage() {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { sessionTemplate: { templates: [TEMPLATE] } },
  })
  const wrapper = mount(SessionTemplateVault, {
    global: {
      plugins: [pinia],
      stubs: {
        SessionDNAChart: true,
        QDialog: { template: '<div><slot /></div>' },
      },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('SessionTemplateVault.vue (skillars-deferred-165 AC7)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('the rename dialog name input carries maxlength=200', async () => {
    const wrapper = await mountPage()

    wrapper.vm.startRename(TEMPLATE)
    await flushPromises()

    const input = wrapper.find('input[maxlength="200"]')
    expect(input.exists()).toBe(true)
  })
})

// feat/coach-workflow-gaps A19: the deploy dialog picks one of the coach's upcoming plannable bookings
// instead of asking for a raw booking UUID.
describe('SessionTemplateVault.vue — deploy picker (feat/coach-workflow-gaps A19)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  const BOOKING = {
    bookingId: '11111111-1111-1111-1111-111111111111',
    playerId: 7,
    playerName: 'Mia',
    requestedStartTime: '2026-11-03T16:00:00Z',
    requestedEndTime: '2026-11-03T17:00:00Z',
    canonicalTimezone: 'Europe/Berlin',
  }

  async function mountWith(deployableBookings) {
    const pinia = createTestingPinia({
      createSpy: vi.fn,
      initialState: { sessionTemplate: { templates: [TEMPLATE], deployableBookings } },
    })
    const wrapper = mount(SessionTemplateVault, {
      global: {
        plugins: [pinia],
        stubs: { SessionDNAChart: true, QDialog: { template: '<div><slot /></div>' } },
      },
    })
    mounted.push(wrapper)
    await flushPromises()
    return wrapper
  }

  it('opening the deploy dialog loads the deployable bookings and shows no UUID text input', async () => {
    const wrapper = await mountWith([BOOKING])
    const store = wrapper.vm.templateStore ?? wrapper.vm.$.setupState.templateStore

    wrapper.vm.startDeploy(TEMPLATE)
    await flushPromises()

    expect(store.fetchDeployableBookings).toHaveBeenCalled()
    expect(wrapper.find('[data-test="deploy-booking-select"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('session.templates.deployBookingIdLabel')
    // Option label carries the player's name for the coach to recognise the session.
    expect(wrapper.vm.bookingOptions[0]).toMatchObject({ value: BOOKING.bookingId })
    expect(wrapper.vm.bookingOptions[0].label).toContain('Mia')
  })

  it('shows an empty state when there is nothing to plan', async () => {
    const wrapper = await mountWith([])
    wrapper.vm.startDeploy(TEMPLATE)
    await flushPromises()
    expect(wrapper.find('[data-test="no-deployable-bookings"]').exists()).toBe(true)
  })

  it('deploys the chosen booking and maps the backend error key, not the helpCode', async () => {
    const wrapper = await mountWith([BOOKING])
    const store = wrapper.vm.templateStore ?? wrapper.vm.$.setupState.templateStore
    store.deployTemplate.mockRejectedValue({
      response: { data: { helpCode: 'abc123', errorMsg: { errorKey: 'SESSION_ALREADY_EXISTS' } } },
    })

    wrapper.vm.startDeploy(TEMPLATE)
    wrapper.vm.deployBookingId = BOOKING.bookingId
    await wrapper.vm.confirmDeploy()
    await flushPromises()

    expect(store.deployTemplate).toHaveBeenCalledWith('t1', BOOKING.bookingId)
    expect(wrapper.vm.deployError).toBe('session.templates.deployAlreadyExists')
  })
})
