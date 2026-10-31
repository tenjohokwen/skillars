<template>
  <q-page class="q-pa-md">
    <div class="q-mb-md">
      <div class="text-h5">{{ t('video.management.title') }}</div>
    </div>

    <!-- S-18: no video is shown to anyone but its uploader until a real video scanner has passed it. -->
    <q-banner dense rounded class="q-mb-md" data-testid="video-review-notice">
      <template #avatar><q-icon name="shield" /></template>
      {{ t('video.management.reviewNotice') }}
    </q-banner>

    <!-- Quota bar -->
    <QuotaUsageBar
      class="q-mb-lg"
      :storage-used-bytes="quota.storageUsedBytes"
      :storage-limit-bytes="quota.storageLimitBytes"
      :bandwidth-used-bytes="quota.bandwidthUsedBytes"
      :bandwidth-limit-bytes="quota.bandwidthLimitBytes"
      :loading="quotaLoading"
    />

    <!-- Upload — players upload HOMEWORK (only self-registered adults reach this page; the server
         refuses a minor's upload at initiation: ADR "Minors never upload"). Coaches choose one of the
         two types POST /api/video/uploads/initiate accepts for them (DRILL_DEMO, COACH_REVIEW); a
         COACH_REVIEW first asks which held session (and so which child) it is about (V184 subject). -->
    <div class="q-mb-md">
      <q-btn-dropdown
        v-if="isCoach"
        color="primary"
        icon="upload"
        :label="t('video.management.coachUploadButton')"
        :loading="uploadBusy"
        :disable="uploadBusy"
        data-testid="coach-upload-dropdown"
      >
        <q-list>
          <q-item
            v-for="type in COACH_VIDEO_TYPES"
            :key="type"
            v-close-popup
            clickable
            :data-testid="`coach-upload-${type}`"
            @click="openUpload(type)"
          >
            <q-item-section>
              <q-item-label>{{ t(`video.type.${type}`) }}</q-item-label>
              <q-item-label caption>{{ t(`video.typeHint.${type}`) }}</q-item-label>
            </q-item-section>
          </q-item>
        </q-list>
      </q-btn-dropdown>
      <q-btn
        v-else
        color="primary"
        icon="upload"
        :label="t('video.management.uploadButton')"
        :loading="uploadBusy"
        data-testid="player-upload-btn"
        @click="openUpload('HOMEWORK')"
      />
      <q-linear-progress
        v-if="uploadState === 'uploading'"
        :value="uploadProgress / 100"
        color="primary"
        class="q-mt-sm"
        style="max-width: 400px"
      />
    </div>
    <input
      ref="fileInputRef"
      type="file"
      accept="video/*"
      style="display: none"
      data-testid="video-file-input"
      @change="onFileSelected"
    />

    <!-- Video list -->
    <div v-if="videosLoading" class="q-gutter-md">
      <q-skeleton v-for="i in 3" :key="i" type="QCard" height="80px" />
    </div>

    <div v-else-if="videos.length === 0" class="text-secondary">
      {{ t('video.management.emptyState') }}
    </div>

    <div v-else class="q-gutter-md">
      <VideoListItem
        v-for="video in videos"
        :key="video.id"
        :video="video"
        can-retry
        :retrying="retryingVideoId === video.id"
        @status-changed="onStatusChanged(video.id, $event)"
        @play="openPlayer"
        @retry="startRetry"
        @delete="confirmDelete(video.id)"
      />
    </div>

    <!-- Player: session videos the coach recorded of them (V184 subject link) — play only. -->
    <div v-if="!isCoach" class="q-mt-xl" data-testid="coach-videos-section">
      <div class="text-h6">{{ t('video.coachVideos.title') }}</div>
      <div class="text-body2 text-secondary q-mb-md">
        {{ t('video.coachVideos.playerSubtitle') }}
      </div>
      <div v-if="coachVideosLoading" class="q-gutter-md">
        <q-skeleton type="QCard" height="80px" />
      </div>
      <div v-else-if="coachVideosError" class="text-negative" data-testid="coach-videos-error">
        {{ t('video.coachVideos.loadError') }}
      </div>
      <div
        v-else-if="coachVideos.length === 0"
        class="text-secondary"
        data-testid="coach-videos-empty"
      >
        {{ t('video.coachVideos.empty') }}
      </div>
      <div v-else class="q-gutter-md">
        <VideoListItem
          v-for="video in coachVideos"
          :key="video.id"
          :video="video"
          :can-delete="false"
          :live="false"
          @play="openPlayer"
        />
      </div>
    </div>

    <!-- Coach: which session is this COACH_REVIEW about? -->
    <q-dialog v-model="subjectDialog">
      <q-card style="min-width: 320px; max-width: 480px">
        <q-card-section>
          <div class="text-subtitle1">{{ t('video.subject.pickerTitle') }}</div>
          <div class="text-caption text-secondary q-mt-xs">{{ t('video.subject.pickerHint') }}</div>
        </q-card-section>
        <q-card-section>
          <div v-if="subjectLoading" class="flex flex-center q-pa-md">
            <q-spinner-dots size="28px" color="primary" />
          </div>
          <div v-else-if="subjectLoadError" class="text-negative text-body2">
            {{ t('video.subject.loadError') }}
            <q-btn flat dense no-caps :label="t('common.retry')" @click="loadSubjectBookings" />
          </div>
          <div
            v-else-if="!subjectBookings.length"
            class="text-body2 text-secondary"
            data-testid="no-subject-bookings"
          >
            {{ t('video.subject.noSessions') }}
          </div>
          <q-select
            v-else
            v-model="subjectBookingId"
            :options="subjectOptions"
            emit-value
            map-options
            outlined
            dense
            :label="t('video.subject.label')"
            data-testid="subject-booking-select"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn v-close-popup flat :label="t('common.cancel')" />
          <q-btn
            color="primary"
            :label="t('video.subject.confirm')"
            :disable="!subjectBookingId"
            data-testid="subject-confirm-btn"
            @click="confirmSubject"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <VideoPlayDialog
      v-model="playerOpen"
      :video-id="playingVideo?.id ?? null"
      :title="playingVideo?.title ?? ''"
    />
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useRouter } from 'vue-router'
import { videoApi } from 'src/api/video.api'
import { useAuthStore } from 'src/stores/auth.store'
import VideoListItem from 'src/components/video/VideoListItem.vue'
import VideoPlayDialog from 'src/components/video/VideoPlayDialog.vue'
import QuotaUsageBar from 'src/components/video/QuotaUsageBar.vue'

