// skillars-deferred-142 AC4 — VideoManagementPage.vue's fetchVideos() 403 handler redirected to
// '/dashboard', a route gated admin-only since skillars-deferred-141 (MainLayout.vue's generic
// "Dashboard" nav link is `v-if="authStore.isAdmin"`). A bounced PLAYER landed on a dead end with no
// menu entry for their role. Fixed to redirect to '/player/dashboard' instead.
//
// Mutation check (run both ways, see Dev Agent Record): revert the redirect target back to
// '/dashboard' — this spec's one real assertion turns red.
//
// UI-gap chunk C7: the page is now shared by the PLAYER route (player/videos) and the COACH route
// (coach/videos) — coach upload-type choice + coach initiate endpoint (A17), a play action for READY
// videos (A17b) and a Retry action for FAILED uploads (D5).

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/video.api', () => ({
  videoApi: {
    getMyVideos: vi.fn(),
    getMyQuota: vi.fn(),
    initiateUpload: vi.fn(),
    initiatePlayerUpload: vi.fn(),
    retryUpload: vi.fn(),
    createTusUpload: vi.fn(),
    getMyCoachVideos: vi.fn(),
    listSubjectBookings: vi.fn(),
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

const EMPTY_QUOTA = {
  storageUsedBytes: 0,
  storageLimitBytes: 0,
  bandwidthUsedBytes: 0,
  bandwidthLimitBytes: 0,
}

// The dropdown's menu is teleported and only rendered when open; render its slot inline instead.
const QBtnDropdownStub = { template: '<div data-testid="coach-upload-dropdown"><slot /></div>' }
// QDialog teleports; render its content inline while open.
const QDialogStub = { props: ['modelValue'], template: '<div v-if="modelValue"><slot /></div>' }

async function mountPage({ role = 'PLAYER' } = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/player/videos', component: VideoManagementPage },
      { path: '/coach/videos', component: VideoManagementPage },
      { path: '/dashboard', component: STUB },
      { path: '/player/dashboard', component: STUB },
      { path: '/coach/command-center', component: STUB },
    ],
  })
  router.push(role === 'COACH' ? '/coach/videos' : '/player/videos')
  await router.isReady()

  const pinia = createTestingPinia({ createSpy: vi.fn, initialState: { auth: { role } } })
  const wrapper = mount(VideoManagementPage, {
    global: {
      plugins: [router, pinia],
      stubs: {
        VideoStatusCard: true,
        QuotaUsageBar: true,
        VideoPlayDialog: true,
        QBtnDropdown: QBtnDropdownStub,
        QDialog: QDialogStub,
      },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}

async function selectFile(wrapper, file) {
  const input = wrapper.find('[data-testid="video-file-input"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

function fakeFile(name = 'clip.mp4', size = 2048) {
  const file = new File(['x'], name, { type: 'video/mp4' })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

const CREDS = {
  videoId: 'v-1',
  signedUploadUrl: 'https://tus',
  providerUploadId: 'guid',
  tusAuthorizationSignature: 'sig',
  tusAuthorizationExpire: 1,
  tusLibraryId: 2,
}

beforeEach(() => {
  videoApi.getMyCoachVideos.mockResolvedValue([])
})

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('VideoManagementPage.vue — 403 redirect target (skillars-deferred-142 AC4)', () => {
  it('redirects to /player/dashboard, not /dashboard, on a 403 from getMyVideos()', async () => {
    videoApi.getMyVideos.mockRejectedValue({ response: { status: 403 } })
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)

    const { router } = await mountPage()

    expect(router.currentRoute.value.path).toBe('/player/dashboard')
    // 'message' is the discriminating field: both catch-arms notify with type 'negative', so
    // asserting only the type would survive deleting the status check entirely.
    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'video.management.accessDenied' }),
    )
  })

  it("redirects a coach to the coach's own home, not the player dashboard", async () => {
    videoApi.getMyVideos.mockRejectedValue({ response: { status: 403 } })
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)

    const { router } = await mountPage({ role: 'COACH' })

    expect(router.currentRoute.value.path).toBe('/coach/command-center')
  })
})

