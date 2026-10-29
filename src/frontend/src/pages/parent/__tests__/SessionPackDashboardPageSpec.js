// fix/pack-status-badges: SessionPackDashboardPage.vue shows one badge per server-computed pack status
// (SessionPackPaymentService.computeStatus). Before this fix a PAUSED pack read as a green "Active" (the
// page looked for status ACTIVE + a client-side pause check), a pack frozen by an open card dispute had no
// label, and a withdrawn (revoked) pack read as "Exhausted". The Pause action is offered only on an ACTIVE
// pack that has sessions left and has not used its one pause.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('src/api/marketplace.api', () => ({
  getCoachProfile: vi.fn().mockResolvedValue({ displayName: 'Coach Carla' }),
}))

import SessionPackDashboardPage from 'src/pages/parent/SessionPackDashboardPage.vue'

const DAY = 24 * 60 * 60 * 1000
const inDays = (n) => new Date(Date.now() + n * DAY).toISOString()

const BASE = {
  id: 'p-1',
  coachId: 'c-1',
  playerId: '42',
  sessionCount: 5,
  creditsRemaining: 3,
  expiresAt: inDays(40),
  pausedFrom: null,
  pausedUntil: null,
  revokedReason: null,
  status: 'ACTIVE',
}

const mounted = []

async function mountPage(packs) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      booking: { sessionPacks: packs, packsLoading: false, packPauseConflicts: [] },
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/parent/players/:playerId/packs', component: SessionPackDashboardPage }],
  })
  router.push('/parent/players/42/packs')
  await router.isReady()
  const wrapper = mount(SessionPackDashboardPage, { global: { plugins: [pinia, router] } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const has = (wrapper, test) => wrapper.find(`[data-test="${test}"]`).exists()
const badge = (wrapper) => wrapper.find('[data-test="pack-status-badge"]')

describe('SessionPackDashboardPage.vue — pack status badges (fix/pack-status-badges)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('ACTIVE: Active badge, Pause offered, no pause/frozen/withdrawn notes', async () => {
    const wrapper = await mountPage([BASE])
    expect(badge(wrapper).text()).toBe('booking.packs.activeStatus')
    expect(badge(wrapper).classes()).toContain('bg-positive')
    expect(has(wrapper, 'pack-pause-cta')).toBe(true)
    expect(has(wrapper, 'pack-paused-until')).toBe(false)
    expect(has(wrapper, 'pack-pause-scheduled')).toBe(false)
    expect(has(wrapper, 'pack-frozen-notice')).toBe(false)
    expect(has(wrapper, 'pack-revoked-reason')).toBe(false)
  })

  it('PAUSED: Paused badge (not Active) with "paused until", no Pause action', async () => {
    const wrapper = await mountPage([
      { ...BASE, status: 'PAUSED', pausedFrom: inDays(-2), pausedUntil: inDays(10) },
    ])
    expect(badge(wrapper).text()).toBe('booking.packs.pausedStatus')
    expect(badge(wrapper).classes()).toContain('bg-warning')
    expect(wrapper.find('[data-test="pack-paused-until"]').text()).toBe(
      'booking.packs.pausedUntilLabel',
    )
    expect(has(wrapper, 'pack-pause-cta')).toBe(false)
  })

  it('a pre-V210 pause (no pausedFrom) that the server reports PAUSED still reads Paused', async () => {
    const wrapper = await mountPage([{ ...BASE, status: 'PAUSED', pausedUntil: inDays(10) }])
    expect(badge(wrapper).text()).toBe('booking.packs.pausedStatus')
    expect(has(wrapper, 'pack-paused-until')).toBe(true)
  })

  it('a pause scheduled for later: Active badge plus a "pause scheduled" note; the one pause is used', async () => {
    const wrapper = await mountPage([
      { ...BASE, status: 'ACTIVE', pausedFrom: inDays(3), pausedUntil: inDays(13) },
    ])
    expect(badge(wrapper).text()).toBe('booking.packs.activeStatus')
    expect(wrapper.find('[data-test="pack-pause-scheduled"]').text()).toBe(
      'booking.packs.pauseScheduledLabel',
    )
    expect(has(wrapper, 'pack-paused-until')).toBe(false)
    expect(has(wrapper, 'pack-pause-cta')).toBe(false)
    expect(wrapper.text()).toContain('booking.packs.alreadyPausedLabel')
  })

  it('FROZEN: Frozen badge with the dispute notice, no Pause action', async () => {
    const wrapper = await mountPage([{ ...BASE, status: 'FROZEN' }])
    expect(badge(wrapper).text()).toBe('booking.packs.frozenStatus')
    expect(badge(wrapper).classes()).toContain('bg-deep-orange')
    expect(wrapper.find('[data-test="pack-frozen-notice"]').text()).toBe(
      'booking.packs.frozenNotice',
    )
    expect(has(wrapper, 'pack-pause-cta')).toBe(false)
  })

  it.each([
    ['CHARGEBACK_LOST', 'booking.packs.revokedChargebackLost'],
    ['EXTERNAL_REFUND', 'booking.packs.revokedExternalRefund'],
    ['COACH_SUSPENDED', 'booking.packs.revokedCoachSuspended'],
    [null, 'booking.packs.revokedOther'],
  ])(
    'REVOKED (%s): Withdrawn badge (not Exhausted) with its reason, no expiry, no Pause',
    async (reason, text) => {
      const wrapper = await mountPage([
        { ...BASE, status: 'REVOKED', revokedReason: reason, creditsRemaining: 0 },
      ])
      expect(badge(wrapper).text()).toBe('booking.packs.revokedStatus')
      expect(wrapper.find('[data-test="pack-revoked-reason"]').text()).toBe(text)
      expect(wrapper.text()).not.toContain('booking.packs.expiresLabel')
      expect(wrapper.text()).not.toContain('booking.packs.exhaustedLabel')
      expect(has(wrapper, 'pack-pause-cta')).toBe(false)
    },
  )

  it('EXPIRED: Expired badge, no Pause', async () => {
    const wrapper = await mountPage([{ ...BASE, status: 'EXPIRED', expiresAt: inDays(-1) }])
    expect(badge(wrapper).text()).toBe('booking.packs.expiredLabel')
    expect(badge(wrapper).classes()).toContain('bg-negative')
    expect(has(wrapper, 'pack-pause-cta')).toBe(false)
  })

  it('EXHAUSTED: Exhausted badge, no Pause, no withdrawn reason', async () => {
    const wrapper = await mountPage([{ ...BASE, status: 'EXHAUSTED', creditsRemaining: 0 }])
    expect(badge(wrapper).text()).toBe('booking.packs.exhaustedLabel')
    expect(has(wrapper, 'pack-pause-cta')).toBe(false)
    expect(has(wrapper, 'pack-revoked-reason')).toBe(false)
  })
})
