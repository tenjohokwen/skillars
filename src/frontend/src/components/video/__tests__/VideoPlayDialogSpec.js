// UI-gap chunk C7 / A17b — signed playback through GET /api/video/{id}/play, with one silent
// re-fetch on a media error (probable URL expiry) and never a loop.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/video.api', () => ({
  videoApi: { getPlayUrl: vi.fn() },
}))

import VideoPlayDialog from '../VideoPlayDialog.vue'
import { videoApi } from 'src/api/video.api'

const mounted = []

// QDialog teleports its content; render the slot inline so the card is queryable.
const QDialogStub = { template: '<div><slot /></div>' }

async function mountDialog(props = {}) {
  const wrapper = mount(VideoPlayDialog, {
    props: { modelValue: true, videoId: 'vid-1', title: 'Clip', ...props },
    global: { stubs: { QDialog: QDialogStub } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const playback = (n) => ({
  signedHlsUrl: `https://cdn/play-${n}.m3u8`,
  expiresAt: new Date(Date.now() + 3600_000).toISOString(),
  downloadUrl: null,
})

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('VideoPlayDialog.vue', () => {
  it('fetches the signed URL when opened and plays it', async () => {
    videoApi.getPlayUrl.mockResolvedValue(playback(1))
    const wrapper = await mountDialog()

    expect(videoApi.getPlayUrl).toHaveBeenCalledWith('vid-1')
    expect(wrapper.find('[data-testid="video-play-element"]').attributes('src')).toBe(
      'https://cdn/play-1.m3u8',
    )
  })

  it('does not fetch while closed', async () => {
    await mountDialog({ modelValue: false })
    expect(videoApi.getPlayUrl).not.toHaveBeenCalled()
  })

  it('answers a media error with ONE silent re-fetch, then stops with a manual retry', async () => {
    videoApi.getPlayUrl.mockResolvedValueOnce(playback(1)).mockResolvedValueOnce(playback(2))
    const wrapper = await mountDialog()

    await wrapper.find('[data-testid="video-play-element"]').trigger('error')
    await flushPromises()
    expect(videoApi.getPlayUrl).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="video-play-element"]').attributes('src')).toBe(
      'https://cdn/play-2.m3u8',
    )

    await wrapper.find('[data-testid="video-play-element"]').trigger('error')
    await flushPromises()
    expect(videoApi.getPlayUrl).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="video-play-error"]').text()).toContain(
      'video.player.loadError',
    )
    expect(wrapper.find('[data-testid="video-play-retry"]').exists()).toBe(true)
  })

  it('reloads the element even when the re-fetch returns the SAME URL, so a second error still surfaces', async () => {
    videoApi.getPlayUrl.mockResolvedValue(playback(1))
    const wrapper = await mountDialog()
    const first = wrapper.find('[data-testid="video-play-element"]').element

    await wrapper.find('[data-testid="video-play-element"]').trigger('error')
    await flushPromises()
    expect(wrapper.find('[data-testid="video-play-element"]').element).not.toBe(first)

    await wrapper.find('[data-testid="video-play-element"]').trigger('error')
    await flushPromises()
    expect(wrapper.find('[data-testid="video-play-error"]').exists()).toBe(true)
  })

  it('maps a 403 to a non-retryable "not allowed" state', async () => {
    videoApi.getPlayUrl.mockRejectedValue({ response: { status: 403 } })
    const wrapper = await mountDialog()

    expect(wrapper.find('[data-testid="video-play-error"]').text()).toContain(
      'video.player.notAllowed',
    )
    expect(wrapper.find('[data-testid="video-play-retry"]').exists()).toBe(false)
  })
})
