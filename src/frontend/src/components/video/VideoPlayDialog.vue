<template>
  <q-dialog :model-value="modelValue" @update:model-value="onDialogToggle">
    <q-card class="video-play-dialog glass-card" data-testid="video-play-dialog">
      <q-card-section class="row items-center q-pb-none">
        <div class="text-subtitle1 ellipsis">{{ title || t('video.player.title') }}</div>
        <q-space />
        <q-btn
          v-close-popup
          flat
          round
          dense
          icon="close"
          :aria-label="t('video.player.closeAriaLabel')"
        />
      </q-card-section>

      <q-card-section>
        <div v-if="loading" class="video-play-dialog__frame flex flex-center">
          <q-spinner size="40px" color="primary" />
        </div>

        <div
          v-else-if="errorKey"
          class="video-play-dialog__frame flex flex-center column q-gutter-sm text-center"
          data-testid="video-play-error"
        >
          <q-icon name="videocam_off" size="48px" />
          <div class="text-body2">{{ t(errorKey) }}</div>
          <q-btn
            v-if="errorRetryable"
            flat
            color="primary"
            :label="t('common.retry')"
            data-testid="video-play-retry"
            @click="fetchPlayUrl"
          />
        </div>

        <!-- The signed URL is an HLS playlist (Bunny .m3u8). It is fed straight to the native element,
             the same as drill videos (DrillDetailPanel.vue) — no extra player dependency. :key is a
             per-fetch counter (not the URL), so every re-fetch reloads the element even when the backend
             hands back the same URL — otherwise a non-expiry media error would leave a dead player. -->
        <video
          v-else-if="playback"
          :key="playbackKey"
          ref="videoEl"
          :src="playback.signedHlsUrl"
          class="video-play-dialog__video"
          controls
          autoplay
          playsinline
          data-testid="video-play-element"
          @loadeddata="onMediaLoaded"
          @error="onMediaError"
        />

        <div v-if="playback?.downloadUrl" class="q-mt-sm">
          <q-btn
            flat
            dense
            no-caps
            icon="download"
            color="primary"
            type="a"
            target="_blank"
            rel="noopener"
            :href="playback.downloadUrl"
            :label="t('video.player.download')"
          />
        </div>
      </q-card-section>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { videoApi } from 'src/api/video.api'

/**
 * Plays one video through its signed, time-limited playback URL (GET /api/video/{id}/play).
 *
 * Expiry handling: the signed URL carries its own expiresAt. A media error is treated as a probable
 * expiry and answered with ONE silent re-fetch of a fresh URL; a second error in a row stops and shows
 * the unavailable state with a manual retry (never loops — same contract as useDrillVideoPlayback).
 * The re-fetched URL resumes from the position the viewer had reached. There is deliberately no timer
 * that swaps the URL before expiresAt: changing the source of a playing element restarts it.
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  videoId: { type: String, default: null },
  title: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue'])

const { t } = useI18n()

const loading = ref(false)
const playback = ref(null)
const playbackKey = ref(0)
const videoEl = ref(null)
const errorKey = ref(null)
const errorRetryable = ref(false)
let mediaRetried = false
// Playback position to restore once a re-fetched URL has loaded (0 = start).
let resumeAt = 0
let requestSeq = 0

function errorKeyFor(err) {
  const status = err?.response?.status
  if (status === 403) return { key: 'video.player.notAllowed', retryable: false }
  if (status === 404) return { key: 'video.player.notFound', retryable: false }
  if (status === 409 || status === 422) return { key: 'video.player.notPlayable', retryable: false }
  return { key: 'video.player.loadError', retryable: true }
}

async function fetchPlayUrl({ silent = false } = {}) {
  if (!props.videoId) return
  const seq = ++requestSeq
  if (!silent) {
    loading.value = true
    errorKey.value = null
  }
  try {
    const data = await videoApi.getPlayUrl(props.videoId)
    if (seq !== requestSeq) return
    playback.value = data
    playbackKey.value++
    errorKey.value = null
  } catch (err) {
    if (seq !== requestSeq) return
    const { key, retryable } = errorKeyFor(err)
    playback.value = null
    errorKey.value = key
    errorRetryable.value = retryable
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function onMediaLoaded() {
  // A URL that loaded fine re-arms the one-shot silent refresh for its own later expiry.
  mediaRetried = false
  if (resumeAt > 0 && videoEl.value) {
    videoEl.value.currentTime = resumeAt
    resumeAt = 0
  }
}

function onMediaError() {
  if (!mediaRetried) {
    // Most likely the signed URL expired while the dialog was open — fetch a fresh one once and
    // carry on from where the viewer was.
    mediaRetried = true
    resumeAt = videoEl.value?.currentTime || 0
    fetchPlayUrl({ silent: true })
    return
  }
  playback.value = null
  errorKey.value = 'video.player.loadError'
  errorRetryable.value = true
  mediaRetried = false
}

function reset() {
  requestSeq++
  playback.value = null
  resumeAt = 0
  errorKey.value = null
  loading.value = false
  mediaRetried = false
}

function onDialogToggle(value) {
  emit('update:modelValue', value)
}

watch(
  () => [props.modelValue, props.videoId],
  ([open, id]) => {
    reset()
    if (open && id) fetchPlayUrl()
  },
  { immediate: true },
)

onBeforeUnmount(reset)

defineExpose({ fetchPlayUrl })
</script>

<style scoped>
.video-play-dialog {
  width: 720px;
  max-width: 95vw;
}
.video-play-dialog__video {
  width: 100%;
  max-height: 70vh;
  background: #000;
  border-radius: 8px;
}
.video-play-dialog__frame {
  min-height: 240px;
}
</style>
