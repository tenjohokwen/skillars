// skillars-deferred-108 AC1 — payment.store.js card-collection actions (closes deferred-11 D-frontend).
//
// Ledger (deferred-work.md:657): "No frontend tests … for the new payment.store.js actions
// (fetchStripeConfig, fetchSavedPaymentMethod)".
//
// Style: real Pinia store (setActivePinia + createPinia) with the api module fully mocked.
// The two actions share one shape — set loading, null the error, assign the awaited value,
// clear loading in finally, park the error in catch.
//
// Mutation check (run both ways, see Dev Agent Record): deleting `this.error.stripeConfig = err`
// in fetchStripeConfig's catch turns the "rejection populates error.stripeConfig" case red.
//
// skillars-deferred-108 code review (P10): the "null the error" half of that shape was asserted
// only against a FRESH store, where error.stripeConfig is already null -- so deleting
// `this.error.stripeConfig = null` from the top of either action left every case green. The two
// failure-then-success cases below drive the error ref non-null first, so the clear is now the
// only thing that can make them pass.
//
// Mutation check: delete `this.error.stripeConfig = null` (resp. `.savedPaymentMethod`) from the
// action prologue -> the matching "a later success clears the previous error" case turns red.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Factory mock (not auto-mock): the real payment.api pulls in src/boot/axios → src/boot/i18n,
// which calls Quasar's defineBoot and throws outside the app runtime. Stub every export the
// store imports.
vi.mock('src/api/payment.api', () => {
  const fn = () => vi.fn()
  return {
    getStripeStatus: fn(),
    fetchCreditBalance: fn(),
    fetchMySessionPackTiers: fn(),
    fetchMyStrikes: fn(),
    acknowledgeStrike: fn(),
    fetchCoachTiers: fn(),
    fetchMyCoachSubscription: fn(),
    subscribeCoach: fn(),
    changeCoachTier: fn(),
    cancelCoachSubscription: fn(),
    fetchPlayerTiers: fn(),
    fetchMyPlayerSubscription: fn(),
    subscribePlayer: fn(),
    changePlayerTier: fn(),
    cancelPlayerSubscription: fn(),
    fetchCoachRevenueSummary: fn(),
    fetchCoachTransactions: fn(),
    fetchCreditStatement: fn(),
    getStripeConfig: fn(),
    getSavedPaymentMethod: fn(),
  }
})

import { getStripeConfig, getSavedPaymentMethod } from 'src/api/payment.api'
import { usePaymentStore } from 'src/stores/payment.store'

/** A promise plus its resolve/reject handles, for observing the mid-flight loading flag. */
function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('payment.store — card-collection actions (deferred-108 AC1)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('fetchStripeConfig', () => {
    it('success populates stripeConfig and toggles loading true→false', async () => {
      const store = usePaymentStore()
      const config = { publishableKey: 'pk_test_123' }
      const d = deferred()
      getStripeConfig.mockReturnValue(d.promise)

      expect(store.loading.stripeConfig).toBe(false)
      const call = store.fetchStripeConfig()
      expect(store.loading.stripeConfig).toBe(true)

      d.resolve(config)
      await call

      expect(store.stripeConfig).toEqual(config)
      expect(store.error.stripeConfig).toBeNull()
      expect(store.loading.stripeConfig).toBe(false)
      expect(getStripeConfig).toHaveBeenCalledTimes(1)
    })

    it('rejection populates error.stripeConfig, leaves stripeConfig untouched, clears loading', async () => {
      const store = usePaymentStore()
      const err = new Error('stripe config 503')
      getStripeConfig.mockRejectedValue(err)

      await store.fetchStripeConfig()

      expect(store.error.stripeConfig).toBe(err)
      expect(store.stripeConfig).toBeNull()
      expect(store.loading.stripeConfig).toBe(false)
    })

    it('a later success clears the error left by a previous failure', async () => {
      const store = usePaymentStore()
      getStripeConfig.mockRejectedValueOnce(new Error('stripe config 503'))
      await store.fetchStripeConfig()
      expect(store.error.stripeConfig).not.toBeNull() // precondition: the ref is dirty

      const config = { publishableKey: 'pk_test_456' }
      getStripeConfig.mockResolvedValueOnce(config)
      await store.fetchStripeConfig()

      expect(store.error.stripeConfig).toBeNull()
      expect(store.stripeConfig).toEqual(config)
      expect(store.loading.stripeConfig).toBe(false)
    })
  })

  describe('fetchSavedPaymentMethod', () => {
    it('a { hasCard: true, … } resolution is stored verbatim', async () => {
      const store = usePaymentStore()
      const saved = { hasCard: true, brand: 'visa', last4: '4242', expMonth: 12, expYear: 2030 }
      getSavedPaymentMethod.mockResolvedValue(saved)

      await store.fetchSavedPaymentMethod()

      expect(store.savedPaymentMethod).toEqual(saved)
      expect(store.error.savedPaymentMethod).toBeNull()
      expect(store.loading.savedPaymentMethod).toBe(false)
    })

    it('a { hasCard: false } resolution is stored verbatim without throwing', async () => {
      const store = usePaymentStore()
      const noCard = { hasCard: false, brand: null, last4: null, expMonth: null, expYear: null }
      getSavedPaymentMethod.mockResolvedValue(noCard)

      await expect(store.fetchSavedPaymentMethod()).resolves.toBeUndefined()

      expect(store.savedPaymentMethod).toEqual(noCard)
      expect(store.error.savedPaymentMethod).toBeNull()
    })

    it('rejection populates error.savedPaymentMethod and clears loading', async () => {
      const store = usePaymentStore()
      const err = new Error('payment-method 500')
      const d = deferred()
      getSavedPaymentMethod.mockReturnValue(d.promise)

      const call = store.fetchSavedPaymentMethod()
      expect(store.loading.savedPaymentMethod).toBe(true)

      d.reject(err)
      await call

      expect(store.error.savedPaymentMethod).toBe(err)
      expect(store.savedPaymentMethod).toBeNull()
      expect(store.loading.savedPaymentMethod).toBe(false)
    })

    it('a later success clears the error left by a previous failure', async () => {
      const store = usePaymentStore()
      getSavedPaymentMethod.mockRejectedValueOnce(new Error('payment-method 500'))
      await store.fetchSavedPaymentMethod()
      expect(store.error.savedPaymentMethod).not.toBeNull() // precondition: the ref is dirty

      const saved = { hasCard: true, brand: 'visa', last4: '4242', expMonth: 12, expYear: 2030 }
      getSavedPaymentMethod.mockResolvedValueOnce(saved)
      await store.fetchSavedPaymentMethod()

      expect(store.error.savedPaymentMethod).toBeNull()
      expect(store.savedPaymentMethod).toEqual(saved)
    })
  })
})
