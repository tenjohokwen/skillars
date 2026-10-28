// feat/admin-operations A8 / D4: AdminVideosPage.vue — the admin video review queue (locked /
// failed / blocked), single-video lookup, and the confirmed block / restore / reconcile / delete
// actions plus upload-session listing.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getVideoReviewQueue: vi.fn(),
    getAdminVideo: vi.fn(),
    setVideoAccessState: vi.fn(),
    deleteVideo: vi.fn(),
    getVideoUploadSessions: vi.fn(),
    reconcileVideo: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminVideosPage from 'src/pages/admin/AdminVideosPage.vue'

const VIDEO_ID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'

const LOCKED = {
  id: VIDEO_ID,
  title: 'clip.mp4',
  operationalState: 'LOCKED',
  accessState: 'ACTIVE',
  ownerId: 'owner-1',
  visibility: 'PRIVATE',
  videoType: 'HOMEWORK',
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-02T10:00:00Z',
  lifecycleLockedAt: null,
  archivedAt: null,
  flaggedLayers: ['VIDEOINTEL'],
}

const mounted = []
async function mountPage(query = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }],
  })
  router.push({ path: '/admin/videos', query })
  await router.isReady()
  const wrapper = mount(AdminVideosPage, {
    global: { plugins: [router], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminVideosPage.vue', () => {
  beforeEach(() => {
    adminApi.getVideoReviewQueue.mockResolvedValue({ content: [LOCKED], totalPages: 1 })
    adminApi.getAdminVideo.mockResolvedValue(LOCKED)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads the attention queue on mount and shows flagged layers', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getVideoReviewQueue).toHaveBeenCalledWith('ATTENTION', 0)
    expect(wrapper.text()).toContain('admin.videos.layers.VIDEOINTEL')
    expect(adminApi.getAdminVideo).not.toHaveBeenCalled()
  })

  it('changing the filter reloads from page 0', async () => {
    const wrapper = await mountPage()
    wrapper.vm.currentPage = 2
    wrapper.vm.filter = 'BLOCKED'

    await wrapper.vm.onFilterChange()

    expect(adminApi.getVideoReviewQueue).toHaveBeenLastCalledWith('BLOCKED', 0)
  })

  it('opens a video from a ?videoId= deep link', async () => {
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    expect(adminApi.getAdminVideo).toHaveBeenCalledWith(VIDEO_ID)
    expect(wrapper.find('[data-testid="video-detail-content"]').text()).toContain('clip.mp4')
  })

  it('opens a video by typed id only when it is a UUID', async () => {
    const wrapper = await mountPage()
    wrapper.vm.videoIdInput = 'nope'
    await wrapper.vm.openById()
    expect(adminApi.getAdminVideo).not.toHaveBeenCalled()

    wrapper.vm.videoIdInput = VIDEO_ID
    await wrapper.vm.openById()
    expect(adminApi.getAdminVideo).toHaveBeenCalledWith(VIDEO_ID)
  })

  it('offers block for an active video and restore for a blocked one', async () => {
    const wrapper = await mountPage({ videoId: VIDEO_ID })
    expect(wrapper.find('[data-testid="video-block-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="video-restore-btn"]').exists()).toBe(false)

    adminApi.getAdminVideo.mockResolvedValue({ ...LOCKED, accessState: 'BLOCKED' })
    await wrapper.vm.reloadDetail()
    await flushPromises()
    expect(wrapper.find('[data-testid="video-restore-btn"]').exists()).toBe(true)
  })

  it('offers no actions for a deleted video', async () => {
    adminApi.getAdminVideo.mockResolvedValue({ ...LOCKED, operationalState: 'DELETED' })
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    expect(wrapper.find('[data-testid="video-delete-btn"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('admin.videos.goneHint')
  })

  it('delete goes through the confirmation, then refreshes queue and detail', async () => {
    adminApi.deleteVideo.mockResolvedValue(undefined)
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    wrapper.vm.openAction('delete')
    await flushPromises()
    expect(wrapper.find('[data-testid="video-action-dialog"]').text()).toContain(
      'admin.videos.deleteHint',
    )
    expect(adminApi.deleteVideo).not.toHaveBeenCalled()

    await wrapper.vm.submitAction()

    expect(adminApi.deleteVideo).toHaveBeenCalledWith(VIDEO_ID)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'admin.videos.deleteSuccess', type: 'positive' }),
    )
    expect(adminApi.getVideoReviewQueue).toHaveBeenCalledTimes(2)
    expect(adminApi.getAdminVideo).toHaveBeenCalledTimes(2)
  })

  it('block and restore set the access state', async () => {
    adminApi.setVideoAccessState.mockResolvedValue({})
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    wrapper.vm.openAction('block')
    await wrapper.vm.submitAction()
    wrapper.vm.openAction('restore')
    await wrapper.vm.submitAction()

    expect(adminApi.setVideoAccessState).toHaveBeenNthCalledWith(1, VIDEO_ID, 'BLOCKED')
    expect(adminApi.setVideoAccessState).toHaveBeenNthCalledWith(2, VIDEO_ID, 'ACTIVE')
  })

  it('restore warns that a non-ready video stays unplayable', async () => {
    const wrapper = await mountPage({ videoId: VIDEO_ID })
    wrapper.vm.openAction('restore')

    expect(wrapper.vm.actionHint).toBe('admin.videos.restoreNotPlayableHint')
  })

  it('reconcile reports whether a correction happened', async () => {
    adminApi.reconcileVideo.mockResolvedValue({
      videoId: VIDEO_ID,
      operationalState: 'FAILED',
      accessState: 'ACTIVE',
      incident: { incidentType: 'MISSING_ASSET' },
    })
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    wrapper.vm.openAction('reconcile')
    await wrapper.vm.submitAction()

    expect(adminApi.reconcileVideo).toHaveBeenCalledWith(VIDEO_ID)
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'admin.videos.reconcileCorrected' }),
    )
  })

  it('maps provider and conflict failures', async () => {
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    adminApi.deleteVideo.mockRejectedValueOnce({ response: { status: 502 } })
    wrapper.vm.openAction('delete')
    await wrapper.vm.submitAction()
    expect(notifySpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ message: 'admin.videos.providerUnavailable', type: 'negative' }),
    )

    adminApi.setVideoAccessState.mockRejectedValueOnce({ response: { status: 409 } })
    wrapper.vm.openAction('block')
    await wrapper.vm.submitAction()
    expect(notifySpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ message: 'admin.videos.actionConflict' }),
    )
  })

  it('loads upload sessions on demand', async () => {
    adminApi.getVideoUploadSessions.mockResolvedValue([
      {
        id: 's1',
        videoId: VIDEO_ID,
        status: 'EXPIRED',
        reservedBytes: 5 * 1024 * 1024,
        expiresAt: '2026-10-01T11:00:00Z',
        createdAt: '2026-10-01T10:00:00Z',
      },
    ])
    const wrapper = await mountPage({ videoId: VIDEO_ID })
    expect(adminApi.getVideoUploadSessions).not.toHaveBeenCalled()

    await wrapper.vm.loadSessions()
    await flushPromises()

    expect(adminApi.getVideoUploadSessions).toHaveBeenCalledWith(VIDEO_ID)
    expect(wrapper.find('[data-testid="video-sessions-list"]').text()).toContain('5.0 MB')
  })

  it('offers neither block nor restore for an archived video, and explains why', async () => {
    adminApi.getAdminVideo.mockResolvedValue({
      ...LOCKED,
      operationalState: 'READY',
      accessState: 'ARCHIVED',
      archivedAt: '2026-10-03T10:00:00Z',
    })
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    expect(wrapper.find('[data-testid="video-block-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="video-restore-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="video-archived-hint"]').exists()).toBe(true)
  })

  it('shows "blocked since" only while the video is blocked', async () => {
    adminApi.getAdminVideo.mockResolvedValue({
      ...LOCKED,
      accessState: 'ACTIVE',
      lifecycleLockedAt: '2026-09-01T10:00:00Z',
    })
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    expect(wrapper.text()).not.toContain('admin.videos.blockedSinceLabel')
  })

  it('re-reads the video and the queue after a failed action (it may have partly applied)', async () => {
    adminApi.deleteVideo.mockRejectedValue({ response: { status: 500 } })
    const wrapper = await mountPage({ videoId: VIDEO_ID })

    wrapper.vm.openAction('delete')
    await wrapper.vm.submitAction()

    expect(adminApi.getAdminVideo).toHaveBeenCalledTimes(2)
    expect(adminApi.getVideoReviewQueue).toHaveBeenCalledTimes(2)
  })
})
