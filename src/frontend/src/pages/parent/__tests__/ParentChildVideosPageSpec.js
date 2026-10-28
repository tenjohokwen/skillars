// UI-gap chunk C7 / D3 — a parent views, plays and deletes a linked child's videos.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/video.api', () => ({
  videoApi: { getPlayerVideos: vi.fn(), getPlayerCoachVideos: vi.fn(), deleteVideo: vi.fn() },
}))

const notifySpy = vi.fn()
let dialogResult = true
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    useQuasar: () => ({
      notify: notifySpy,
      dialog: () => {
        const chain = {
          onOk(fn) {
            if (dialogResult) fn()
            return chain
          },
          onCancel(fn) {
            if (!dialogResult) fn()
            return chain
          },
        }
        return chain
      },
    }),
  }
})

import ParentChildVideosPage from 'src/pages/parent/ParentChildVideosPage.vue'
import { videoApi } from 'src/api/video.api'

// A real backend Tsid shape — Number(...) would corrupt it.
const LARGE_PLAYER_ID = '893573203704173564'
const mounted = []

async function mountPage(playerId = LARGE_PLAYER_ID) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/parent/players/:playerId/videos', component: ParentChildVideosPage },
      { path: '/parent/players/:playerId/sessions', component: { template: '<div />' } },
    ],
  })
  router.push(`/parent/players/${playerId}/videos`)
  await router.isReady()
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { role: 'PARENT' },
      player: { players: [{ id: LARGE_PLAYER_ID, name: 'Mia' }] },
    },
  })
  const wrapper = mount(ParentChildVideosPage, {
    global: {
      plugins: [router, pinia],
      stubs: { VideoStatusCard: true, VideoPlayDialog: true },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

beforeEach(() => {
  videoApi.getPlayerCoachVideos.mockResolvedValue([])
})

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
  dialogResult = true
})

describe('ParentChildVideosPage.vue', () => {
  it("lists the child's videos for the exact route-param id, with play but no retry", async () => {
    videoApi.getPlayerVideos.mockResolvedValue([
      { id: 'v1', operationalState: 'READY' },
      { id: 'v2', operationalState: 'FAILED' },
    ])
    const { wrapper } = await mountPage()

    expect(videoApi.getPlayerVideos).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    expect(wrapper.find('[data-testid="child-name"]').text()).toBe('Mia')
    expect(wrapper.findAll('[data-testid="video-list-item"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-testid="video-play-btn"]')).toHaveLength(1)
    expect(wrapper.find('[data-testid="video-retry-btn"]').exists()).toBe(false)
    // Live status (SSE / polling) is owner-only server-side: a parent's cards must not open it.
    const cards = wrapper.findAllComponents({ name: 'VideoStatusCard' })
    expect(cards).toHaveLength(2)
    for (const card of cards) {
      expect(card.props('live')).toBe(false)
    }
  })

  it('shows the empty state for a child with no videos', async () => {
    videoApi.getPlayerVideos.mockResolvedValue([])
    const { wrapper } = await mountPage()
    expect(wrapper.find('[data-testid="child-videos-empty"]').exists()).toBe(true)
  })

  it('shows an error (not the empty state) when the list fails', async () => {
    videoApi.getPlayerVideos.mockRejectedValue({ response: { status: 403 } })
    const { wrapper } = await mountPage()
    expect(wrapper.find('[data-testid="child-videos-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="child-videos-empty"]').exists()).toBe(false)
  })

  it('deletes a video after confirmation and drops it from the list', async () => {
    videoApi.getPlayerVideos.mockResolvedValue([{ id: 'v1', operationalState: 'READY' }])
    videoApi.deleteVideo.mockResolvedValue(undefined)
    const { wrapper } = await mountPage()

    await wrapper.find('[data-testid="video-delete-btn"]').trigger('click')
    await flushPromises()

    expect(videoApi.deleteVideo).toHaveBeenCalledWith('v1')
    expect(wrapper.find('[data-testid="video-list-item"]').exists()).toBe(false)
  })

  it('does not delete when the confirmation is cancelled', async () => {
    dialogResult = false
    videoApi.getPlayerVideos.mockResolvedValue([{ id: 'v1', operationalState: 'READY' }])
    const { wrapper } = await mountPage()

    await wrapper.find('[data-testid="video-delete-btn"]').trigger('click')
    await flushPromises()

    expect(videoApi.deleteVideo).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="video-list-item"]').exists()).toBe(true)
  })

  // V184: coach videos ABOUT the child — listed for the parent, play only (the coach owns them).
  it("lists the coach's session videos about the child, playable but never deletable", async () => {
    videoApi.getPlayerVideos.mockResolvedValue([])
    videoApi.getPlayerCoachVideos.mockResolvedValue([
      { id: 'c1', operationalState: 'READY', videoType: 'COACH_REVIEW' },
    ])
    const { wrapper } = await mountPage()

    expect(videoApi.getPlayerCoachVideos).toHaveBeenCalledWith(LARGE_PLAYER_ID)
    const section = wrapper.find('[data-testid="child-coach-videos-section"]')
    expect(section.findAll('[data-testid="video-list-item"]')).toHaveLength(1)
    expect(section.find('[data-testid="video-play-btn"]').exists()).toBe(true)
    expect(section.find('[data-testid="video-delete-btn"]').exists()).toBe(false)
  })

  it('shows the coach-videos error independently of the own-videos list', async () => {
    videoApi.getPlayerVideos.mockResolvedValue([{ id: 'v1', operationalState: 'READY' }])
    videoApi.getPlayerCoachVideos.mockRejectedValue({ response: { status: 500 } })
    const { wrapper } = await mountPage()

    expect(wrapper.find('[data-testid="child-coach-videos-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="child-videos-error"]').exists()).toBe(false)
  })
})
