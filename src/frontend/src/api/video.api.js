import { api } from 'src/boot/axios'
import * as tus from 'tus-js-client'

export const videoApi = {
  getPlayUrl(videoId) {
    return api.get(`/api/video/${videoId}/play`)
  },

  // signal is an AbortSignal from AbortController — passed by video.store.js for cancel-during-initiation support
  initiateUpload(payload, signal) {
    return api.post('/api/video/uploads/initiate', payload, { signal })
  },

  initiatePlayerUpload(payload, signal) {
    return api.post('/api/video/player/uploads/initiate', payload, { signal })
  },

  getMyVideos() {
    return api.get('/api/video/my')
  },

  getMyQuota() {
    return api.get('/api/video/quotas/me')
  },

  // A linked player's videos (parent view). playerId is the PlayerProfile id, kept as a string.
  getPlayerVideos(playerId) {
    return api.get(`/api/video/players/${playerId}/videos`)
  },

  // Restart a FAILED upload on the same video record; returns fresh TUS credentials (same shape as
  // initiateUpload's response) for the re-selected file.
  retryUpload(videoId, fileSizeBytes) {
    return api.post(`/api/video/${videoId}/retry`, { fileSizeBytes })
  },

  deleteVideo(videoId) {
    return api.delete(`/api/video/${videoId}`)
  },

  // Coach videos ABOUT a player (V184 subject link) — parent view of a linked child; play only.
  getPlayerCoachVideos(playerId) {
    return api.get(`/api/video/players/${playerId}/coach-videos`)
  },

  // The self-registered player's own view of the coach videos about them.
  getMyCoachVideos() {
    return api.get('/api/video/my/coach-videos')
  },

  // The coach's held sessions (last 90 days) a COACH_REVIEW / DRILL_DEMO upload can be linked to.
  listSubjectBookings() {
    return api.get('/api/video/coach/subject-bookings')
  },

  createTusUpload({
    file,
    signedUploadUrl,
    providerUploadId,
    tusAuthorizationSignature,
    tusAuthorizationExpire,
    tusLibraryId,
    onProgress,
    onSuccess,
    onError,
  }) {
    return new tus.Upload(file, {
      endpoint: signedUploadUrl,
      retryDelays: [1000, 3000, 5000, 10000, 20000], // no immediate retry — avoids hammering Bunny on 429/503
      // Bunny.net requires all four headers for TUS auth — computed server-side
      headers: {
        AuthorizationSignature: tusAuthorizationSignature,
        AuthorizationExpire: String(tusAuthorizationExpire),
        LibraryId: String(tusLibraryId),
        VideoId: providerUploadId,
      },
      // Bunny.net requires 'title' and 'filetype' in Upload-Metadata
      metadata: {
        title: file.name,
        filetype: file.type,
      },
      onProgress(bytesUploaded, bytesTotal) {
        onProgress?.(bytesUploaded, bytesTotal)
      },
      onSuccess,
      onError,
    })
  },
}
