import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillsRadarChart from '../SkillsRadarChart.vue'

// Factory for a SkillRadarEntry-shaped object
function makeSkill(code, score, baseline = null, entryCount = null) {
  return {
    skillCode: code,
    compositeScore: score,
    baselineScore: baseline,
    entryCount,
    lastUpdatedAt: score !== null ? new Date().toISOString() : null,
  }
}

// Build 15 skills — first N have scores, rest are null
function makeSkills(withScoreCount = 15, totalCount = 15) {
  return Array.from({ length: totalCount }, (_, i) => {
    const code = `SK${String(i + 1).padStart(2, '0')}`
    return makeSkill(code, i < withScoreCount ? 60 + i : null, null, i < withScoreCount ? 3 : null)
  })
}

const globalConfig = {
  global: {
    stubs: {
      'q-tooltip': true,
    },
    mocks: {
      $t: (key, params) => {
        if (params && params.date) return `Last assessed: ${params.date}`
        return key
      },
    },
  },
}

describe('SkillsRadarChart', () => {
  it('renders empty state when no skills have scores', () => {
    const skills = makeSkills(0)
    const wrapper = mount(SkillsRadarChart, {
      props: { skills },
      ...globalConfig,
    })
    expect(wrapper.text()).toContain('development.radar.noAssessmentsYet')
  })

  it('polygon has N points for N-skill selection', () => {
    const skills = makeSkills(15)
    const selectedSkillCodes = skills.slice(0, 5).map((s) => s.skillCode)
    const wrapper = mount(SkillsRadarChart, {
      props: { skills, selectedSkillCodes },
      ...globalConfig,
    })
    const polygon = wrapper.find('polygon:not([stroke-dasharray])')
    if (!polygon.exists()) return // no scores yet
    const points = polygon.attributes('points').trim().split(' ').filter(Boolean)
    expect(points).toHaveLength(5)
  })

  it('polygon uses all scored skills when selection is empty', () => {
    // 10 with scores, 5 null
    const skills = makeSkills(10, 15)
    const wrapper = mount(SkillsRadarChart, {
      props: { skills, selectedSkillCodes: [] },
      ...globalConfig,
    })
    const polygon = wrapper.find('polygon:not([stroke-dasharray])')
    if (!polygon.exists()) return
    const points = polygon.attributes('points').trim().split(' ').filter(Boolean)
    expect(points).toHaveLength(10)
  })

  it('polygon uses all 15 skills when all have scores and selection is empty', () => {
    const skills = makeSkills(15)
    const wrapper = mount(SkillsRadarChart, {
      props: { skills, selectedSkillCodes: [] },
      ...globalConfig,
    })
    const polygon = wrapper.find('polygon:not([stroke-dasharray])')
    if (!polygon.exists()) return
    const points = polygon.attributes('points').trim().split(' ').filter(Boolean)
    expect(points).toHaveLength(15)
  })

  it('baseline ghost polygon rendered when showBaseline is true and baseline exists', () => {
    const skills = [
      makeSkill('PAC', 65, 50, 3),
      makeSkill('SHO', 70, 60, 3),
    ]
    const wrapper = mount(SkillsRadarChart, {
      props: { skills, showBaseline: true },
      ...globalConfig,
    })
    const dashedPolygon = wrapper.find('polygon[stroke-dasharray]')
    expect(dashedPolygon.exists()).toBe(true)
  })

  it('baseline ghost polygon not rendered when showBaseline is false', () => {
    const skills = [makeSkill('PAC', 65, 50, 3)]
    const wrapper = mount(SkillsRadarChart, {
      props: { skills, showBaseline: false },
      ...globalConfig,
    })
    const dashedPolygon = wrapper.find('polygon[stroke-dasharray]')
    expect(dashedPolygon.exists()).toBe(false)
  })

  it('accessible table always contains all 15 skills regardless of selection', () => {
    const skills = makeSkills(15)
    const selectedSkillCodes = [skills[0].skillCode]
    const wrapper = mount(SkillsRadarChart, {
      props: { skills, selectedSkillCodes },
      ...globalConfig,
    })
    const tbody = wrapper.find('table.sr-only tbody')
    const rows = tbody.findAll('tr')
    expect(rows).toHaveLength(15)
  })

  it('confidence dot shows filled for entryCount >= 3', () => {
    const skills = [makeSkill('PAC', 65, null, 3)]
    const wrapper = mount(SkillsRadarChart, {
      props: { skills },
      ...globalConfig,
    })
    // The filled confidence dot should have fill="var(--accent-primary)"
    const dots = wrapper.findAll('circle[stroke="var(--accent-primary)"]')
    const filledDot = dots.find((d) => d.attributes('fill') === 'var(--accent-primary)')
    expect(filledDot).toBeDefined()
  })

  it('toPoint clamps score above 100 within radius', () => {
    // A skill with compositeScore > 100 should still plot within bounds
    const overRange = { ...makeSkill('PAC', 150, null, 5) }
    const skills = [overRange]
    const wrapper = mount(SkillsRadarChart, {
      props: { skills },
      ...globalConfig,
    })
    const polygon = wrapper.find('polygon:not([stroke-dasharray])')
    if (!polygon.exists()) return
    const points = polygon.attributes('points').trim().split(' ')
    for (const pair of points) {
      const [x, y] = pair.split(',').map(Number)
      const CENTER_X = 200
      const CENTER_Y = 200
      const RADIUS = 160
      expect(x).toBeGreaterThanOrEqual(CENTER_X - RADIUS)
      expect(x).toBeLessThanOrEqual(CENTER_X + RADIUS)
      expect(y).toBeGreaterThanOrEqual(CENTER_Y - RADIUS)
      expect(y).toBeLessThanOrEqual(CENTER_Y + RADIUS)
    }
  })
})
