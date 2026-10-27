// skillars-deferred-165 AC4: SkillTrendsPanel.vue — per-skill direction/slope rendering.

import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillTrendsPanel from 'src/components/development/SkillTrendsPanel.vue'

const mounted = []
function mountPanel(props = {}) {
  const wrapper = mount(SkillTrendsPanel, { props })
  mounted.push(wrapper)
  return wrapper
}

describe('SkillTrendsPanel.vue (skillars-deferred-165 AC4)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('renders the empty state when there are no trends', () => {
    const wrapper = mountPanel({ trends: [] })

    expect(wrapper.find('[data-testid="skill-trends-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="skill-trends-list"]').exists()).toBe(false)
  })

  it('renders one row per trend, using the skill display name when available', () => {
    const wrapper = mountPanel({
      trends: [
        { skillCode: 'PAC', direction: 'IMPROVING', slopePerWeek: 1.25, weeksObserved: 6 },
        { skillCode: 'SHO', direction: 'DECLINING', slopePerWeek: -0.5, weeksObserved: 4 },
      ],
      skillDefinitions: [{ code: 'PAC', displayName: 'Pace' }],
    })

    const rows = wrapper.findAll('[data-testid="skill-trend-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Pace')
    // No matching skill definition for SHO -> falls back to the raw code.
    expect(rows[1].text()).toContain('SHO')
  })

  it('falls back to a neutral icon/color for an unrecognized direction', () => {
    const wrapper = mountPanel({
      trends: [{ skillCode: 'DRI', direction: 'SOMETHING_NEW', slopePerWeek: 0, weeksObserved: 1 }],
    })

    expect(wrapper.vm.directionIcon('SOMETHING_NEW')).toBe('help_outline')
    expect(wrapper.vm.directionColor('SOMETHING_NEW')).toBe('grey-6')
  })

  // Code review 2026-10-27.
  it('renders the error state, not the empty state, when the fetch failed', () => {
    const wrapper = mountPanel({ trends: [], error: true })

    expect(wrapper.find('[data-testid="skill-trends-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="skill-trends-empty"]').exists()).toBe(false)
  })

  it('shows no slope caption for INSUFFICIENT_DATA or an unknown direction, and never a raw key', () => {
    const wrapper = mountPanel({
      trends: [
        { skillCode: 'PAC', direction: 'INSUFFICIENT_DATA', slopePerWeek: 0, weeksObserved: 1 },
        { skillCode: 'DRI', direction: 'SOMETHING_NEW', slopePerWeek: 0, weeksObserved: 1 },
        { skillCode: 'SHO', direction: 'FLAT', slopePerWeek: 0, weeksObserved: 5 },
      ],
    })

    const rows = wrapper.findAll('[data-testid="skill-trend-row"]')
    expect(rows[0].text()).not.toContain('slopeCaption')
    expect(rows[0].text()).not.toMatch(/SLU\/week/)
    expect(rows[1].text()).not.toContain('SOMETHING_NEW')
    expect(rows[1].text()).not.toMatch(/SLU\/week/)
    expect(rows[2].text()).toMatch(/SLU\/week|slopeCaption/)
  })

  it('formats the slope to two decimal places', () => {
    const wrapper = mountPanel({ trends: [] })

    expect(wrapper.vm.formatSlope(1.2)).toBe('1.20')
    expect(wrapper.vm.formatSlope(null)).toBe('0.00')
  })
})
