// skillars-12-1 AC8: first-ever coverage for MyPlayersPage.vue — mirrors
// CoachReviewsPageSpec.js's mock/mount/flushPromises pattern.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/development.api', () => ({
  getCoachRoster: vi.fn(),
}))

vi.mock('src/api/messaging.api', () => ({
  initiateConversation: vi.fn(),
  fetchConversations: vi.fn(),
  fetchMessages: vi.fn(),
  fetchPlayerConversations: vi.fn(),
  fetchPlayerConversationMessages: vi.fn(),
  sendMessage: vi.fn(),
  subscribeToEvents: vi.fn(),
}))

import { getCoachRoster } from 'src/api/development.api'
import { initiateConversation } from 'src/api/messaging.api'
import MyPlayersPage from 'src/pages/coach/MyPlayersPage.vue'

// A realistic 18-19 digit Tsid, reused from ParentDevelopmentPortalPageSpec.js's own fixture —
// large enough that a Number(...) regression on the receiving CoachPlayerDetailPage would be
// caught, unlike this spec's other fixtures' small playerId: 1.
const LARGE_PLAYER_ID = '893573203704173564'

const PLAYER = (overrides = {}) => ({
  playerId: 1,
  playerName: 'Alex Kane',
  lastSessionDate: '2026-01-01T00:00:00Z',
  openNeglectedSkillCount: 0,
  dormant: false,
  attentionScore: 0.1,
  daysSinceLastSession: 5,
  hasUpcomingBooking: false,
  accessExpired: false,
  ...overrides,
})

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/coach/players', name: 'coach-players', component: { template: '<div />' } },
      {
        path: '/coach/players/:playerId',
        name: 'coach-player-detail',
        component: { template: '<div />' },
      },
      { path: '/messaging', name: 'messaging', component: { template: '<div />' } },
    ],
  })
}

const mounted = []
function mountPage() {
  const router = createTestRouter()
  // Real (non-stubbed) messaging store actions so the "Message" entry point reaches the mocked API.
  const pinia = createTestingPinia({ createSpy: vi.fn, stubActions: false })
  const wrapper = mount(MyPlayersPage, { global: { plugins: [router, pinia] } })
  mounted.push(wrapper)
  return { wrapper, router }
}

