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
