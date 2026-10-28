// skillars-12-9 AC10(a): the "no reports" empty state keyed off the legacy shared store.error, so
// an unrelated failed fetch elsewhere on the page hid it. It must key off reportsError.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDevelopmentStore } from 'src/stores/development.store'
import PerformanceReportsPanel from '../PerformanceReportsPanel.vue'

const mounted = []
function mountPanel(storeState = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const store = useDevelopmentStore()
  Object.assign(store, storeState)
  const wrapper = mount(PerformanceReportsPanel, {
    props: { playerId: '7', isCoach: true },
    global: { plugins: [pinia], stubs: { GenerateReportDialog: true } },
  })
  mounted.push(wrapper)
  return { wrapper, store }
}

describe('PerformanceReportsPanel.vue (skillars-12-9)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('still shows "no reports" when an unrelated store.error is set', () => {
    const { wrapper } = mountPanel({ error: 'some other section failed', reports: [] })
    expect(wrapper.text()).toContain('development.report.noReports')
  })

  it('hides "no reports" when the report list itself failed', () => {
    const { wrapper } = mountPanel({ reportsError: 'list failed', reports: [] })
    expect(wrapper.text()).not.toContain('development.report.noReports')
    expect(wrapper.text()).toContain('list failed')
  })
})

// UI-gap chunk C7 / A18 (UX-DR22): a known SCOUT coach gets the soft teaser for report generation
// instead of the action simply vanishing; an unknown tier is not an upsell moment.
describe('PerformanceReportsPanel.vue — FeatureGateOverlay (A18)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  function mountWithTier(coachTier, isCoach = true) {
    const pinia = createTestingPinia({
      createSpy: vi.fn,
      initialState: { auth: { role: 'COACH', coachTier } },
    })
    const wrapper = mount(PerformanceReportsPanel, {
      props: { playerId: '7', isCoach },
      global: { plugins: [pinia], stubs: { GenerateReportDialog: true } },
    })
    mounted.push(wrapper)
    return wrapper
  }

  it('shows the upgrade teaser, linking to the coach subscription page, for a SCOUT coach', () => {
    const wrapper = mountWithTier('SCOUT')
    const gate = wrapper.findComponent({ name: 'FeatureGateOverlay' })
    expect(gate.exists()).toBe(true)
    expect(gate.props('upgradeTo')).toBe('/coach/subscription')
  })

  it('shows no teaser for a paid tier, an unknown tier, or a non-coach viewer', () => {
    expect(mountWithTier('INSTRUCTOR').findComponent({ name: 'FeatureGateOverlay' }).exists()).toBe(
      false,
    )
    expect(mountWithTier(null).findComponent({ name: 'FeatureGateOverlay' }).exists()).toBe(false)
    expect(
      mountWithTier('SCOUT', false).findComponent({ name: 'FeatureGateOverlay' }).exists(),
    ).toBe(false)
  })
})
