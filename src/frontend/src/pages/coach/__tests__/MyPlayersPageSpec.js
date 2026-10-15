// skillars-12-1 AC8: first-ever coverage for MyPlayersPage.vue — mirrors
// CoachReviewsPageSpec.js's mock/mount/flushPromises pattern.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/development.api', () => ({
  getCoachRoster: vi.fn(),
}))

import { getCoachRoster } from 'src/api/development.api'
import MyPlayersPage from 'src/pages/coach/MyPlayersPage.vue'

const PLAYER = (overrides = {}) => ({
  playerId: 1,
  playerName: 'Alex Kane',
  lastSessionDate: '2026-01-01T00:00:00Z',
  openNeglectedSkillCount: 0,
  dormant: false,
  attentionScore: 0.1,
  ...overrides,
})

const mounted = []
function mountPage() {
  const wrapper = mount(MyPlayersPage)
  mounted.push(wrapper)
  return wrapper
}

describe('MyPlayersPage.vue (skillars-12-1 AC8)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('shows a spinner while loading', () => {
    getCoachRoster.mockReturnValue(new Promise(() => {}))
    const wrapper = mountPage()

    expect(wrapper.findComponent({ name: 'QSpinner' }).exists()).toBe(true)
  })

  it('renders an incomplete-profile state on a 404 (no CoachProfile row yet)', async () => {
    getCoachRoster.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersIncompleteProfile')
  })

  it('renders a generic error state on a non-404 failure', async () => {
    getCoachRoster.mockRejectedValue({ response: { status: 500 } })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('common.errorGeneric')
  })

  it('renders an explicit empty state when the roster has zero entries', async () => {
    getCoachRoster.mockResolvedValue({ players: [] })
    const wrapper = mountPage()
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
        PLAYER({ playerId: 2, playerName: 'Sam Rivera', dormant: true, lastSessionDate: null }),
      ],
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Alex Kane')
    expect(wrapper.text()).toContain('Sam Rivera')
    expect(wrapper.findAll('.player-item')).toHaveLength(2)
    expect(wrapper.text()).toContain('coach.myPlayersLastSessionLabel')
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
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersDormantBadge')
    expect(wrapper.text()).toContain('coach.myPlayersLastSessionLabel')
  })

  it("shows 'never trained together' when lastSessionDate is null and not dormant", async () => {
    getCoachRoster.mockResolvedValue({
      players: [PLAYER({ dormant: false, lastSessionDate: null })],
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('coach.myPlayersNeverTrainedTogether')
  })
})