describe('MyPlayersPage.vue (skillars-12-1 AC8)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('shows a spinner while loading', () => {
    getCoachRoster.mockReturnValue(new Promise(() => {}))
    const { wrapper } = mountPage()

    expect(wrapper.findComponent({ name: 'QSpinner' }).exists()).toBe(true)
  })

  it('renders an incomplete-profile state on a 404 (no CoachProfile row yet)', async () => {
    getCoachRoster.mockRejectedValue({ response: { status: 404 } })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersIncompleteProfile')
  })

  it('renders a generic error state on a non-404 failure', async () => {
    getCoachRoster.mockRejectedValue({ response: { status: 500 } })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('common.errorGeneric')
  })

  it('renders an explicit empty state when the roster has zero entries', async () => {
    getCoachRoster.mockResolvedValue({ players: [] })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersEmpty')
  })

  // skillars-12-1 code review (2026-10-15) Patch: renamed from a title claiming this test verified
  // sort order — the page renders players in whatever order the API returns (sorting is the
  // backend's job, per CoachRosterService), so there is no frontend ordering behavior to assert
  // here. Fixed to actually assert the last-session text and neglected-count badge text the old
  // title claimed but never checked.
  it('renders each roster row with name, last-session date, and neglected-skill count', async () => {
    getCoachRoster.mockResolvedValue({
      players: [
        PLAYER({
          playerId: 1,
          playerName: 'Alex Kane',
          lastSessionDate: '2026-01-01T00:00:00Z',
          openNeglectedSkillCount: 2,
        }),
        PLAYER({
          playerId: 2,
          playerName: 'Sam Rivera',
          dormant: true,
          lastSessionDate: null,
          daysSinceLastSession: null,
        }),
      ],
    })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Alex Kane')
    expect(wrapper.text()).toContain('Sam Rivera')
    expect(wrapper.findAll('.player-item')).toHaveLength(2)
    expect(wrapper.text()).toContain('coach.myPlayersLastTrainedDaysAgo')
    expect(wrapper.text()).toContain('coach.myPlayersNeglectedSkillCount')
  })

  // skillars-12-1 code review (2026-10-15) Patch: the fixture now pairs dormant:true with a
  // non-null lastSessionDate — previously both were null-paired, so the caption rendered the same
  // dormant-badge string the q-badge did, and this test passed even with the q-badge block deleted
  // entirely. Asserting the date text separately from the badge text makes each one load-bearing.
  it('shows the dormant badge, with the caption still showing the real last-session date', async () => {
    getCoachRoster.mockResolvedValue({
      players: [PLAYER({ dormant: true, lastSessionDate: '2026-01-01T00:00:00Z' })],
    })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersDormantBadge')
    expect(wrapper.text()).toContain('coach.myPlayersLastTrainedDaysAgo')
  })

  // skillars-12-6 AC9: the three caption rules, each asserted separately and exclusively.
  it('rule 1: an attended session shows the days-ago caption only', async () => {
    getCoachRoster.mockResolvedValue({ players: [PLAYER({ daysSinceLastSession: '3' })] })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersLastTrainedDaysAgo')
    expect(wrapper.text()).not.toContain('coach.myPlayersBookedNoSessionYet')
    expect(wrapper.text()).not.toContain('coach.myPlayersNeverTrainedTogether')
  })

  it('rule 2: no attended session and no upcoming booking shows "booked, but no attended session yet" beside the dormant badge', async () => {
    getCoachRoster.mockResolvedValue({
      players: [
        PLAYER({
          dormant: true,
          lastSessionDate: null,
          daysSinceLastSession: null,
          hasUpcomingBooking: false,
        }),
      ],
    })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersDormantBadge')
    expect(wrapper.text()).toContain('coach.myPlayersBookedNoSessionYet')
    expect(wrapper.text()).not.toContain('coach.myPlayersNeverTrainedTogether')
  })

  it('rule 3: no attended session but an upcoming booking shows "haven\'t trained together yet"', async () => {
    getCoachRoster.mockResolvedValue({
      players: [
        PLAYER({ lastSessionDate: null, daysSinceLastSession: null, hasUpcomingBooking: true }),
      ],
    })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersNeverTrainedTogether')
    expect(wrapper.text()).not.toContain('coach.myPlayersBookedNoSessionYet')
  })

  it('treats a zero days value as an attended session, not as missing', async () => {
    getCoachRoster.mockResolvedValue({ players: [PLAYER({ daysSinceLastSession: 0 })] })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersLastTrainedDaysAgo')
  })

  it("shows 'never trained together' when lastSessionDate is null, not dormant, and a session is upcoming", async () => {
    getCoachRoster.mockResolvedValue({
      players: [
        PLAYER({
          dormant: false,
          lastSessionDate: null,
          daysSinceLastSession: null,
          hasUpcomingBooking: true,
        }),
      ],
    })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersNeverTrainedTogether')
  })

  it('navigates to coach-player-detail with only the playerId param, no playerName query, on row click', async () => {
    getCoachRoster.mockResolvedValue({
      players: [PLAYER({ playerId: LARGE_PLAYER_ID, playerName: 'Alex Kane' })],
    })
    const { wrapper, router } = mountPage()
    await router.push('/coach/players')
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('.player-item').trigger('click')

    // skillars-deferred-156 AC5: playerName no longer travels through the route's query string —
    // a minor's name in the URL reaches browser history/bookmarks/referrers. The exact object
    // (no query key at all) proves it, not just that the key's value changed.
    expect(pushSpy).toHaveBeenCalledWith({
      name: 'coach-player-detail',
      params: { playerId: LARGE_PLAYER_ID },
    })
  })

  // Review Patch 7: rows were mouse-only (no role, tabindex, or keyboard handler) — a keyboard or
  // screen-reader user had no way to reach the new detail page at all.
  it('is keyboard-reachable: row has role=button/tabindex and Enter/Space trigger navigation', async () => {
    getCoachRoster.mockResolvedValue({
      players: [PLAYER({ playerId: LARGE_PLAYER_ID, playerName: 'Alex Kane' })],
    })
    const { wrapper, router } = mountPage()
    await router.push('/coach/players')
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')
    const row = wrapper.find('.player-item')

    expect(row.attributes('role')).toBe('button')
    expect(row.attributes('tabindex')).toBe('0')

    await row.trigger('keyup.enter')
    await row.trigger('keyup.space')

    expect(pushSpy).toHaveBeenCalledTimes(2)
    // skillars-deferred-156 AC5: playerName no longer travels through the route's query string —
    // a minor's name in the URL reaches browser history/bookmarks/referrers. The exact object
    // (no query key at all) proves it, not just that the key's value changed.
    expect(pushSpy).toHaveBeenCalledWith({
      name: 'coach-player-detail',
      params: { playerId: LARGE_PLAYER_ID },
    })
  })

  // skillars-12-10 (D6): lapsed players are findable but not openable.
  it('lists expired players in a separate Lapsed group: not clickable, no badges, no detail link', async () => {
    getCoachRoster.mockResolvedValue({
      players: [
        PLAYER({ playerId: 1, playerName: 'Open Olly' }),
        PLAYER({
          playerId: 2,
          playerName: 'Lapsed Lou',
          accessExpired: true,
          openNeglectedSkillCount: 0,
          daysSinceLastSession: 120,
        }),
      ],
    })
    const { wrapper, router } = mountPage()
    const push = vi.spyOn(router, 'push')
    await flushPromises()

    expect(wrapper.findAll('.player-item.clickable')).toHaveLength(1)
    const lapsed = wrapper.findAll('[data-testid="lapsed-player"]')
    expect(lapsed).toHaveLength(1)
    expect(lapsed[0].text()).toContain('Lapsed Lou')
    expect(lapsed[0].attributes('role')).toBeUndefined()
    expect(wrapper.find('[data-testid="lapsed-heading"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="all-lapsed-hint"]').exists()).toBe(false)

    await lapsed[0].trigger('click')
    expect(push).not.toHaveBeenCalled()
  })

  it('when every player is lapsed shows the all-lapsed hint and the group, not the empty state', async () => {
    getCoachRoster.mockResolvedValue({
      players: [PLAYER({ playerId: 2, playerName: 'Lapsed Lou', accessExpired: true })],
    })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.find('[data-testid="all-lapsed-hint"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('coach.myPlayersEmpty')
    expect(wrapper.find('[data-testid="lapsed-heading"]').exists()).toBe(true)
  })

  it('shows no Lapsed group when nobody is expired', async () => {
    getCoachRoster.mockResolvedValue({ players: [PLAYER()] })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.find('[data-testid="lapsed-heading"]').exists()).toBe(false)
  })

  it('Message button opens the conversation for that player (coachId omitted) and navigates to it, without opening the detail page', async () => {
    getCoachRoster.mockResolvedValue({ players: [PLAYER({ playerId: LARGE_PLAYER_ID })] })
    initiateConversation.mockResolvedValue({
      conversationId: '555',
      status: 'ACTIVE',
      canSend: true,
    })
    const { wrapper, router } = mountPage()
    const push = vi.spyOn(router, 'push')
    await flushPromises()

    await wrapper.find(`[data-testid="roster-message-${LARGE_PLAYER_ID}"]`).trigger('click')
    await flushPromises()

    expect(initiateConversation).toHaveBeenCalledWith(null, LARGE_PLAYER_ID)
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ name: 'messaging', query: { conversation: '555' } })
  })

  it('lapsed players get no Message button', async () => {
    getCoachRoster.mockResolvedValue({
      players: [PLAYER({ playerId: 2, playerName: 'Lapsed Lou', accessExpired: true })],
    })
    const { wrapper } = mountPage()
    await flushPromises()

    expect(wrapper.find('[data-testid="roster-message-2"]').exists()).toBe(false)
  })
})
