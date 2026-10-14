// skillars-deferred-151 AC2 — CoachSessionPackTiersPage.vue first-ever coverage.
//
// The backend has no tier "catalog" to manage (idx_spt_one_active_per_coach — at most one active
// tier per coach, DB-enforced). This page shows a current-offer + read-only-history model, not a
// multi-row catalog with per-row deactivate.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

import CoachSessionPackTiersPage from 'src/pages/coach/CoachSessionPackTiersPage.vue'

/** Read the component's paymentStore instance regardless of <script setup> expose shape. */
function storeOf(wrapper) {
  return wrapper.vm.paymentStore ?? wrapper.vm.$.setupState.paymentStore
}

const mounted = []
function mountPage(sessionPackTiers = []) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { payment: { sessionPackTiers, loading: {}, error: {} } },
  })
  const wrapper = mount(CoachSessionPackTiersPage, {
    global: { plugins: [pinia] },
    attachTo: document.body,
  })
  mounted.push(wrapper)
  return wrapper
}

const ACTIVE_TIER = {
  packTierId: 't1',
  label: 'Standard pack',
  sessionCount: 10,
  totalPrice: 500,
  pricePerSession: 50,
  isActive: true,
  createdAt: '2026-02-01T00:00:00Z',
}

const OLD_TIER = {
  packTierId: 't0',
  label: 'Old pack',
  sessionCount: 5,
  totalPrice: 200,
  pricePerSession: 40,
  isActive: false,
  createdAt: '2026-01-01T00:00:00Z',
}

