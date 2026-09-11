// skillars-deferred-108 AC1 — PaymentMethodCard.vue (closes deferred-11 D-frontend).
//
// Ledger (deferred-work.md:657): "No frontend tests … for PaymentMethodCard.vue … real coverage
// gap on a component with non-trivial lifecycle logic."
//
// Covers: onMounted drives both store fetches; the saved-card vs add-card branch is discriminated
// by `savedCard?.hasCard` (SavedPaymentMethodResponse is always an object, never null); and the
// null-key guard in ensureStripeReady never lets loadStripe run with an empty key.
//
// Mutation check (run both ways, see Dev Agent Record): delete the
//   `if (!key) { stripeUnavailable.value = true; return false }`
// guard in ensureStripeReady — loadStripe(undefined) then runs and is swallowed by the try/catch,
// landing on the same stripeUnavailable = true, so the assertion that turns red is
// `expect(loadStripe).not.toHaveBeenCalled()`.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { watch } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
// @stripe/stripe-js appends <script src="https://js.stripe.com/…"> at import time — must be
// mocked regardless. loadStripe is the spy the guard test asserts on.
vi.mock('@stripe/stripe-js', () => ({ loadStripe: vi.fn() }))

// payment.api pulls in src/boot/axios (Quasar defineBoot) — stub the exports the .vue imports.
vi.mock('src/api/payment.api', () => ({
  createSetupIntent: vi.fn(),
  savePaymentMethod: vi.fn(),
  confirmCardSetup: vi.fn(),
}))

// skillars-deferred-109 AC1.2: the component surfaces a non-blocking notice via
// useQuasar().notify. The shared setup-file installs Quasar without the Notify plugin, so stub
// useQuasar's return to a spy we can assert on. Everything else from 'quasar' is passed through
// (Quasar core, q-* components used by the mounted template).
const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import { loadStripe } from '@stripe/stripe-js'
import { createSetupIntent, savePaymentMethod, confirmCardSetup } from 'src/api/payment.api'
import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'

/** Read the component's paymentStore instance regardless of <script setup> expose shape. */
function storeOf(wrapper) {
  return wrapper.vm.paymentStore ?? wrapper.vm.$.setupState.paymentStore
}

/** A fake Stripe object whose elements().create().mount() all no-op. */
function fakeStripe() {
  return {
    elements: () => ({
      create: () => ({ mount: vi.fn(), unmount: vi.fn(), on: vi.fn() }),
    }),
  }
}

const mounted = []

/** Keep every mounted wrapper so afterEach can unmount it (skillars-deferred-108 code review P14). */
function trackMounted(wrapper) {
  mounted.push(wrapper)
  return wrapper
}

function mountCard(initialPaymentState) {
  return trackMounted(
    mount(PaymentMethodCard, {
      global: {
        plugins: [
          createTestingPinia({
            createSpy: vi.fn,
            initialState: { payment: initialPaymentState },
          }),
        ],
        stubs: { 'q-banner': true },
      },
    }),
  )
}

describe('PaymentMethodCard.vue (deferred-108 AC1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
  })

  it('on mount calls fetchStripeConfig and fetchSavedPaymentMethod', async () => {
    const wrapper = mountCard({
      savedPaymentMethod: { hasCard: true, brand: 'visa', last4: '4242' },
      stripeConfig: { publishableKey: 'pk_test_x' },
    })
    await flushPromises()

    const store = wrapper.vm.paymentStore ?? wrapper.vm.$.setupState.paymentStore
    expect(store.fetchStripeConfig).toHaveBeenCalledTimes(1)
    expect(store.fetchSavedPaymentMethod).toHaveBeenCalledTimes(1)
  })

  it('renders the saved-card row when savedPaymentMethod.hasCard is true', async () => {
    loadStripe.mockResolvedValue(fakeStripe())
    const wrapper = mountCard({
      savedPaymentMethod: {
        hasCard: true,
        brand: 'visa',
        last4: '4242',
        expMonth: 12,
        expYear: 2030,
      },
      stripeConfig: { publishableKey: 'pk_test_x' },
    })
    await flushPromises()

    expect(wrapper.vm.showForm).toBe(false)
    expect(wrapper.text()).toContain('payment.card.savedLabel')
    expect(wrapper.text()).not.toContain('payment.card.addCardPrompt')
  })

  it('renders the add-card form when savedPaymentMethod.hasCard is false', async () => {
    loadStripe.mockResolvedValue(fakeStripe())
    const wrapper = mountCard({
      savedPaymentMethod: { hasCard: false },
      stripeConfig: { publishableKey: 'pk_test_x' },
    })
    await flushPromises()

    expect(wrapper.vm.showForm).toBe(true)
    expect(wrapper.text()).toContain('payment.card.addCardPrompt')
    expect(loadStripe).toHaveBeenCalledWith('pk_test_x')
  })

  it('null-key guard: with no stripeConfig, stripeUnavailable is set and loadStripe is never called', async () => {
    const wrapper = mountCard({
      savedPaymentMethod: { hasCard: false },
      stripeConfig: null,
    })
    await flushPromises()

    expect(wrapper.vm.stripeUnavailable).toBe(true)
    expect(loadStripe).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('payment.card.unavailable')
  })
})

