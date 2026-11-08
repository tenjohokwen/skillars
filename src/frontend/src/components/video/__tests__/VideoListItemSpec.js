// Pitchside ADM-11: a video an admin blocked (or the lifecycle archived) used to keep its Play button, since only
// the operational state was checked; playback needs READY and ACTIVE, so Play could only fail. It now shows why.

import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import VideoListItem from 'src/components/video/VideoListItem.vue'

const VideoStatusCard = { name: 'VideoStatusCard', template: '<div />' }
const mounted = []

function mountItem(video) {
  const wrapper = mount(VideoListItem, {
    props: { video: { id: 'v1', title: 'clip.mp4', operationalState: 'READY', ...video } },
    global: { stubs: { VideoStatusCard } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('VideoListItem.vue', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('offers Play for a ready, active video, with no availability chip', () => {
    const wrapper = mountItem({ accessState: 'ACTIVE' })
    expect(wrapper.find('[data-testid="video-play-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="video-access-state"]').exists()).toBe(false)
  })

  it('treats an absent access state as active', () => {
    const wrapper = mountItem({})
    expect(wrapper.find('[data-testid="video-play-btn"]').exists()).toBe(true)
  })

  it.each([
    ['BLOCKED', 'video.access.BLOCKED'],
    ['ARCHIVED', 'video.access.ARCHIVED'],
  ])('a %s video says it is unavailable and offers no Play', (state, key) => {
    const wrapper = mountItem({ accessState: state })
    expect(wrapper.find('[data-testid="video-play-btn"]').exists()).toBe(false)
    const chip = wrapper.find('[data-testid="video-access-state"]')
    expect(chip.attributes('data-state')).toBe(state)
    expect(chip.text()).toContain(key)
  })
})
