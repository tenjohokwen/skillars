// B5: the video status stream must send credentials like the booking and messaging streams do.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useVideoStatusSse } from 'src/stores/video.store'

vi.mock('src/api/video.api', () => ({ videoApi: {} }))

describe('useVideoStatusSse', () => {
  let constructed

  beforeEach(() => {
    vi.useFakeTimers()
    constructed = []
    vi.stubGlobal(
      'EventSource',
      vi.fn(function (url, init) {
        this.url = url
        this.init = init
        this.addEventListener = vi.fn()
        this.close = vi.fn()
        constructed.push(this)
      }),
    )
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({}) })),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })

  it('opens the EventSource with credentials', () => {
    const { stop } = useVideoStatusSse('vid-1')

    expect(constructed).toHaveLength(1)
    expect(constructed[0].url).toBe('/api/video/vid-1/events')
    expect(constructed[0].init).toEqual({ withCredentials: true })
    stop()
  })

  it('polls the status fallback with credentials after an SSE error', async () => {
    const { stop } = useVideoStatusSse('vid-2')
    // First error: polling starts, reconnect after 1s (which stops polling again).
    constructed[0].onerror()
    await vi.advanceTimersByTimeAsync(1000)
    // Second error: polling restarts; its 2s tick fires before the 2s reconnect.
    constructed[1].onerror()
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetch).toHaveBeenCalledWith('/api/video/vid-2/status', { credentials: 'include' })
    stop()
  })
})