const { t, locale } = useI18n()
const $q = useQuasar()
const router = useRouter()
const authStore = useAuthStore()

// Shared by the PLAYER route (player/videos) and the COACH route (coach/videos).
const isCoach = computed(() => authStore.isCoach)
const COACH_VIDEO_TYPES = ['COACH_REVIEW', 'DRILL_DEMO']
// Client-side pre-check for player HOMEWORK only; coach types have their own (larger, config-driven)
// server limits, which the initiate call reports as a validation error.
const HOMEWORK_MAX_BYTES = 500 * 1024 * 1024

const videos = ref([])
const videosLoading = ref(true)
const quota = ref({
  storageUsedBytes: 0,
  storageLimitBytes: 0,
  bandwidthUsedBytes: 0,
  bandwidthLimitBytes: 0,
})
const quotaLoading = ref(true)

const fileInputRef = ref(null)
const uploadState = ref('idle') // idle | initiating | uploading
const uploadProgress = ref(0)
const uploadBusy = computed(() => uploadState.value !== 'idle')
// What the next file selection is for: a new upload of `videoType`, or a retry of `retryVideo`.
let pendingSelection = null
const retryingVideoId = ref(null)

const playerOpen = ref(false)
const playingVideo = ref(null)

// Player only: coach videos about them (V184).
const coachVideos = ref([])
const coachVideosLoading = ref(false)
const coachVideosError = ref(false)

// Coach only: the COACH_REVIEW subject picker.
const subjectDialog = ref(false)
const subjectBookings = ref([])
const subjectLoading = ref(false)
const subjectLoadError = ref(false)
const subjectBookingId = ref(null)

