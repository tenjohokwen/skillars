// skillars-12-3 Task 5.1 — first-ever coverage for SessionPrepCard.vue. Covers loading, error, the
// three independent empty states from AC5 (never-trained-together / no-recap-yet /
// no-focus-thread-yet, asserted separately so they can't be accidentally conflated), the explicit
// zero-neglected-skills empty state, skill-code-to-displayName resolution, a fully populated render,
// and the "Plan this session" link's resolved route.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/development.api', () => ({
  getSessionPrep: vi.fn(),
}))

import { getSessionPrep } from 'src/api/development.api'
import SessionPrepCard from 'src/components/booking/SessionPrepCard.vue'

const PREP = (overrides = {}) => ({
  playerId: 1,
  lastSessionDate: null,
  lastWhatWeDid: null,
  lastWhatsNext: null,
  focusThreadSkillCodes: [],
  openNeglectedSkillCount: 0,
  ...overrides,
})

const SKILL_DEFINITIONS = [
  { code: 'PAC', displayName: 'Pace' },
  { code: 'DRI', displayName: 'Dribbling' },
]

const mounted = []

function makeRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      {
        path: '/coach/session-builder/:bookingId',
        name: 'coach-session-builder',
        component: { template: '<div />' },
      },
    ],
  })
  router.push('/')
  return router
}

