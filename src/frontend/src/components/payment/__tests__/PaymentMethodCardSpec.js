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

import { loadStripe } from '@stripe/stripe-js'
import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'

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