function formatSessionSlot(iso, timezone) {
  try {
    return new Date(iso).toLocaleString(locale.value, {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
      timeZone: timezone || undefined,
    })
  } catch {
    // An unknown IANA zone throws a RangeError — fall back to the viewer's own zone.
    return new Date(iso).toLocaleString(locale.value)
  }
}

const subjectOptions = computed(() =>
  subjectBookings.value.map((b) => ({
    value: b.bookingId,
    label: `${formatSessionSlot(b.requestedStartTime, b.canonicalTimezone)} · ${
      b.playerName || t('video.subject.unknownPlayer')
    }`,
  })),
)

function homeRoute() {
  return isCoach.value ? '/coach/command-center' : '/player/dashboard'
}

async function fetchVideos() {
  videosLoading.value = true
  try {
    videos.value = await videoApi.getMyVideos()
  } catch (err) {
    if (err?.response?.status === 403) {
      $q.notify({ type: 'negative', message: t('video.management.accessDenied') })
      router.replace(homeRoute())
    } else {
      $q.notify({ type: 'negative', message: t('video.management.loadError') })
    }
  } finally {
    videosLoading.value = false
  }
}

async function fetchQuota() {
  quotaLoading.value = true
  try {
    quota.value = await videoApi.getMyQuota()
  } catch {
    // Quota display degrades silently; bar stays in loading state rather than crashing the page
  } finally {
    quotaLoading.value = false
  }
}

function onStatusChanged(videoId, newState) {
  // PURGED is translated to DELETED server-side (VideoSseService.onVideoPurged for SSE,
  // VideoEventResource.computeDisplayState for the polling fallback) — this branch is a
  // defensive backstop in case a raw PURGED ever reaches the client anyway.
  if (newState === 'DELETED' || newState === 'PURGED') {
    videos.value = videos.value.filter((v) => v.id !== videoId)
    fetchQuota()
    return
  }
  const video = videos.value.find((v) => v.id === videoId)
  if (video) video.operationalState = newState
}

async function fetchCoachVideos() {
  coachVideosLoading.value = true
  coachVideosError.value = false
  try {
    coachVideos.value = (await videoApi.getMyCoachVideos()) ?? []
  } catch {
    coachVideos.value = []
    coachVideosError.value = true
  } finally {
    coachVideosLoading.value = false
  }
}

async function loadSubjectBookings() {
  subjectLoading.value = true
  subjectLoadError.value = false
  try {
    subjectBookings.value = (await videoApi.listSubjectBookings()) ?? []
  } catch {
    subjectBookings.value = []
    subjectLoadError.value = true
  } finally {
    subjectLoading.value = false
  }
}

function openUpload(videoType) {
  if (uploadBusy.value) return
  if (isCoach.value && videoType === 'COACH_REVIEW') {
    // A session review must name the session it reviews (server-enforced); ask before the file.
    subjectBookingId.value = null
    subjectDialog.value = true
    loadSubjectBookings()
    return
  }
  pendingSelection = { videoType }
  fileInputRef.value?.click()
}

function confirmSubject() {
  if (!subjectBookingId.value) return
  pendingSelection = { videoType: 'COACH_REVIEW', subjectBookingId: subjectBookingId.value }
  subjectDialog.value = false
  fileInputRef.value?.click()
}

function startRetry(video) {
  if (uploadBusy.value) return
  // The retry re-uploads the file: the browser cannot re-read the original, so the owner re-picks it.
  pendingSelection = { retryVideo: video }
  fileInputRef.value?.click()
}

function openPlayer(video) {
  playingVideo.value = video
  playerOpen.value = true
}

function uploadErrorMessage(err) {
  const status = err?.response?.status
  const code = err?.response?.data?.errorMsg?.errorKey
  if (code === 'VIDEO_MINOR_UPLOAD_REFUSED') return t('video.management.minorUploadRefused')
  if (code === 'VIDEO_SUBJECT_INVALID') return t('video.management.subjectInvalid')
  if (status === 429) return t('video.management.quotaOrRateLimited')
  if (status === 409) return t('video.management.retryConflict')
  if (status === 422 || status === 400) return t('video.management.uploadRejected')
  return t('video.management.uploadError')
}