async function mountCard(props = {}, skillDefinitions = SKILL_DEFINITIONS) {
  const router = makeRouter()
  await router.isReady()
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { development: { skillDefinitions } },
  })

  const wrapper = mount(SessionPrepCard, {
    props: {
      bookingId: 'booking-1',
      playerName: 'Alex Kane',
      ...props,
    },
    global: { plugins: [router, pinia] },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('SessionPrepCard.vue (skillars-12-3)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  // skillars-12-3 code review (2026-10-15) Patch: previously asserted the spinner synchronously
  // right after mount, before any `await` — true only because `loading` defaults to `true`, so the
  // assertion passed even with the fetch call deleted entirely. Now proves the loading->done
  // transition is actually driven by the fetch resolving.
  it('shows a spinner while loading, and hides it once the fetch resolves', async () => {
    let resolveFetch
    getSessionPrep.mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve
      }),
    )
    const router = makeRouter()
    await router.isReady()
    const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { development: {} } })
    const wrapper = mount(SessionPrepCard, {
      props: { bookingId: 'booking-1', playerName: 'Alex Kane' },
      global: { plugins: [router, pinia] },
    })
    mounted.push(wrapper)

    expect(wrapper.findComponent({ name: 'QSpinner' }).exists()).toBe(true)

    resolveFetch(PREP())
    await flushPromises()

    expect(wrapper.findComponent({ name: 'QSpinner' }).exists()).toBe(false)
  })

  it('renders a dedicated error state on a rejected call', async () => {
    getSessionPrep.mockRejectedValue(new Error('network error'))
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('coach.sessionPrepError')
  })

  it("shows 'never trained together' when lastSessionDate is null", async () => {
    getSessionPrep.mockResolvedValue(PREP())
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('coach.myPlayersNeverTrainedTogether')
    expect(wrapper.text()).not.toContain('coach.sessionPrepNoRecapYet')
  })

  it("shows 'no recap yet' when lastSessionDate is present but both recap fields are null", async () => {
    getSessionPrep.mockResolvedValue(PREP({ lastSessionDate: '2026-01-01T00:00:00Z' }))
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('coach.sessionPrepNoRecapYet')
    expect(wrapper.text()).not.toContain('coach.myPlayersNeverTrainedTogether')
  })

  // skillars-deferred-155 AC12: hasRecap tests blankness via ?.trim(), not plain truthiness — a
  // whitespace-only value must fall through to the "no recap yet" empty state, not an empty <dl>.
  it("shows 'no recap yet' when both recap fields are whitespace-only", async () => {
    getSessionPrep.mockResolvedValue(
      PREP({ lastSessionDate: '2026-01-01T00:00:00Z', lastWhatWeDid: '   ', lastWhatsNext: '\n' }),
    )
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('coach.sessionPrepNoRecapYet')
    expect(wrapper.find('dl').exists()).toBe(false)
  })

  // skillars-deferred-155 code review: hasRecap is an OR, so with only ONE of the two fields
  // populated the other used to render a bolded <dt> over an empty <dd> — a semantically declared
  // empty definition. Reachable in production, not theoretical: TimelineEventListener.java:46-54
  // documents that a publisher can pass real nulls for any booking whose session_completion_data row
  // predates V159 (both columns nullable, no backfill). Each field now carries its own guard, as
  // PlayerTimelinePanel.vue's equivalent block already did.
  it('renders only the populated half of a one-sided recap, with no empty label', async () => {
    getSessionPrep.mockResolvedValue(
      PREP({
        lastSessionDate: '2026-01-01T00:00:00Z',
        lastWhatWeDid: 'Worked on first touch',
        lastWhatsNext: null,
      }),
    )
    const wrapper = await mountCard()

    expect(wrapper.find('dl').exists()).toBe(true)
    expect(wrapper.text()).toContain('Worked on first touch')
    expect(wrapper.text()).toContain('coach.sessionPrepWhatWeDidLabel')
    expect(wrapper.text()).not.toContain('coach.sessionPrepWhatsNextLabel')
    expect(wrapper.findAll('dt')).toHaveLength(1)
    expect(wrapper.findAll('dd')).toHaveLength(1)
  })

  it('drops a whitespace-only half of a recap rather than labelling it', async () => {
    getSessionPrep.mockResolvedValue(
      PREP({
        lastSessionDate: '2026-01-01T00:00:00Z',
        lastWhatWeDid: '   ',
        lastWhatsNext: 'Shooting drills next',
      }),
    )
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('Shooting drills next')
    expect(wrapper.text()).not.toContain('coach.sessionPrepWhatWeDidLabel')
    expect(wrapper.findAll('dt')).toHaveLength(1)
  })

  it("shows 'no focus thread yet' when focusThreadSkillCodes is empty", async () => {
    getSessionPrep.mockResolvedValue(PREP({ lastSessionDate: '2026-01-01T00:00:00Z' }))
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('coach.sessionPrepNoFocusThreadYet')
  })

  // skillars-12-3 code review (2026-10-15) Patch, AC5: openNeglectedSkillCount === 0 previously
  // rendered an empty, padded card section instead of an honest empty-state string.
  it("shows an explicit 'no neglected skills' empty state when openNeglectedSkillCount is 0", async () => {
    getSessionPrep.mockResolvedValue(PREP({ openNeglectedSkillCount: 0 }))
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('coach.sessionPrepNoNeglectedSkills')
    expect(wrapper.text()).not.toContain('coach.myPlayersNeglectedSkillCount')
  })

  // skillars-12-3 code review (2026-10-15) Patch, AC7: focus-thread badges must resolve internal
  // skill codes to their translated/shared displayName, not render the raw code.
  it('resolves focus-thread skill codes to their displayName via the shared skill-definitions dictionary', async () => {
    getSessionPrep.mockResolvedValue(
      PREP({ lastSessionDate: '2026-01-01T00:00:00Z', focusThreadSkillCodes: ['PAC', 'DRI'] }),
    )
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('Pace')
    expect(wrapper.text()).toContain('Dribbling')
    expect(wrapper.text()).not.toContain('PAC')
    expect(wrapper.text()).not.toContain('DRI')
  })

  it('falls back to the raw code when a focus-thread code is missing from the dictionary', async () => {
    getSessionPrep.mockResolvedValue(
      PREP({ lastSessionDate: '2026-01-01T00:00:00Z', focusThreadSkillCodes: ['UNKNOWN'] }),
    )
    const wrapper = await mountCard({}, [])

    expect(wrapper.text()).toContain('UNKNOWN')
  })

  it('renders all four fields in a fully populated case', async () => {
    getSessionPrep.mockResolvedValue(
      PREP({
        lastSessionDate: '2026-01-01T00:00:00Z',
        lastWhatWeDid: 'Worked on first touch',
        lastWhatsNext: 'Continue weak-foot drills',
        focusThreadSkillCodes: ['PAC', 'DRI'],
        openNeglectedSkillCount: 2,
      }),
    )
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('Worked on first touch')
    expect(wrapper.text()).toContain('Continue weak-foot drills')
    expect(wrapper.text()).toContain('Pace')
    expect(wrapper.text()).toContain('Dribbling')
    expect(wrapper.text()).toContain('coach.myPlayersNeglectedSkillCount')
    expect(wrapper.text()).not.toContain('coach.sessionPrepNoRecapYet')
    expect(wrapper.text()).not.toContain('coach.sessionPrepNoFocusThreadYet')
    expect(wrapper.text()).not.toContain('coach.sessionPrepNoNeglectedSkills')
  })

  // skillars-deferred-155 AC7: the player/parent-initiated session-prep request, read-only on this
  // card. Guarded on data presence alone — no new prop, matching skillars-12-5's own precedent.
  it('renders the player request label and text when present', async () => {
    getSessionPrep.mockResolvedValue(PREP({ playerRequest: 'Can we work on free kicks?' }))
    const wrapper = await mountCard()

    expect(wrapper.text()).toContain('booking.requests.playerRequestLabel')
    expect(wrapper.text()).toContain('Can we work on free kicks?')
  })

  it('renders no player request section when playerRequest is absent', async () => {
    getSessionPrep.mockResolvedValue(PREP())
    const wrapper = await mountCard()

    expect(wrapper.text()).not.toContain('booking.requests.playerRequestLabel')
  })

  it('the "Plan this session" link resolves to coach/session-builder/:bookingId for the given bookingId prop', async () => {
    getSessionPrep.mockResolvedValue(PREP())
    const wrapper = await mountCard({ bookingId: 'booking-xyz' })

    const planLink = wrapper.findAll('a').find((a) => a.text() === 'coach.sessionPrepPlanButton')
    expect(planLink.attributes('href')).toBe('/coach/session-builder/booking-xyz')
  })
})
