// skillars-deferred-163 AC4 -- first-ever coverage for CoachRadarCard.vue. Scoped to this story's
// own fixes: the pagehide flush must survive page teardown via fetch keepalive (not axios, not
// navigator.sendBeacon -- the endpoint is PUT-only), and rapid selections must reach the network in
// send order instead of racing (a response-side guard cannot fix this; saveRadarPreferences writes
// no local state on success, so the defect is in send order, not response-handling order).

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/development.api', () => ({
  putRadarPreferencesKeepAlive: vi.fn().mockResolvedValue(undefined),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { putRadarPreferencesKeepAlive } from 'src/api/development.api'
import CoachRadarCard from 'src/components/development/CoachRadarCard.vue'

function storeOf(wrapper) {
  return wrapper.vm.store ?? wrapper.vm.$.setupState.store
}

const mounted = []
function mountCard(playerId = '1') {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const wrapper = mount(CoachRadarCard, {
    props: { playerId },
    global: {
      plugins: [pinia],
      stubs: { SkillsRadarChart: true },
    },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('CoachRadarCard.vue (skillars-deferred-163 AC4)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  it('flushes the pending save via fetch keepalive on pagehide, not the normal store save', async () => {
    const wrapper = mountCard()
    const store = storeOf(wrapper)
    await flushPromises()

    wrapper.vm.$.setupState.onSkillSelectionChange(['passing'])
    window.dispatchEvent(new Event('pagehide'))

    expect(putRadarPreferencesKeepAlive).toHaveBeenCalledWith('1', ['passing'])
    expect(store.saveRadarPreferences).not.toHaveBeenCalled()

    // The debounce timer was cleared by the flush -- it must not ALSO fire the normal save later.
    vi.advanceTimersByTime(300)
    expect(store.saveRadarPreferences).not.toHaveBeenCalled()
  })

  it('serialises two selections in separate debounce windows so the second save waits for the first', async () => {
    const wrapper = mountCard()
    const store = storeOf(wrapper)
    await flushPromises()

    let resolveFirst
    store.saveRadarPreferences = vi.fn(() => {
      if (store.saveRadarPreferences.mock.calls.length === 1) {
        return new Promise((resolve) => {
          resolveFirst = resolve
        })
      }
      return Promise.resolve()
    })

    wrapper.vm.$.setupState.onSkillSelectionChange(['a'])
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(store.saveRadarPreferences).toHaveBeenCalledTimes(1)
    expect(store.saveRadarPreferences).toHaveBeenNthCalledWith(1, '1', ['a'])

    // A second selection, a new debounce window later -- the first PUT is still unresolved.
    wrapper.vm.$.setupState.onSkillSelectionChange(['b'])
    vi.advanceTimersByTime(300)
    await flushPromises()
    // Must not have issued the second save yet: it is chained behind the first's pending promise.
    expect(store.saveRadarPreferences).toHaveBeenCalledTimes(1)

    resolveFirst()
    await flushPromises()
    expect(store.saveRadarPreferences).toHaveBeenCalledTimes(2)
    expect(store.saveRadarPreferences).toHaveBeenNthCalledWith(2, '1', ['b'])
  })

  it('notifies on a save failure without breaking the chain for the next save', async () => {
    const wrapper = mountCard()
    const store = storeOf(wrapper)
    await flushPromises()

    store.saveRadarPreferences = vi
      .fn()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce(undefined)

    wrapper.vm.$.setupState.onSkillSelectionChange(['a'])
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'negative' }))

    wrapper.vm.$.setupState.onSkillSelectionChange(['b'])
    vi.advanceTimersByTime(300)
    await flushPromises()
    expect(store.saveRadarPreferences).toHaveBeenCalledTimes(2)
  })
})