async function onFileSelected(event) {
  const file = event.target.files?.[0]
  // Reset the input so the same file can be re-selected after an error
  if (fileInputRef.value) fileInputRef.value.value = ''
  const selection = pendingSelection
  pendingSelection = null
  if (!file || !selection) return

  const isHomework = selection.retryVideo
    ? selection.retryVideo.videoType === 'HOMEWORK'
    : selection.videoType === 'HOMEWORK'
  if (isHomework && file.size > HOMEWORK_MAX_BYTES) {
    $q.notify({ type: 'negative', message: t('video.management.fileTooLarge') })
    return
  }

  uploadState.value = 'initiating'
  uploadProgress.value = 0
  const retryVideo = selection.retryVideo ?? null
  if (retryVideo) retryingVideoId.value = retryVideo.id

  try {
    let data
    if (retryVideo) {
      data = await videoApi.retryUpload(retryVideo.id, file.size)
      // The server moved the video FAILED→UPLOADING; reflect it so its card resumes live status.
      // Look the row up again: the list may have been reloaded while the request was in flight.
      const row = videos.value.find((v) => v.id === retryVideo.id)
      if (row) row.operationalState = 'UPLOADING'
    } else {
      const payload = {
        fileName: file.name,
        fileSizeBytes: file.size,
        mimeType: file.type,
        videoType: selection.videoType,
      }
      if (selection.subjectBookingId) payload.subjectBookingId = selection.subjectBookingId
      data = isCoach.value
        ? await videoApi.initiateUpload(payload)
        : await videoApi.initiatePlayerUpload(payload)
    }

    if (uploadState.value === 'idle') return

    uploadState.value = 'uploading'

    const upload = videoApi.createTusUpload({
      file,
      signedUploadUrl: data.signedUploadUrl,
      providerUploadId: data.providerUploadId,
      tusAuthorizationSignature: data.tusAuthorizationSignature,
      tusAuthorizationExpire: data.tusAuthorizationExpire,
      tusLibraryId: data.tusLibraryId,
      onProgress(bytesUploaded, bytesTotal) {
        uploadProgress.value = bytesTotal > 0 ? Math.round((bytesUploaded / bytesTotal) * 100) : 0
      },
      onSuccess() {
        uploadState.value = 'idle'
        retryingVideoId.value = null
        $q.notify({ type: 'positive', message: t('video.management.uploadSuccess') })
        fetchVideos()
        fetchQuota()
      },
      onError() {
        uploadState.value = 'idle'
        retryingVideoId.value = null
        $q.notify({ type: 'negative', message: t('video.management.uploadError') })
        // A failed transfer leaves the video for the server to mark FAILED again (session expiry);
        // re-read the list so the row shows whatever state it is really in.
        fetchVideos()
      },
    })

    upload.start()
  } catch (err) {
    uploadState.value = 'idle'
    retryingVideoId.value = null
    $q.notify({ type: 'negative', message: uploadErrorMessage(err) })
    if (retryVideo) fetchVideos()
  }
}

async function confirmDelete(videoId) {
  const confirmed = await new Promise((resolve) => {
    $q.dialog({
      title: t('video.management.deleteConfirmTitle'),
      message: t('video.management.deleteConfirm'),
      cancel: true,
      persistent: true,
    })
      .onOk(() => resolve(true))
      .onCancel(() => resolve(false))
  })
  if (!confirmed) return

  try {
    await videoApi.deleteVideo(videoId)
    videos.value = videos.value.filter((v) => v.id !== videoId)
    fetchQuota()
    $q.notify({ type: 'positive', message: t('video.management.deleteSuccess') })
  } catch (err) {
    if (err?.response?.status === 403) {
      $q.notify({ type: 'negative', message: t('video.management.accessDenied') })
    } else {
      $q.notify({ type: 'negative', message: t('video.management.deleteError') })
    }
  }
}

onMounted(async () => {
  const loads = [fetchVideos(), fetchQuota()]
  if (!isCoach.value) loads.push(fetchCoachVideos())
  await Promise.all(loads)
})
</script>