// ---------------------------------------------------------------------------
// skillars-deferred-109 AC1 — PaymentMethodCard.vue card-collection lifecycle fixes.
// Closes 4 pre-existing prod-defect bullets the deferred-108 code review filed
// (deferred-work.md:1811-1832). Each block names its one-line revert; all were run in
// both directions (see Dev Agent Record → Validation summary).
// ---------------------------------------------------------------------------
describe('PaymentMethodCard.vue (deferred-109 AC1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.restoreAllMocks()
  })

  // AC1.1 — retry after a failed Stripe-config fetch: one click, no stale key.
  it('AC1.1 — a retry whose refetch succeeds mounts Elements on the first click', async () => {
    loadStripe.mockResolvedValue(fakeStripe())
    const wrapper = mountCard({ savedPaymentMethod: { hasCard: false }, stripeConfig: null })
    await flushPromises()
    // Precondition: the first load could not resolve a publishable key.
    expect(wrapper.vm.stripeUnavailable).toBe(true)
    expect(loadStripe).not.toHaveBeenCalled()

    const store = storeOf(wrapper)
    // The refetch now succeeds, but only after a tick — the watcher race the bug depends on.
    store.fetchStripeConfig.mockImplementation(async () => {
      await Promise.resolve()
      store.stripeConfig = { publishableKey: 'pk_live_retry' }
    })

    // skillars-deferred-109 code review: record EVERY transition of stripeUnavailable, flush:'sync'
    // so no intermediate value is coalesced away. The AC1.1 defect is a flip-flop, not an end
    // state — clearing the flag BEFORE the fetches resolve flips showForm true synchronously, the
    // watch(showForm) job runs mid-await, ensureStripeReady re-raises the flag against the
    // still-null key, and the click no-ops. Asserting only the final value cannot see that: the
    // trailing clear restores it either way. Pinning the exact sequence is what makes this test
    // fail when the deleted leading `stripeUnavailable.value = false` is put back.
    const flips = []
    const stopWatch = watch(
      () => wrapper.vm.stripeUnavailable,
      (v) => flips.push(v),
      {
        flush: 'sync',
      },
    )
    // Same reason, second angle: the flag must still be raised at the moment the refetch runs.
    let raisedDuringRefetch = null
    const refetch = store.fetchStripeConfig.getMockImplementation()
    store.fetchStripeConfig.mockImplementation(async () => {
      raisedDuringRefetch = wrapper.vm.stripeUnavailable
      await refetch()
    })

    await wrapper.vm.loadStripeConfig({ isRetry: true })
    await flushPromises()
    stopWatch()

    expect(wrapper.vm.stripeUnavailable).toBe(false)
    expect(wrapper.vm.showForm).toBe(true)
    expect(loadStripe).toHaveBeenCalledWith('pk_live_retry')
    // Exactly one transition: true → false, once the data is actually in.
    expect(flips).toEqual([false])
    expect(raisedDuringRefetch).toBe(true)
    // Mutation A: delete the trailing `stripeUnavailable.value = false` in loadStripeConfig →
    // stripeUnavailable stays true, showForm stays false, loadStripe is never reached → RED.
    // Mutation B: re-insert `stripeUnavailable.value = false` at the TOP of loadStripeConfig (the
    // line AC1.1 removed) → flips becomes [false, true, false] and raisedDuringRefetch becomes
    // false → RED. Before this review only Mutation A was caught.
  })

  it('AC1.1 — a retry whose refetch fails re-raises stripeUnavailable and never reuses the stale key', async () => {
    loadStripe.mockResolvedValue(fakeStripe())
    const wrapper = mountCard({
      savedPaymentMethod: { hasCard: false },
      stripeConfig: { publishableKey: 'pk_stale' },
    })
    await flushPromises()
    expect(loadStripe).toHaveBeenCalledWith('pk_stale')

    loadStripe.mockClear()
    const store = storeOf(wrapper)
    // Refetch fails: the store swallows and parks the error (stripeConfig keeps the stale value).
    store.fetchStripeConfig.mockImplementation(async () => {
      store.error.stripeConfig = new Error('stripe config 503')
    })

    await wrapper.vm.loadStripeConfig({ isRetry: true })
    await flushPromises()

    expect(wrapper.vm.stripeUnavailable).toBe(true)
    expect(loadStripe).not.toHaveBeenCalled()
    // Mutation: remove the `if (paymentStore.error.stripeConfig || …) { stripeUnavailable = true;
    // return }` block → loadStripeConfig falls through, mountCardElement runs, and
    // loadStripe('pk_stale') fires → RED.
  })

  // AC1.2 — a failed post-save refresh does not silently strand the entry form.
  it('AC1.2 — a post-save refresh failure surfaces a notice and still emits saved', async () => {
    loadStripe.mockResolvedValue(fakeStripe())
    const wrapper = mountCard({
      savedPaymentMethod: { hasCard: false },
      stripeConfig: { publishableKey: 'pk_test_x' },
    })
    await flushPromises()

    const store = storeOf(wrapper)
    createSetupIntent.mockResolvedValue({ clientSecret: 'cs_1' })
    confirmCardSetup.mockResolvedValue({
      setupIntent: { status: 'succeeded', payment_method: 'pm_1' },
      error: undefined,
    })
    savePaymentMethod.mockResolvedValue(undefined)
    // The card saves, but the refresh that follows fails and the store swallows it.
    store.fetchSavedPaymentMethod.mockImplementation(async () => {
      store.error.savedPaymentMethod = new Error('saved-method refresh 500')
    })
    await wrapper.vm.submit()
    await flushPromises()

    expect(savePaymentMethod).toHaveBeenCalledWith('pm_1')
    expect(wrapper.emitted('saved')).toBeTruthy()
    expect(notifyMock).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'warning', message: 'payment.card.savedRefreshFailed' }),
    )
    // Mutation: remove the `if (paymentStore.error.savedPaymentMethod) { $q.notify(...) }` guard in
    // submit() → notifySpy is never called → RED (the save still succeeds either way).
  })

  // AC1.3 — hasCard:true with brand:null renders a real label, not "undefined undefined".
  it('AC1.3 — a saved card with a null brand renders detailsUnavailable and keeps the Replace affordance', async () => {
    loadStripe.mockResolvedValue(fakeStripe())
    const wrapper = mountCard({
      savedPaymentMethod: {
        hasCard: true,
        brand: null,
        last4: null,
        expMonth: null,
        expYear: null,
      },
      stripeConfig: { publishableKey: 'pk_test_x' },
    })
    await flushPromises()

    expect(wrapper.vm.showForm).toBe(false)
    expect(wrapper.text()).toContain('payment.card.detailsUnavailable')
    expect(wrapper.text()).not.toContain('payment.card.savedLabel')
    expect(wrapper.text()).toContain('payment.card.replaceCard')
    // Mutation: drop the `savedCard.brand ? … : …` ternary → the template renders savedLabel with
    // undefined interpolations and the detailsUnavailable assertion goes RED.
  })

  // AC1.4 — the card-save path's three branches are pinned.
  describe('AC1.4 — submit() branches', () => {
    async function mountForm() {
      loadStripe.mockResolvedValue(fakeStripe())
      const wrapper = mountCard({
        savedPaymentMethod: { hasCard: false },
        stripeConfig: { publishableKey: 'pk_test_x' },
      })
      await flushPromises()
      createSetupIntent.mockResolvedValue({ clientSecret: 'cs_1' })
      return wrapper
    }

    it('a hard decline (error present) surfaces the error message and does not save', async () => {
      const wrapper = await mountForm()
      confirmCardSetup.mockResolvedValue({
        setupIntent: undefined,
        error: { message: 'Your card was declined' },
      })

      await wrapper.vm.submit()
      await flushPromises()

      expect(wrapper.vm.cardError).toBe('Your card was declined')
      expect(savePaymentMethod).not.toHaveBeenCalled()
      expect(wrapper.emitted('saved')).toBeFalsy()
    })

    it('a non-succeeded intent (3DS requires_action) surfaces the generic save error and does not save', async () => {
      const wrapper = await mountForm()
      confirmCardSetup.mockResolvedValue({
        setupIntent: { status: 'requires_action' },
        error: undefined,
      })

      await wrapper.vm.submit()
      await flushPromises()

      expect(wrapper.vm.cardError).toBe('payment.card.saveError')
      expect(savePaymentMethod).not.toHaveBeenCalled()
      // Mutation: delete the `|| setupIntent?.status !== 'succeeded'` disjunct at
      // PaymentMethodCard.vue:197 → submit() proceeds to savePaymentMethod(undefined), cardError
      // stays null → both assertions go RED.
    })

    it('a succeeded intent saves the payment method and emits saved', async () => {
      const wrapper = await mountForm()
      confirmCardSetup.mockResolvedValue({
        setupIntent: { status: 'succeeded', payment_method: 'pm_ok' },
        error: undefined,
      })
      savePaymentMethod.mockResolvedValue(undefined)

      await wrapper.vm.submit()
      await flushPromises()

      expect(savePaymentMethod).toHaveBeenCalledWith('pm_ok')
      expect(wrapper.emitted('saved')).toBeTruthy()
      expect(wrapper.vm.cardError).toBeNull()
    })
  })
})
