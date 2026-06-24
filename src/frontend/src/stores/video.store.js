import { defineStore } from 'pinia'
import { ref } from 'vue'
import { videoApi } from 'src/api/video.api'

const SSE_BACKOFF_DELAYS = [1000, 2000, 4000, 8000]
// ARCHIVED: defensive — SSE is inactive after READY so ARCHIVED will not arrive via SSE in practice.
// DELETED: markPurged() fires VideoStatusChangedEvent(DELETED) which reaches SSE subscribers.
//   Without DELETED here, the SSE connection stays open indefinitely after purge.
// SUBSCRIPTION_LOCKED is intentionally excluded — it is reversible (player may renew within 30 days).
// HIDDEN is NOT terminal: a video awaiting parental approval will transition to TRANSCODING or REJECTED;
//   the SSE connection must stay open so the VideoStatusCard receives the follow-up state push.
// REJECTED is terminal: parental approval is final.
const TERMINAL_SSE_STATES = new Set(['READY', 'LOCKED', 'REJECTED', 'FAILED', 'DELETED', 'ARCHIVED'])
const POLLING_INTERVAL_MS = 2000

export function useVideoStatusSse(videoId, { onStatusChange, onTerminal } = {}) {
  let eventSource = null
  let retryIndex = 0
  let retryTimer = null
  let pollTimer = null
  let active = true

  function startPolling() {
    if (pollTimer) return
    pollTimer = setInterval(async () => {
      if (!active) return
      try {
        const res = await fetch(`/api/video/${videoId}/status`)
        if (res.status === 401 || res.status === 403) {
          onStatusChange?.('AUTH_ERROR')
          stop()
          return
        }
        if (!res.ok) return
        const data = await res.json()
        // displayState bridges the two-axis model (operationalState + accessState) to a single
        // render-ready value. Fall back to operationalState for backward compat with old API responses.
        const state = data.displayState ?? data.operationalState
        onStatusChange?.(state)
        if (TERMINAL_SSE_STATES.has(state)) {
          onTerminal?.(state)
          stop()
        }
      } catch {
        // network error during polling — ignore and retry next tick
      }
    }, POLLING_INTERVAL_MS)
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function connect() {
    if (!active) return
    eventSource = new EventSource(`/api/video/${videoId}/events`)
    stopPolling() // SSE connected — stop polling fallback

    eventSource.addEventListener('status', (event) => {
      retryIndex = 0
      const state = event.data
      onStatusChange?.(state)
      if (TERMINAL_SSE_STATES.has(state)) {
        onTerminal?.(state)
        stop()
      }
    })

    eventSource.onerror = () => {
      if (eventSource) {
        eventSource.close()
        eventSource = null
      }
      if (active) {
        startPolling()
        const delay = SSE_BACKOFF_DELAYS[Math.min(retryIndex, SSE_BACKOFF_DELAYS.length - 1)]
        retryIndex++
        clearTimeout(retryTimer)
        retryTimer = setTimeout(connect, delay)
      }
    }
  }

  function stop() {
    active = false
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    clearTimeout(retryTimer)
    stopPolling()
  }

  connect()
  return { stop }
}

export const useVideoStore = defineStore('video', () => {
  const uploadProgress = ref(0)
  const uploadState = ref('idle') // 'idle' | 'initiating' | 'uploading' | 'processing' | 'error'
  const currentVideoId = ref(null)
  const currentUpload = ref(null)
  const currentAbortController = ref(null) // cancels the axios POST during 'initiating' state

  async function initiateAndUpload({ file, videoType, onProgress, onSuccess, onError }) {
    // Guard against concurrent uploads — a second call while uploading orphans the first TUS session
    if (uploadState.value !== 'idle') {
      onError?.(new Error('An upload is already in progress — cancel the current upload before starting a new one'))
      return
    }
    try {
      uploadState.value = 'initiating'
      uploadProgress.value = 0
      currentVideoId.value = null

      const controller = new AbortController()
      currentAbortController.value = controller

      const { data } = await videoApi.initiateUpload({
        fileName: file.name,
        fileSizeBytes: file.size,
        mimeType: file.type,
        videoType,
      }, controller.signal)

      // Guard: cancelUpload() was called while we were awaiting the initiate POST.
      // The store is already reset to 'idle'; bail without starting a TUS upload.
      if (uploadState.value === 'idle') return

      currentVideoId.value = data.videoId
      uploadState.value = 'uploading'
      // Audit note (M-4): clear the AbortController after initiation resolves — its signal
      // is settled and reusing it in cancelUpload() during 'uploading' state is misleading.
      currentAbortController.value = null

      const upload = videoApi.createTusUpload({
        file,
        signedUploadUrl: data.signedUploadUrl,
        providerUploadId: data.providerUploadId,
        tusAuthorizationSignature: data.tusAuthorizationSignature,
        tusAuthorizationExpire: data.tusAuthorizationExpire,
        tusLibraryId: data.tusLibraryId,
        onProgress(bytesUploaded, bytesTotal) {
          uploadProgress.value = bytesTotal > 0
            ? Math.round((bytesUploaded / bytesTotal) * 100)
            : 0
          onProgress?.(uploadProgress.value)
        },
        onSuccess() {
          uploadState.value = 'processing'
          onSuccess?.(currentVideoId.value)
        },
        onError(err) {
          uploadState.value = 'error'
          onError?.(err)
        },
      })

      currentUpload.value = upload
      upload.start()
    } catch (err) {
      // Audit fix (H-3): if cancelUpload() aborted the axios POST, it already reset
      // uploadState to 'idle'. The AbortError rejection reaches this catch block AFTER
      // that reset (JS microtask ordering). Guard here to avoid overwriting 'idle' → 'error'.
      if (uploadState.value === 'idle') return
      uploadState.value = 'error'
      onError?.(err)
    }
  }

  async function cancelUpload() {
    // Cancel the in-flight axios POST if we are still in 'initiating' state.
    // Without this, the POST resolves after state resets and starts an untracked TUS upload.
    if (currentAbortController.value) {
      currentAbortController.value.abort()
      currentAbortController.value = null
    }
    if (currentUpload.value) {
      // Pass true to send a DELETE to Bunny's TUS endpoint and terminate the upload
      // server-side. Without this, chunks may continue in-flight after state resets.
      // abort(true) may fail if the network is gone — swallow but log.
      await currentUpload.value.abort(true).catch((err) => {
        console.warn('TUS abort failed (network may be unavailable):', err)
      })
    }
    uploadState.value = 'idle'
    uploadProgress.value = 0
    currentVideoId.value = null
    currentUpload.value = null
  }

  // Audit note (H-1): after encoding begins, uploadState stays 'processing' indefinitely —
  // there is no server-push in Story 6.2 to signal when Bunny encoding completes.
  // A coach who wants to upload a second video cannot call initiateAndUpload() while
  // uploadState !== 'idle'. They must call resetUpload() (or reload the page) first.
  // resetUpload() is also the correct recovery path after 'error' state.
  async function resetUpload() {
    if (currentAbortController.value) {
      currentAbortController.value.abort()
      currentAbortController.value = null
    }
    if (currentUpload.value) {
      await currentUpload.value.abort(true).catch((err) => {
        console.warn('TUS abort on reset failed (network may be unavailable):', err)
      })
    }
    uploadState.value = 'idle'
    uploadProgress.value = 0
    currentVideoId.value = null
    currentUpload.value = null
  }

  return {
    uploadProgress,
    uploadState,
    currentVideoId,
    initiateAndUpload,
    cancelUpload,
    resetUpload, // use for: error recovery, post-encoding upload-again, explicit reset
    // currentAbortController is internal — do not expose
  }
})
