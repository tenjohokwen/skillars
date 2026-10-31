// S-20: HealthDashboardPage.vue surfaces WARNING components (the Gemini developer-API compliance
// notice) as a banner while the overall status stays UP.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({ adminApi: { getHealth: vi.fn() } }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: vi.fn() }) }
})

import { adminApi } from 'src/api/admin.api'
import HealthDashboardPage from 'src/pages/admin/HealthDashboardPage.vue'

const mounted = []
async function mountPage(health) {
  adminApi.getHealth.mockResolvedValue(health)
  const wrapper = mount(HealthDashboardPage)
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  mounted.splice(0).forEach((w) => w.unmount())
  vi.clearAllMocks()
})

describe('HealthDashboardPage warnings', () => {
  it('shows a banner for a WARNING component', async () => {
    const wrapper = await mountPage({
      status: 'UP',
      components: {
        db: { status: 'UP' },
        geminiCompliance: {
          status: 'WARNING',
          details: { mode: 'developer', warning: 'raw backend text' },
        },
      },
    })
    expect(wrapper.findAll('[data-test="health-warning"]')).toHaveLength(1)
    expect(wrapper.find('.status-pill--warning').exists()).toBe(true)
  })

  it('shows no banner when every component is UP', async () => {
    const wrapper = await mountPage({
      status: 'UP',
      components: { geminiCompliance: { status: 'UP', details: { mode: 'vertex' } } },
    })
    expect(wrapper.find('[data-test="health-warning"]').exists()).toBe(false)
  })
})
