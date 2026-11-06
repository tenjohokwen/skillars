// skillars-deferred-148 AC1: PlayerSubscriptionPage.vue read the :playerId route param via
// Number(route.params.playerId). Player ids are backend Tsids (18-19 digits), well past
// Number.MAX_SAFE_INTEGER, and the backend deliberately quotes Long as a JSON string so JS never
// has to represent one as a number (CommonConfig.longToStringModule) — converting it back to a
// Number here silently corrupted it, so every subscription API call on this page hit a player id
// that does not exist. Mirrors PlayerDevelopmentDashboardPageSpec.js's precedent (deferred-147).
//
// Mutation check: reintroduce `Number(route.params.playerId)` on the playerId computed → the
// "exact id, not corrupted" assertion below goes RED, since it would assert against a Number
// whose string form has already lost the trailing digits.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises, config } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import PlayerSubscriptionPage from 'src/pages/parent/PlayerSubscriptionPage.vue'

const mounted = []

// A real backend Tsid shape — large enough that Number(...) silently corrupts it.
const LARGE_PLAYER_ID = '893573203704173564'
const CORRUPTED_PLAYER_ID = '893573203704173600'

async function mountPage(playerId = LARGE_PLAYER_ID, role = 'PARENT', payment = undefined) {
  const initialState = { auth: { role } }
  if (payment) initialState.payment = payment
  const pinia = createTestingPinia({ createSpy: vi.fn, initialState })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/parent/player/:playerId/subscription',
        name: 'player-subscription',
        component: PlayerSubscriptionPage,
      },
    ],
  })
  router.push(`/parent/player/${playerId}/subscription`)
  await router.isReady()

  const wrapper = mount(PlayerSubscriptionPage, {
    shallow: true,
    // QPage is a pass-through so the page body renders under shallow mounting.
    global: { plugins: [pinia, router], stubs: { QPage: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, pinia }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('PlayerSubscriptionPage.vue — playerId precision (deferred-148 AC1)', () => {
  it('fetches the player subscription with the exact route-param id, not a Number-corrupted one', async () => {
    const { pinia } = await mountPage()

    const { usePaymentStore } = await import('src/stores/payment.store')
    const paymentStore = usePaymentStore(pinia)

    expect(paymentStore.fetchPlayerSubscription).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    const [calledWithId] = paymentStore.fetchPlayerSubscription.mock.calls[0]
    expect(typeof calledWithId).toBe('string')
    expect(calledWithId).toBe(LARGE_PLAYER_ID)
    expect(calledWithId).not.toBe(CORRUPTED_PLAYER_ID)
  })
})

// A self-registered adult PLAYER owns their profile and pays for it themselves — the mutating
// controls used to be authStore.isParent-only, leaving them a read-only page.
describe('PlayerSubscriptionPage.vue — mutating controls by role', () => {
  const payment = {
    playerTiers: [
      { tier: 'ATHLETE', features: [] },
      { tier: 'SEMI_PRO', features: [] },
      { tier: 'PRO', features: [] },
    ],
    playerSubscription: {
      tier: 'SEMI_PRO',
      status: 'ACTIVE',
      cancelAtPeriodEnd: false,
      subscribed: true,
      billingInterval: 'YEARLY',
    },
  }

  function actionButtons(wrapper) {
    return wrapper.findAll('.tier-actions q-btn-stub')
  }

  it.each(['PARENT', 'PLAYER'])('%s sees upgrade/cancel controls', async (role) => {
    const { wrapper } = await mountPage(LARGE_PLAYER_ID, role, payment)
    // SEMI_PRO (current) → cancel; PRO → upgrade; ATHLETE → free-plan caption, no button.
    expect(actionButtons(wrapper)).toHaveLength(2)
  })

  it('a COACH (not an owner) sees no mutating controls', async () => {
    const { wrapper } = await mountPage(LARGE_PLAYER_ID, 'COACH', payment)
    expect(actionButtons(wrapper)).toHaveLength(0)
  })
})

// fix/payment-subscription-payment-method-sync: a past-due/unpaid subscription points the user at
// "update card", which now retries the outstanding payment.
describe('PlayerSubscriptionPage.vue — payment issue banner', () => {
  const tiers = [
    { tier: 'ATHLETE', features: [] },
    { tier: 'PRO', features: [] },
  ]

  it.each([
    ['PAST_DUE', true],
    ['UNPAID', true],
    ['PAUSED', true],
    ['ACTIVE', false],
  ])('status %s shows the banner: %s', async (status, shown) => {
    const { wrapper } = await mountPage(LARGE_PLAYER_ID, 'PARENT', {
      playerTiers: tiers,
      playerSubscription: { tier: 'PRO', status, cancelAtPeriodEnd: false },
    })

    expect(wrapper.find('[data-test="payment-issue-banner"]').exists()).toBe(shown)
  })
})

// fix/payment-subscription-resume-and-async-sync: the backend applies a saved card to the subscription
// asynchronously (durable outbox), so after saving the banner says the payment is being retried rather
// than implying it already went through.
describe('PlayerSubscriptionPage.vue — banner after saving a card', () => {
  const tiers = [
    { tier: 'ATHLETE', features: [] },
    { tier: 'PRO', features: [] },
  ]
  let previous

  beforeEach(() => {
    previous = config.global.renderStubDefaultSlot
    config.global.renderStubDefaultSlot = true
  })
  afterEach(() => {
    config.global.renderStubDefaultSlot = previous
  })

  it('switches to the "retrying" copy once a card is saved on a past-due subscription', async () => {
    const { wrapper } = await mountPage(LARGE_PLAYER_ID, 'PARENT', {
      playerTiers: tiers,
      playerSubscription: { tier: 'PRO', status: 'PAST_DUE', cancelAtPeriodEnd: false },
    })
    expect(wrapper.find('[data-test="payment-retry-pending"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('subscription.paymentIssue')

    wrapper.findComponent({ name: 'PaymentMethodCard' }).vm.$emit('saved')
    await flushPromises()

    expect(wrapper.find('[data-test="payment-retry-pending"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('subscription.paymentRetrying')
  })

  it('shows the paused copy for a PAUSED subscription', async () => {
    const { wrapper } = await mountPage(LARGE_PLAYER_ID, 'PARENT', {
      playerTiers: tiers,
      playerSubscription: { tier: 'PRO', status: 'PAUSED', cancelAtPeriodEnd: false },
    })

    expect(wrapper.text()).toContain('subscription.paymentPaused')
  })
})

// Pitchside SUB-06: confirm decided between subscribing and changing tier on a stripeSubscriptionId the
// response never carries, so a family on a paid tier was always sent to subscribe again, which the server
// refuses as already active. It now reads the response's `subscribed` flag (ATHLETE is both the free
// fallback and a priced tier, so the tier cannot say). Mutation check: restore the stripeSubscriptionId
// test and the first case turns red.
describe('PlayerSubscriptionPage.vue — subscribe or change tier', () => {
  async function confirmFrom(currentTier, chosenTier, subscribed = currentTier !== 'ATHLETE') {
    const { wrapper, pinia } = await mountPage(LARGE_PLAYER_ID, 'PARENT', {
      playerSubscription: {
        tier: currentTier,
        status: 'ACTIVE',
        cancelAtPeriodEnd: false,
        subscribed,
      },
      playerTiers: [],
      savedPaymentMethod: { hasCard: true },
    })
    const { usePaymentStore } = await import('src/stores/payment.store')
    const paymentStore = usePaymentStore(pinia)
    const setup = wrapper.vm.$.setupState
    setup.openSubscribeDialog(chosenTier)
    await setup.confirmSubscribe()
    await flushPromises()
    return paymentStore
  }

  it('a player on a paid tier changes tier instead of subscribing again', async () => {
    const store = await confirmFrom('SEMI_PRO', 'PRO')
    expect(store.changePlayerTier).toHaveBeenCalledWith({
      playerId: LARGE_PLAYER_ID,
      newTier: 'PRO',
    })
    expect(store.subscribePlayer).not.toHaveBeenCalled()
  })

  it('a player on the free tier subscribes, with the chosen interval', async () => {
    const store = await confirmFrom('ATHLETE', 'SEMI_PRO')
    expect(store.subscribePlayer).toHaveBeenCalledWith({
      playerId: LARGE_PLAYER_ID,
      tier: 'SEMI_PRO',
      billingInterval: 'YEARLY',
    })
    expect(store.changePlayerTier).not.toHaveBeenCalled()
  })

  it('a player paying for ATHLETE changes tier: the flag decides, not the tier', async () => {
    const store = await confirmFrom('ATHLETE', 'SEMI_PRO', true)
    expect(store.changePlayerTier).toHaveBeenCalledWith({
      playerId: LARGE_PLAYER_ID,
      newTier: 'SEMI_PRO',
    })
    expect(store.subscribePlayer).not.toHaveBeenCalled()
  })

  it('offers a paid downgrade but never the free tier, and no change while the plan is set to end', async () => {
    const tiers = ['ATHLETE', 'SEMI_PRO', 'PRO'].map((tier) => ({ tier, features: [] }))
    const offered = async (playerSubscription) => {
      const { wrapper } = await mountPage(LARGE_PLAYER_ID, 'PARENT', {
        playerSubscription,
        playerTiers: tiers,
        savedPaymentMethod: { hasCard: true },
      })
      return wrapper
        .findAll('[data-testid="subscription-tier-card"]')
        .filter((card) => card.find('[data-testid="subscription-change-btn"]').exists())
        .map((card) => card.attributes('data-tier'))
    }
    const plan = {
      status: 'ACTIVE',
      subscribed: true,
      cancelAtPeriodEnd: false,
      billingInterval: 'YEARLY',
    }
    // PRO moves down to SEMI_PRO; ATHLETE is reached by cancelling.
    expect(await offered({ ...plan, tier: 'PRO' })).toEqual(['SEMI_PRO'])
    expect(await offered({ ...plan, tier: 'PRO', pendingTier: 'SEMI_PRO' })).toEqual([])
    expect(await offered({ ...plan, tier: 'SEMI_PRO' })).toEqual(['PRO'])
    expect(await offered({ ...plan, tier: 'SEMI_PRO', cancelAtPeriodEnd: true })).toEqual([])
    // A change keeps the interval, and SEMI_PRO and PRO are yearly only.
    expect(await offered({ ...plan, tier: 'ATHLETE', billingInterval: 'MONTHLY' })).toEqual([])
    expect(await offered({ ...plan, tier: 'ATHLETE', billingInterval: 'YEARLY' })).toEqual([
      'SEMI_PRO',
      'PRO',
    ])
  })

  async function mountPaid(extra = {}) {
    const { wrapper, pinia } = await mountPage(LARGE_PLAYER_ID, 'PARENT', {
      playerSubscription: {
        tier: 'PRO',
        status: 'ACTIVE',
        subscribed: true,
        cancelAtPeriodEnd: false,
        billingInterval: 'YEARLY',
        currentPeriodEnd: '2027-03-12T00:00:00Z',
        ...extra,
      },
      playerTiers: ['ATHLETE', 'SEMI_PRO', 'PRO'].map((tier) => ({ tier, features: [] })),
      savedPaymentMethod: { hasCard: true },
    })
    const { usePaymentStore } = await import('src/stores/payment.store')
    return { wrapper, store: usePaymentStore(pinia), setup: wrapper.vm.$.setupState }
  }

  it('a downgrade is confirmed first, then changes the tier on the subscription', async () => {
    const { store, setup } = await mountPaid()
    setup.openDowngrade('SEMI_PRO')
    expect(setup.downgradeDialog).toBe(true)
    expect(store.changePlayerTier).not.toHaveBeenCalled()
    await setup.confirmDowngrade()
    expect(store.changePlayerTier).toHaveBeenCalledWith({
      playerId: LARGE_PLAYER_ID,
      newTier: 'SEMI_PRO',
    })
    expect(setup.downgradeDialog).toBe(false)
  })

  it('a pending downgrade is shown with a way to keep the current tier', async () => {
    const { wrapper, store, setup } = await mountPaid({
      pendingTier: 'SEMI_PRO',
      pendingEffectiveAt: '2027-03-12T00:00:00Z',
    })
    expect(wrapper.find('[data-testid="subscription-change-pending"]').exists()).toBe(true)
    await setup.handleKeepTier()
    expect(store.changePlayerTier).toHaveBeenCalledWith({
      playerId: LARGE_PLAYER_ID,
      newTier: 'PRO',
    })
  })

  it('Cancel opens a confirmation; only its confirm cancels', async () => {
    const { wrapper, store, setup } = await mountPaid()
    await wrapper.find('[data-testid="subscription-cancel-btn"]').trigger('click')
    expect(setup.cancelDialog).toBe(true)
    expect(store.cancelPlayerSubscription).not.toHaveBeenCalled()
    await setup.handleCancel()
    expect(store.cancelPlayerSubscription).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    expect(setup.cancelDialog).toBe(false)
  })

  it('a plan set to end offers Keep my plan, which takes the cancel back', async () => {
    const { store, setup } = await mountPaid({ cancelAtPeriodEnd: true })
    await setup.handleKeepPlan()
    expect(store.keepPlayerSubscription).toHaveBeenCalledWith(LARGE_PLAYER_ID)
  })

  it('a paid ATHLETE plan can be cancelled and is not labelled free', async () => {
    const { wrapper } = await mountPage(LARGE_PLAYER_ID, 'PARENT', {
      playerSubscription: {
        tier: 'ATHLETE',
        status: 'ACTIVE',
        subscribed: true,
        cancelAtPeriodEnd: false,
        billingInterval: 'MONTHLY',
      },
      playerTiers: [{ tier: 'ATHLETE', features: [] }],
      savedPaymentMethod: { hasCard: true },
    })
    const card = wrapper.find('[data-testid="subscription-tier-card"]')
    expect(card.find('[data-testid="subscription-cancel-btn"]').exists()).toBe(true)
    expect(card.text()).not.toContain(wrapper.vm.$t('subscription.freePlan'))
  })
})