describe('CoachSessionPackTiersPage.vue (deferred-151 AC2)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('fetches tiers on mount', () => {
    const wrapper = mountPage()
    expect(storeOf(wrapper).fetchSessionPackTiers).toHaveBeenCalledTimes(1)
  })

  it('shows the no-active-offer state when there is no active tier', () => {
    const wrapper = mountPage([])
    expect(wrapper.text()).toContain('payment.myTiers.noActiveOffer')
    expect(wrapper.text()).toContain('payment.myTiers.createNew')
  })

  it('shows the current active offer, a single withdraw control, and a read-only history list — not per-row deactivate', () => {
    const wrapper = mountPage([ACTIVE_TIER, OLD_TIER])

    expect(wrapper.text()).toContain('Standard pack')
    expect(wrapper.text()).toContain('payment.myTiers.replaceOffer')
    expect(wrapper.text()).toContain('payment.myTiers.withdrawOffer')
    expect(wrapper.text()).toContain('payment.myTiers.history')
    expect(wrapper.text()).toContain('Old pack')
    // exactly one withdraw affordance for the single active tier, not one per history row
    expect(
      wrapper.findAll('button').filter((b) => b.text() === 'payment.myTiers.withdrawOffer'),
    ).toHaveLength(1)
  })

  it('has no withdraw control when there is no active tier', () => {
    const wrapper = mountPage([])
    expect(wrapper.text()).not.toContain('payment.myTiers.withdrawOffer')
  })

  it('withdrawing the active tier opens a confirmation and calls the store on confirm', async () => {
    const wrapper = mountPage([ACTIVE_TIER])
    const store = storeOf(wrapper)
    store.deactivateSessionPackTier.mockResolvedValue()

    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'payment.myTiers.withdrawOffer')
      .trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('payment.myTiers.withdrawConfirmBody')

    const confirmBtn = new DOMWrapper(
      Array.from(dialogEl().querySelectorAll('button')).find(
        (b) => b.textContent === 'payment.myTiers.withdrawOffer',
      ),
    )
    await confirmBtn.trigger('click')
    await flushPromises()

    expect(store.deactivateSessionPackTier).toHaveBeenCalledWith(ACTIVE_TIER.packTierId)
  })

  it('shows a distinct error with retry when the tier fetch fails, without falling back to the no-active-offer state', () => {
    const pinia = createTestingPinia({
      createSpy: vi.fn,
      initialState: {
        payment: { sessionPackTiers: [], loading: {}, error: { sessionPackTiers: 'boom' } },
      },
    })
    const wrapper = mount(CoachSessionPackTiersPage, { global: { plugins: [pinia] } })
    mounted.push(wrapper)

    expect(wrapper.text()).toContain('payment.myTiers.fetchError')
    expect(wrapper.text()).not.toContain('payment.myTiers.noActiveOffer')
  })

  it('opening the create form with no active tier shows no replace-confirmation copy', async () => {
    const wrapper = mountPage([])
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('payment.myTiers.createNew')
    expect(wrapper.text()).not.toContain('payment.myTiers.replaceConfirmBody')
  })

  // q-dialog teleports its content to a dedicated `#q-portal--dialog--N` element appended to
  // document.body (confirmed by inspecting the rendered DOM), a sibling of the wrapper's own root
  // — not a descendant. `wrapper.find`/`wrapper.text()` only search inside `wrapper.element`, so
  // dialog-internal assertions below query `document.body` directly instead (the page itself is
  // mounted with `attachTo: document.body`, so both the page and the portal live there).
  it('opening the create form with an active tier shows the replace-confirmation copy', async () => {
    const wrapper = mountPage([ACTIVE_TIER])
    const replaceBtn = wrapper
      .findAll('button')
      .find((b) => b.text() === 'payment.myTiers.replaceOffer')
    await replaceBtn.trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('payment.myTiers.replaceConfirmBody')
  })

  /** The manual-extend widget's own input is always on the page, outside the dialog — scope
   *  queries to the dialog element itself so its index assumptions aren't thrown off by it. */
  function dialogEl() {
    return document.body.querySelector('[role="dialog"]')
  }

  it('blocks submit and shows the price-floor error when totalPrice / sessionCount < 0.005', async () => {
    const wrapper = mountPage([])
    await wrapper.find('button').trigger('click')
    await flushPromises()

    const inputs = Array.from(dialogEl().querySelectorAll('input')).map((el) => new DOMWrapper(el))
    // label, sessionCount, totalPrice — in that template order
    await inputs[0].setValue('Cheap pack')
    await inputs[1].setValue('10')
    await inputs[2].setValue('0.01')

    const submitBtn = Array.from(dialogEl().querySelectorAll('button')).find(
      (b) => b.textContent === 'payment.myTiers.createNew',
    )
    expect(submitBtn.disabled).toBe(true)
  })

  it('submits a valid create payload to the store', async () => {
    const wrapper = mountPage([])
    const store = storeOf(wrapper)
    store.createSessionPackTier.mockResolvedValue(ACTIVE_TIER)

    await wrapper.find('button').trigger('click')
    await flushPromises()

    const inputs = Array.from(dialogEl().querySelectorAll('input')).map((el) => new DOMWrapper(el))
    await inputs[0].setValue('Standard pack')
    await inputs[1].setValue('10')
    await inputs[2].setValue('500')

    const submitBtn = new DOMWrapper(
      Array.from(dialogEl().querySelectorAll('button')).find(
        (b) => b.textContent === 'payment.myTiers.createNew',
      ),
    )
    await submitBtn.trigger('click')
    await flushPromises()

    expect(store.createSessionPackTier).toHaveBeenCalledWith({
      label: 'Standard pack',
      sessionCount: 10,
      totalPrice: 500,
    })
  })

  it('extend widget shows one generic failure message on error, not a branched backend reason', async () => {
    const wrapper = mountPage([])
    const store = storeOf(wrapper)
    store.extendSessionPack.mockRejectedValue(new Error('409'))

    const purchaseIdInput = wrapper.find('input')
    await purchaseIdInput.setValue('purchase-123')
    const extendBtn = wrapper
      .findAll('button')
      .find((b) => b.text() === 'payment.myTiers.extendCta')
    await extendBtn.trigger('click')
    await flushPromises()

    expect(store.extendSessionPack).toHaveBeenCalledWith('purchase-123')
    expect(wrapper.text()).toContain('payment.myTiers.extendFailed')
  })
})
