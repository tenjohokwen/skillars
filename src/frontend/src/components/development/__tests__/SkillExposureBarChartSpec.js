// Found by Pitchside DEV-04: the neglected-skill alert lived only inside the canvas chart, and a
// neglected skill with no exposure this week showed as a neutral grey chip.

import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('vue-echarts', () => ({ default: { name: 'VChart', template: '<div class="v-chart" />' } }))
vi.mock('echarts/core', () => ({ use: vi.fn() }))
vi.mock('echarts/charts', () => ({ BarChart: {} }))
vi.mock('echarts/components', () => ({ GridComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

import SkillExposureBarChart from 'src/components/development/SkillExposureBarChart.vue'

const DEFS = [{ code: 'PAC' }, { code: 'SHO' }, { code: 'PAS' }]

function mountChart(props) {
  return mount(SkillExposureBarChart, { props: { skillDefinitions: DEFS, ...props } })
}

describe('SkillExposureBarChart.vue: neglected skills as text', () => {
  it('lists every neglected skill, exposed this week or not', () => {
    const wrapper = mountChart({ currentWeek: { PAC: 40 }, neglectedCodes: ['PAC', 'SHO'] })
    const items = wrapper.findAll('[data-testid="exposure-neglected-skill"]')
    expect(items.map((i) => i.text())).toEqual([
      'development.neglectedSkillAlert',
      'development.neglectedSkillAlert',
    ])
  })

  it('a neglected skill with no exposure gets the alert chip; others stay neutral', () => {
    const wrapper = mountChart({ currentWeek: { PAC: 40 }, neglectedCodes: ['SHO'] })
    const chip = (code) => wrapper.find(`[data-testid="exposure-zero-skill"][data-skill="${code}"]`)
    expect(chip('SHO').attributes('data-neglected')).toBe('true')
    expect(chip('SHO').text()).toContain('development.neglectedSkillAlert')
    expect(chip('PAS').attributes('data-neglected')).toBe('false')
    expect(chip('PAS').text()).toContain('development.noExposureYet')
  })

  it('renders no list when nothing is neglected', () => {
    const wrapper = mountChart({ currentWeek: { PAC: 40 }, neglectedCodes: [] })
    expect(wrapper.find('[data-testid="exposure-neglected"]').exists()).toBe(false)
  })
})
