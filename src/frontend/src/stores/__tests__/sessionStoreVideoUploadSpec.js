// Drill demo uploads go through videoApi.createTusUpload, which needs the Bunny TUS auth fields
// (AuthorizationSignature / AuthorizationExpire / LibraryId / VideoId). initiateVideoUpload used to
// strip them from the backend response, so every drill demo TUS upload was rejected by Bunny.

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('src/api/session.api', () => ({
  sessionApi: { initiateVideoUpload: vi.fn() },
}))

import { sessionApi } from 'src/api/session.api'
import { useSessionStore } from 'src/stores/session.store'

describe('session.store initiateVideoUpload', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('returns the Bunny TUS authorization fields alongside the upload URL', async () => {
    const backend = {
      videoId: 'v1',
      uploadSessionId: 's1',
      signedUploadUrl: 'https://video.bunnycdn.com/tusupload',
      expiresAt: '2026-01-01T00:00:00Z',
      providerUploadId: 'bunny-guid',
      tusAuthorizationSignature: 'sig',
      tusAuthorizationExpire: 1999999999,
      tusLibraryId: 42,
    }
    sessionApi.initiateVideoUpload.mockResolvedValue(backend)
    const store = useSessionStore()

    const creds = await store.initiateVideoUpload(
      'd1',
      { name: 'demo.mp4', size: 10, type: 'video/mp4' },
      12,
    )

    expect(creds).toEqual(backend)
    expect(sessionApi.initiateVideoUpload).toHaveBeenCalledWith('d1', {
      fileName: 'demo.mp4',
      fileSizeBytes: 10,
      mimeType: 'video/mp4',
      durationSeconds: 12,
    })
  })
})
