// Found by Pitchside suite J: a failed homework load (a 403 included) showed "No homework yet".

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRoute: () => ({ params: { playerId: '42' } }),
}))

import PlayerLockerRoomPage from 'src/pages/player/PlayerLockerRoomPage.vue'

const mounted = []
function mountPage(homework) {
  const wrapper = mount(PlayerLockerRoomPage, {
    global: {
      plugins: [
        createTestingPinia({
          createSpy: vi.fn,
          initialState: { homework: { assignments: [], loading: false, error: null, ...homework } },
        }),
      ],
      stubs: { DrillCard: true },
    },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('PlayerLockerRoomPage.vue: load failure vs no homework', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('a failed load shows an error, not the empty state', async () => {
    const wrapper = mountPage({ error: new Error('403') })
    await flushPromises()
    expect(wrapper.find('[data-testid="locker-room-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="locker-room-empty"]').exists()).toBe(false)
  })

  it('no assignments and no error is the empty state', async () => {
    const wrapper = mountPage({})
    await flushPromises()
    expect(wrapper.find('[data-testid="locker-room-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="locker-room-error"]').exists()).toBe(false)
  })
})