describe('VideoManagementPage.vue — role-aware upload (A17)', () => {
  it('a player gets the single HOMEWORK upload button on the player initiate endpoint', async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.initiatePlayerUpload.mockResolvedValue(CREDS)
    videoApi.createTusUpload.mockReturnValue({ start: vi.fn() })

    const { wrapper } = await mountPage()
    expect(wrapper.find('[data-testid="coach-upload-dropdown"]').exists()).toBe(false)

    await wrapper.find('[data-testid="player-upload-btn"]').trigger('click')
    await selectFile(wrapper, fakeFile())

    expect(videoApi.initiatePlayerUpload).toHaveBeenCalledWith(
      expect.objectContaining({ videoType: 'HOMEWORK', fileSizeBytes: 2048 }),
    )
    expect(videoApi.initiateUpload).not.toHaveBeenCalled()
  })

  it('a coach chooses a coach video type and uploads through the coach initiate endpoint', async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.initiateUpload.mockResolvedValue(CREDS)
    const start = vi.fn()
    videoApi.createTusUpload.mockReturnValue({ start })

    const { wrapper } = await mountPage({ role: 'COACH' })
    expect(wrapper.find('[data-testid="player-upload-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="coach-upload-HOMEWORK"]').exists()).toBe(false)

    await wrapper.find('[data-testid="coach-upload-DRILL_DEMO"]').trigger('click')
    await selectFile(wrapper, fakeFile())

    expect(videoApi.initiateUpload).toHaveBeenCalledWith(
      expect.objectContaining({ videoType: 'DRILL_DEMO', fileName: 'clip.mp4' }),
    )
    expect(videoApi.initiatePlayerUpload).not.toHaveBeenCalled()
    expect(videoApi.createTusUpload).toHaveBeenCalledWith(
      expect.objectContaining({ signedUploadUrl: 'https://tus', providerUploadId: 'guid' }),
    )
    expect(start).toHaveBeenCalled()
  })

  it('ignores a file selection that no upload/retry action asked for', async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)

    const { wrapper } = await mountPage()
    await selectFile(wrapper, fakeFile())

    expect(videoApi.initiatePlayerUpload).not.toHaveBeenCalled()
    expect(videoApi.retryUpload).not.toHaveBeenCalled()
  })
})

describe('VideoManagementPage.vue — play and retry actions (A17b, D5)', () => {
  it('offers Play only on READY videos and Retry only on FAILED videos', async () => {
    videoApi.getMyVideos.mockResolvedValue([
      { id: 'ready', operationalState: 'READY', videoType: 'HOMEWORK' },
      { id: 'failed', operationalState: 'FAILED', videoType: 'HOMEWORK' },
      { id: 'busy', operationalState: 'TRANSCODING', videoType: 'HOMEWORK' },
    ])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)

    const { wrapper } = await mountPage()
    const rows = wrapper.findAll('[data-testid="video-list-item"]')
    expect(rows).toHaveLength(3)
    expect(rows[0].find('[data-testid="video-play-btn"]').exists()).toBe(true)
    expect(rows[0].find('[data-testid="video-retry-btn"]').exists()).toBe(false)
    expect(rows[1].find('[data-testid="video-play-btn"]').exists()).toBe(false)
    expect(rows[1].find('[data-testid="video-retry-btn"]').exists()).toBe(true)
    expect(rows[2].find('[data-testid="video-play-btn"]').exists()).toBe(false)
    expect(rows[2].find('[data-testid="video-retry-btn"]').exists()).toBe(false)
  })

  it('Play opens the player dialog for that video', async () => {
    videoApi.getMyVideos.mockResolvedValue([
      { id: 'ready', title: 'My clip', operationalState: 'READY', videoType: 'HOMEWORK' },
    ])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)

    const { wrapper } = await mountPage()
    const dialog = wrapper.findComponent({ name: 'VideoPlayDialog' })
    expect(dialog.props('modelValue')).toBe(false)

    await wrapper.find('[data-testid="video-play-btn"]').trigger('click')

    expect(dialog.props('modelValue')).toBe(true)
    expect(dialog.props('videoId')).toBe('ready')
    expect(dialog.props('title')).toBe('My clip')
  })

  it('Retry re-uploads the re-selected file on the SAME video via the retry endpoint', async () => {
    const failed = { id: 'failed', operationalState: 'FAILED', videoType: 'HOMEWORK' }
    videoApi.getMyVideos.mockResolvedValue([failed])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.retryUpload.mockResolvedValue({ ...CREDS, videoId: 'failed' })
    const start = vi.fn()
    videoApi.createTusUpload.mockReturnValue({ start })

    const { wrapper } = await mountPage()
    await wrapper.find('[data-testid="video-retry-btn"]').trigger('click')
    await selectFile(wrapper, fakeFile('again.mp4', 4096))

    expect(videoApi.retryUpload).toHaveBeenCalledWith('failed', 4096)
    expect(videoApi.initiatePlayerUpload).not.toHaveBeenCalled()
    expect(start).toHaveBeenCalled()
    // The row leaves FAILED, so its Retry action disappears while the upload runs.
    expect(wrapper.find('[data-testid="video-retry-btn"]').exists()).toBe(false)
  })

  it('a 409 on retry (already being retried) says so and re-reads the list', async () => {
    videoApi.getMyVideos.mockResolvedValue([
      { id: 'failed', operationalState: 'FAILED', videoType: 'HOMEWORK' },
    ])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.retryUpload.mockRejectedValue({ response: { status: 409 } })

    const { wrapper } = await mountPage()
    expect(videoApi.getMyVideos).toHaveBeenCalledTimes(1)
    await wrapper.find('[data-testid="video-retry-btn"]').trigger('click')
    await selectFile(wrapper, fakeFile())

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'video.management.retryConflict' }),
    )
    expect(videoApi.createTusUpload).not.toHaveBeenCalled()
    expect(videoApi.getMyVideos).toHaveBeenCalledTimes(2)
  })
})

