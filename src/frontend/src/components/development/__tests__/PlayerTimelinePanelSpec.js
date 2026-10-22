// skillars-deferred-153 AC4: PlayerTimelinePanel.vue's "Load more" button — no spec covered this
// component's timeline-rendering interaction before this story.

import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDevelopmentStore } from 'src/stores/development.store'
import PlayerTimelinePanel from '../PlayerTimelinePanel.vue'

function mountPanel(timelineState, overrides = {}) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      development: {
        timeline: timelineState,
        timelineLoading: false,
        timelineLoadingMore: false,
        timelinePage: 0,
        timelineError: null,
        timelineLoadMoreError: null,
        ...overrides,
      },
    },
  })
  const wrapper = mount(PlayerTimelinePanel, {
    props: { playerId: 'p1' },
    global: { plugins: [pinia] },
  })
  const store = useDevelopmentStore()
  return { wrapper, store }
}

describe('PlayerTimelinePanel — Load more (deferred-153 AC4)', () => {
  it('shows the Load more button when hasMore is true', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1', eventType: 'SESSION_COMPLETED', occurredAt: new Date().toISOString() }],
      hasMore: true,
    })

    expect(wrapper.text()).toContain('development.timeline.loadMore')
  })

  it('hides the Load more button when hasMore is false', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1', eventType: 'SESSION_COMPLETED', occurredAt: new Date().toISOString() }],
      hasMore: false,
    })

    expect(wrapper.text()).not.toContain('development.timeline.loadMore')
  })

  it('clicking Load more calls fetchTimeline with playerId and the next page', async () => {
    const { wrapper, store } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1', eventType: 'SESSION_COMPLETED', occurredAt: new Date().toISOString() }],
      hasMore: true,
    })
    store.timelinePage = 0

    await wrapper.find('button').trigger('click')

    expect(store.fetchTimeline).toHaveBeenCalledWith('p1', 1)
  })

  // skillars-deferred-153 code review: the load-more failure path. Before the fix a page > 0
  // failure set store.timelineError, whose banner is the FIRST branch of this template's chain
  // and is guarded only on timelineLoading (false during a load-more by design) — so the whole
  // timeline and its retry button were replaced by a banner the user could no longer dismiss.
  it('a load-more failure keeps the event list rendered and shows the inline error + retry', async () => {
    const { wrapper, store } = mountPanel(
      {
        accessExpired: false,
        accessExpiryDays: null,
        events: [
          { id: 'e1', eventType: 'SESSION_COMPLETED', occurredAt: new Date().toISOString() },
        ],
        hasMore: true,
      },
      { timelineLoadMoreError: 'boom' },
    )

    // The list and the load-more affordance both survive the failure. ($te is falsy under the
    // testing stub, so the entry title falls through to the raw eventType — see the template.)
    expect(wrapper.text()).toContain('SESSION_COMPLETED')
    expect(wrapper.text()).toContain('development.timeline.loadMore')
    // ...and the failure itself is inline, with a retry label beside it.
    expect(wrapper.text()).toContain('boom')
    expect(wrapper.text()).toContain('development.timeline.loadMoreRetry')

    // Retrying simply re-runs the same load-more action.
    const retryBtn = wrapper
      .findAll('button')
      .find((b) => b.text().includes('development.timeline.loadMoreRetry'))
    await retryBtn.trigger('click')

    expect(store.fetchTimeline).toHaveBeenCalledWith('p1', 1)
  })

  it('a page-0 error still replaces the panel wholesale (unchanged behavior)', () => {
    const { wrapper } = mountPanel(null, { timelineError: 'page-0 boom' })

    expect(wrapper.text()).toContain('page-0 boom')
    expect(wrapper.text()).not.toContain('development.timeline.loadMore')
  })
})

