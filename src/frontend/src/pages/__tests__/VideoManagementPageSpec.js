// skillars-deferred-142 AC4 — VideoManagementPage.vue's fetchVideos() 403 handler redirected to
// '/dashboard', a route gated admin-only since skillars-deferred-141 (MainLayout.vue's generic
// "Dashboard" nav link is `v-if="authStore.isAdmin"`). PLAYER is the only role that can even reach
// this page (routes.js: `player/videos`, `meta: { role: 'PLAYER' }`), so a bounced PLAYER landed on
// a dead end with no menu entry for their role. Fixed to redirect to '/player/dashboard' instead.
// No spec existed for this page before this story.
//
// Mutation check (run both ways, see Dev Agent Record): revert the redirect target back to
// '/dashboard' — this spec's one real assertion turns red.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/video.api', () => ({
  videoApi: {
    getMyVideos: vi.fn(),
    getMyQuota: vi.fn(),
  },
}))

// Notify is not registered by installQuasarPlugin — stub useQuasar so $q.notify is callable
// (see ParentBookingsPageSpec.js for the established precedent of this exact mock).
const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import VideoManagementPage from 'src/pages/VideoManagementPage.vue'
import { videoApi } from 'src/api/video.api'

const STUB = { template: '<div />' }
const mounted = []

async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/player/videos', component: VideoManagementPage },
      { path: '/dashboard', component: STUB },
      { path: '/player/dashboard', component: STUB },
    ],
  })
  router.push('/player/videos')
  await router.isReady()

  const wrapper = mount(VideoManagementPage, {
    global: {
      plugins: [router],
      stubs: { VideoStatusCard: true, QuotaUsageBar: true },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

describe('VideoManagementPage.vue — 403 redirect target (skillars-deferred-142 AC4)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('redirects to /player/dashboard, not /dashboard, on a 403 from getMyVideos()', async () => {
    videoApi.getMyVideos.mockRejectedValue({ response: { status: 403 } })
    videoApi.getMyQuota.mockResolvedValue({
      storageUsedBytes: 0,
      storageLimitBytes: 0,
      bandwidthUsedBytes: 0,
      bandwidthLimitBytes: 0,
    })

    const { router } = await mountPage()

    expect(router.currentRoute.value.path).toBe('/player/dashboard')
    // 'message' is the discriminating field: both catch-arms notify with type 'negative', so
    // asserting only the type would survive deleting the status check entirely.
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'video.management.accessDenied' }),
    )
  })
})
