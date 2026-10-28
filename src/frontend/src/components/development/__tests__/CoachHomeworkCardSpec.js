// UI-gap chunk C7 / D2 — the coach sees the homework they assigned a player and its completion.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/homework.api', () => ({
  homeworkApi: { getCoachPlayerHomework: vi.fn() },
}))

import CoachHomeworkCard from '../CoachHomeworkCard.vue'
import { homeworkApi } from 'src/api/homework.api'

const PLAYER_ID = '893573203704173564'
const mounted = []

async function mountCard(playerId = PLAYER_ID) {
  const wrapper = mount(CoachHomeworkCard, { props: { playerId } })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('CoachHomeworkCard.vue', () => {
  it('lists each assignment with its open / completed state for the exact player id', async () => {
    homeworkApi.getCoachPlayerHomework.mockResolvedValue([
      {
        assignmentId: 'a1',
        drillName: 'Rondo',
        assignedAt: '2026-10-01T10:00:00Z',
        completed: true,
        completedAt: '2026-10-03T10:00:00Z',
      },
      {
        assignmentId: 'a2',
        drillName: null,
        assignedAt: '2026-10-05T10:00:00Z',
        completed: false,
        completedAt: null,
      },
    ])

    const wrapper = await mountCard()

    expect(homeworkApi.getCoachPlayerHomework).toHaveBeenCalledWith(PLAYER_ID)
    const rows = wrapper.findAll('[data-testid="coach-homework-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Rondo')
    // The global $t test mock renders any {date}-param key as "Last assessed: <date>", so assert
    // the completed state through its marker and date rather than the key.
    expect(rows[0].text()).toContain('task_alt')
    expect(rows[0].find('.text-positive').exists()).toBe(true)
    expect(rows[0].text()).not.toContain('coach.playerDetailHomeworkOpen')
    expect(rows[1].text()).toContain('coach.playerDetailHomeworkUnknownDrill')
    expect(rows[1].text()).toContain('coach.playerDetailHomeworkOpen')
  })

  it('shows the empty state when the coach assigned nothing', async () => {
    homeworkApi.getCoachPlayerHomework.mockResolvedValue([])
    const wrapper = await mountCard()
    expect(wrapper.find('[data-testid="coach-homework-empty"]').exists()).toBe(true)
  })

  it('shows its own error state (not an empty list) when the fetch fails, e.g. a lapsed window', async () => {
    homeworkApi.getCoachPlayerHomework.mockRejectedValue({ response: { status: 403 } })
    const wrapper = await mountCard()
    expect(wrapper.find('[data-testid="coach-homework-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="coach-homework-empty"]').exists()).toBe(false)
  })
})