describe('PlayerTimelinePanel — session recap (skillars-12-5 AC8)', () => {
  it('renders both labels and both text values when both recap fields are present', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [
        {
          id: 'e1',
          eventType: 'SESSION_COMPLETED',
          occurredAt: new Date().toISOString(),
          metadata: { coachId: 'c1', whatWeDid: 'Passing drills', whatsNext: 'Shooting drills' },
        },
      ],
      hasMore: false,
    })

    expect(wrapper.text()).toContain('development.timeline.whatWeDidLabel')
    expect(wrapper.text()).toContain('Passing drills')
    expect(wrapper.text()).toContain('development.timeline.whatsNextLabel')
    expect(wrapper.text()).toContain('Shooting drills')
  })

  // skillars-12-7 AC11: the third optional recap field renders inside the same guarded block.
  it('renders the what-went-well label and value when present, and not when absent or blank', () => {
    const mk = (metadata) =>
      mountPanel({
        accessExpired: false,
        accessExpiryDays: null,
        events: [
          {
            id: 'e1',
            eventType: 'SESSION_COMPLETED',
            occurredAt: new Date().toISOString(),
            metadata,
          },
        ],
        hasMore: false,
      }).wrapper

    const present = mk({ coachId: 'c1', whatWeDid: 'Passing', whatWentWell: 'Great tempo' })
    expect(present.text()).toContain('development.timeline.whatWentWellLabel')
    expect(present.text()).toContain('Great tempo')

    const absent = mk({ coachId: 'c1', whatWeDid: 'Passing' })
    expect(absent.text()).not.toContain('development.timeline.whatWentWellLabel')

    const blankOnly = mk({ coachId: 'c1', whatWentWell: '   ' })
    expect(blankOnly.find('dl').exists()).toBe(false)
  })

  it('renders only the whatWeDid label and value when whatsNext is absent', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [
        {
          id: 'e1',
          eventType: 'SESSION_COMPLETED',
          occurredAt: new Date().toISOString(),
          metadata: { coachId: 'c1', whatWeDid: 'Passing drills' },
        },
      ],
      hasMore: false,
    })

    expect(wrapper.text()).toContain('development.timeline.whatWeDidLabel')
    expect(wrapper.text()).toContain('Passing drills')
    expect(wrapper.text()).not.toContain('development.timeline.whatsNextLabel')
  })

  // skillars-deferred-155 AC12: a whitespace-only value must hide the block entirely — all three
  // guards (outer wrapper + two inner) test blankness via ?.trim(), not plain truthiness.
  it('renders neither label, and no empty wrapper, when both recap fields are whitespace-only', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [
        {
          id: 'e1',
          eventType: 'SESSION_COMPLETED',
          occurredAt: new Date().toISOString(),
          metadata: { coachId: 'c1', whatWeDid: '   ', whatsNext: '\n' },
        },
      ],
      hasMore: false,
    })

    expect(wrapper.text()).not.toContain('development.timeline.whatWeDidLabel')
    expect(wrapper.text()).not.toContain('development.timeline.whatsNextLabel')
    expect(wrapper.find('dl').exists()).toBe(false)
  })

  it('renders neither label for a redacted event (metadata has coachId only)', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [
        {
          id: 'e1',
          eventType: 'SESSION_COMPLETED',
          occurredAt: new Date().toISOString(),
          metadata: { coachId: 'c1' },
        },
      ],
      hasMore: false,
    })

    expect(wrapper.text()).not.toContain('development.timeline.whatWeDidLabel')
    expect(wrapper.text()).not.toContain('development.timeline.whatsNextLabel')
  })

  it('does not crash and renders neither label when metadata is absent entirely (legacy row)', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [{ id: 'e1', eventType: 'SESSION_COMPLETED', occurredAt: new Date().toISOString() }],
      hasMore: false,
    })

    expect(wrapper.text()).not.toContain('development.timeline.whatWeDidLabel')
    expect(wrapper.text()).not.toContain('development.timeline.whatsNextLabel')
  })

  it('renders neither recap label and leaks no array content for a RADAR_ASSESSMENT event', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [
        {
          id: 'e1',
          eventType: 'RADAR_ASSESSMENT',
          occurredAt: new Date().toISOString(),
          metadata: { skillCodes: ['PASSING'] },
        },
      ],
      hasMore: false,
    })

    expect(wrapper.text()).not.toContain('development.timeline.whatWeDidLabel')
    expect(wrapper.text()).not.toContain('development.timeline.whatsNextLabel')
    expect(wrapper.text()).not.toContain('PASSING')
  })

  it('renders neither label for a non-SESSION_COMPLETED event even when recap-shaped metadata is present', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [
        {
          id: 'e1',
          eventType: 'RADAR_ASSESSMENT',
          occurredAt: new Date().toISOString(),
          metadata: { whatWeDid: 'x' },
        },
      ],
      hasMore: false,
    })

    expect(wrapper.text()).not.toContain('development.timeline.whatWeDidLabel')
    expect(wrapper.text()).not.toContain('development.timeline.whatsNextLabel')
  })

  it('renders the recap label exactly once in a mixed list of a recap and a no-recap entry', () => {
    const { wrapper } = mountPanel({
      accessExpired: false,
      accessExpiryDays: null,
      events: [
        {
          id: 'e1',
          eventType: 'SESSION_COMPLETED',
          occurredAt: new Date().toISOString(),
          metadata: { coachId: 'c1', whatWeDid: 'Passing drills', whatsNext: 'Shooting drills' },
        },
        {
          id: 'e2',
          eventType: 'SESSION_COMPLETED',
          occurredAt: new Date().toISOString(),
          metadata: { coachId: 'c2' },
        },
      ],
      hasMore: false,
    })

    const text = wrapper.text()
    expect(text.split('development.timeline.whatWeDidLabel').length - 1).toBe(1)
    expect(text.split('development.timeline.whatsNextLabel').length - 1).toBe(1)
    expect(text.split('Passing drills').length - 1).toBe(1)
    expect(text.split('Shooting drills').length - 1).toBe(1)
  })
})