describe('VideoManagementPage.vue — minors never upload / coach video subject (V184)', () => {
  it('a COACH_REVIEW asks for the session first and sends it as subjectBookingId', async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.listSubjectBookings.mockResolvedValue([
      {
        bookingId: 'b-1',
        playerId: 77,
        playerName: 'Mia',
        requestedStartTime: '2026-10-20T10:00:00Z',
        canonicalTimezone: 'Europe/Berlin',
      },
    ])
    videoApi.initiateUpload.mockResolvedValue(CREDS)
    videoApi.createTusUpload.mockReturnValue({ start: vi.fn() })

    const { wrapper } = await mountPage({ role: 'COACH' })
    await wrapper.find('[data-testid="coach-upload-COACH_REVIEW"]').trigger('click')
    await flushPromises()

    expect(videoApi.listSubjectBookings).toHaveBeenCalled()
    expect(wrapper.find('[data-testid="subject-booking-select"]').exists()).toBe(true)
    // A file picked before a session is chosen is ignored — nothing asked for it yet.
    await selectFile(wrapper, fakeFile())
    expect(videoApi.initiateUpload).not.toHaveBeenCalled()

    wrapper.vm.subjectBookingId = 'b-1'
    await flushPromises()
    await wrapper.find('[data-testid="subject-confirm-btn"]').trigger('click')
    await selectFile(wrapper, fakeFile())

    expect(videoApi.initiateUpload).toHaveBeenCalledWith(
      expect.objectContaining({ videoType: 'COACH_REVIEW', subjectBookingId: 'b-1' }),
    )
  })

  it('a coach with no held sessions sees why a session review is not possible', async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.listSubjectBookings.mockResolvedValue([])

    const { wrapper } = await mountPage({ role: 'COACH' })
    await wrapper.find('[data-testid="coach-upload-COACH_REVIEW"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="no-subject-bookings"]').exists()).toBe(true)
  })

  it("a refused minor's upload shows the minors-never-upload message", async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.initiatePlayerUpload.mockRejectedValue({
      response: { status: 403, data: { errorMsg: { errorKey: 'VIDEO_MINOR_UPLOAD_REFUSED' } } },
    })

    const { wrapper } = await mountPage()
    await wrapper.find('[data-testid="player-upload-btn"]').trigger('click')
    await selectFile(wrapper, fakeFile())

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'video.management.minorUploadRefused' }),
    )
    expect(videoApi.createTusUpload).not.toHaveBeenCalled()
  })

  it('a player sees the coach videos about them, playable but not deletable', async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)
    videoApi.getMyCoachVideos.mockResolvedValue([
      { id: 'c1', operationalState: 'READY', videoType: 'COACH_REVIEW' },
    ])

    const { wrapper } = await mountPage()
    const section = wrapper.find('[data-testid="coach-videos-section"]')
    expect(section.findAll('[data-testid="video-list-item"]')).toHaveLength(1)
    expect(section.find('[data-testid="video-play-btn"]').exists()).toBe(true)
    expect(section.find('[data-testid="video-delete-btn"]').exists()).toBe(false)
  })

  it('a coach does not load a coach-videos-about-me list', async () => {
    videoApi.getMyVideos.mockResolvedValue([])
    videoApi.getMyQuota.mockResolvedValue(EMPTY_QUOTA)

    const { wrapper } = await mountPage({ role: 'COACH' })

    expect(videoApi.getMyCoachVideos).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="coach-videos-section"]').exists()).toBe(false)
  })
})
