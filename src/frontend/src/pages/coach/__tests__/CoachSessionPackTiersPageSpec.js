// skillars-deferred-151 AC2 — CoachSessionPackTiersPage.vue first-ever coverage.
//
// The backend has no tier "catalog" to manage (idx_spt_one_active_per_coach — at most one active
// tier per coach, DB-enforced). This page shows a current-offer + read-only-history model, not a
// multi-row catalog with per-row deactivate.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises, DOMWrapper } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US'

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  const real = actual.useQuasar
  return {
    ...actual,
    useQuasar: () => {
      const $q = real()
      $q.notify = notifyMock
      return $q
    },
  }
})

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

  // fix/pack-expiry-size-tiers: the coach sees how long a pack bought from the current offer lasts.
  it('shows the active offer validity days when the backend sends them', () => {
    const wrapper = mountPage([{ ...ACTIVE_TIER, validityDays: 365 }])
    const validity = wrapper.find('[data-testid="tier-validity"]')
    expect(validity.exists()).toBe(true)
    expect(validity.text()).toBe('booking.packs.validFor')
  })

  it('omits the validity line when the tier carries no validityDays', () => {
    const wrapper = mountPage([ACTIVE_TIER])
    expect(wrapper.find('[data-testid="tier-validity"]').exists()).toBe(false)
  })

  // skillars-deferred-170 AC2: each tier names itself once. These mount with the real en-US messages: the
  // global test i18n has none, so it renders bare keys and would hide a {label} repeated in the caption.
  describe('naming each tier once (real messages)', () => {
    function mountWithMessages(state) {
      const i18n = createI18n({ legacy: false, locale: 'en-US', messages: { 'en-US': enUS } })
      const pinia = createTestingPinia({
        createSpy: vi.fn,
        initialState: { payment: { sessionPackTiers: [], loading: {}, error: {}, ...state } },
      })
      const wrapper = mount(CoachSessionPackTiersPage, {
        global: { plugins: [pinia, i18n] },
        attachTo: document.body,
      })
      mounted.push(wrapper)
      return wrapper
    }

    function occurrences(text, part) {
      return text.split(part).length - 1
    }

    it('heads a labelled tier with its label, captions it with the summary, and never repeats the label', () => {
      const wrapper = mountWithMessages({
        sessionPackTiers: [
          { ...ACTIVE_TIER, label: 'Season 10', sessionCount: 10, totalPrice: 380 },
          { ...OLD_TIER, label: 'Starter 5', sessionCount: 5, totalPrice: 200 },
        ],
      })

      const text = wrapper.text()
      expect(text).toContain('10 sessions for €380')
      expect(text).toContain('5 sessions for €200')
      // Mutation: restore a caption that interpolates the label ('{label} — ...') -> 2 occurrences -> RED.
      expect(occurrences(text, 'Season 10')).toBe(1)
      expect(occurrences(text, 'Starter 5')).toBe(1)
    })

    it('heads a label-less tier (set from the profile builder) with the summary alone, with no second line', () => {
      const wrapper = mountWithMessages({
        sessionPackTiers: [
          { ...ACTIVE_TIER, label: null, sessionCount: 5, totalPrice: 200 },
          { ...OLD_TIER, label: null, sessionCount: 3, totalPrice: 120 },
        ],
      })

      const text = wrapper.text()
      expect(occurrences(text, '5 sessions for €200')).toBe(1)
      expect(occurrences(text, '3 sessions for €120')).toBe(1)
      expect(text).not.toContain('5 sessions —')
      expect(text).not.toContain('null')
    })

    it('names a sold pack from a label-less tier by its size instead of a dash', () => {
      const wrapper = mountWithMessages({
        soldPacks: [
          {
            purchaseId: 'p-9',
            buyerName: 'Pat Parent',
            playerId: 7,
            playerName: 'Mia',
            tierLabel: null,
            sessionCount: 5,
            remainingSessions: 3,
            expiresAt: '2026-11-05T00:00:00Z',
            extendedAt: null,
            status: 'ACTIVE',
            expiringSoon: false,
            extendable: false,
          },
        ],
      })

      const row = wrapper.find('[data-testid="sold-pack"]')
      expect(row.text()).toContain('5 sessions ·')
      expect(row.text()).not.toContain('—')
    })

    it('keeps the dash for a sold pack whose tier row is gone (sessionCount 0)', () => {
      const wrapper = mountWithMessages({
        soldPacks: [
          {
            purchaseId: 'p-10',
            buyerName: 'Mia',
            playerId: 7,
            playerName: 'Mia',
            tierLabel: null,
            sessionCount: 0,
            remainingSessions: 0,
            expiresAt: '2026-11-05T00:00:00Z',
            extendedAt: null,
            status: 'ACTIVE',
            expiringSoon: false,
            extendable: false,
          },
        ],
      })

      const row = wrapper.find('[data-testid="sold-pack"]').text()
      expect(row).toContain('— ·')
      expect(row).not.toContain('0 sessions ·')
    })
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

  // feat/coach-workflow-gaps A20: the raw purchase-UUID input is gone — the coach extends from the
  // list of packs they sold, and only where the backend says the pack is extendable.
  const SOLD_EXTENDABLE = {
    purchaseId: 'p-1',
    buyerName: 'Pat Parent',
    playerId: 7,
    playerName: 'Mia',
    tierLabel: 'Standard pack',
    sessionCount: 10,
    remainingSessions: 3,
    expiresAt: '2026-11-05T00:00:00Z',
    extendedAt: null,
    status: 'ACTIVE',
    expiringSoon: true,
    extendable: true,
  }
  const SOLD_NOT_EXTENDABLE = {
    ...SOLD_EXTENDABLE,
    purchaseId: 'p-2',
    playerName: 'Leo',
    buyerName: 'Leo',
    expiringSoon: false,
    extendable: false,
  }

  function mountWithSold(soldPacks) {
    const pinia = createTestingPinia({
      createSpy: vi.fn,
      initialState: { payment: { sessionPackTiers: [], soldPacks, loading: {}, error: {} } },
    })
    const wrapper = mount(CoachSessionPackTiersPage, { global: { plugins: [pinia] } })
    mounted.push(wrapper)
    return wrapper
  }

  it('loads the first page of sold packs on mount and has no raw purchase-ID input', () => {
    const wrapper = mountPage([])
    expect(storeOf(wrapper).fetchSoldPacks).toHaveBeenCalledWith(0)
    expect(wrapper.text()).not.toContain('payment.myTiers.extendPurchaseId')
  })

  it('lists sold packs with player, buyer and an expiring-soon flag; Extend only on extendable rows', () => {
    const wrapper = mountWithSold([SOLD_EXTENDABLE, SOLD_NOT_EXTENDABLE])
    const rows = wrapper.findAll('[data-testid="sold-pack"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Mia')
    expect(rows[0].text()).toContain('payment.myTiers.boughtBy')
    expect(rows[0].text()).toContain('payment.myTiers.expiringSoon')
    expect(rows[0].find('[data-testid="extend-pack"]').exists()).toBe(true)
    // buyer == player (self-purchase): no redundant "bought by" line
    expect(rows[1].text()).not.toContain('payment.myTiers.boughtBy')
    expect(rows[1].find('[data-testid="extend-pack"]').exists()).toBe(false)
  })

  // fix/pack-status-badges: a pack frozen by an open card dispute and a withdrawn (revoked) pack get their own
  // badge and a one-line reason; a withdrawn pack never offers Extend.
  it.each([
    [
      'FROZEN',
      null,
      'payment.myTiers.packStatus.FROZEN',
      'payment.myTiers.packStatusNote.frozenNote',
    ],
    [
      'REVOKED',
      'CHARGEBACK_LOST',
      'payment.myTiers.packStatus.REVOKED',
      'payment.myTiers.packStatusNote.revokedChargebackLost',
    ],
    [
      'REVOKED',
      'EXTERNAL_REFUND',
      'payment.myTiers.packStatus.REVOKED',
      'payment.myTiers.packStatusNote.revokedExternalRefund',
    ],
    [
      'REVOKED',
      'COACH_SUSPENDED',
      'payment.myTiers.packStatus.REVOKED',
      'payment.myTiers.packStatusNote.revokedCoachSuspended',
    ],
  ])('a %s pack (%s) shows its badge and reason', (status, revokedReason, badge, note) => {
    const wrapper = mountWithSold([{ ...SOLD_EXTENDABLE, status, revokedReason }])
    const row = wrapper.find('[data-testid="sold-pack"]')
    expect(row.find('[data-testid="sold-pack-status"]').text()).toBe(badge)
    expect(row.find('[data-testid="sold-pack-status-note"]').text()).toBe(note)
    expect(row.find('[data-testid="extend-pack"]').exists()).toBe(status !== 'REVOKED')
  })

  it('an active pack shows the Active badge and no status note', () => {
    const wrapper = mountWithSold([SOLD_EXTENDABLE])
    const row = wrapper.find('[data-testid="sold-pack"]')
    expect(row.find('[data-testid="sold-pack-status"]').text()).toBe(
      'payment.myTiers.packStatus.ACTIVE',
    )
    expect(row.find('[data-testid="sold-pack-status-note"]').exists()).toBe(false)
  })

  it('Extend calls the existing extend endpoint for that row and re-reads the list', async () => {
    const wrapper = mountWithSold([SOLD_EXTENDABLE])
    const store = storeOf(wrapper)
    store.extendSessionPack.mockResolvedValue()
    store.fetchSoldPacks.mockClear()

    await wrapper.find('[data-testid="extend-pack"]').trigger('click')
    await flushPromises()

    expect(store.extendSessionPack).toHaveBeenCalledWith('p-1')
    expect(store.fetchSoldPacks).toHaveBeenCalledWith(0)
  })

  it('extend shows one generic failure message on error, not a branched backend reason', async () => {
    const wrapper = mountWithSold([SOLD_EXTENDABLE])
    const store = storeOf(wrapper)
    store.extendSessionPack.mockRejectedValue(new Error('409'))

    await wrapper.find('[data-testid="extend-pack"]').trigger('click')
    await flushPromises()

    expect(store.extendSessionPack).toHaveBeenCalledWith('p-1')
    expect(wrapper.text()).toContain('payment.myTiers.extendFailed')
  })
})
