<template>
  <q-page class="q-pa-md">
    <div class="q-mb-md">
      <div class="text-h5">{{ t('video.management.title') }}</div>
    </div>

    <!-- Quota bar -->
    <QuotaUsageBar
      class="q-mb-lg"
      :storage-used-bytes="quota.storageUsedBytes"
      :storage-limit-bytes="quota.storageLimitBytes"
      :bandwidth-used-bytes="quota.bandwidthUsedBytes"
      :bandwidth-limit-bytes="quota.bandwidthLimitBytes"
      :loading="quotaLoading"
    />

    <!-- Upload button — shown to all authenticated players regardless of age;
         the minor safety gate (moderation Layer 3) enforces parental approval after content moderation -->
    <div class="q-mb-md">
      <q-btn
        color="primary"
        icon="upload"
        :label="t('video.management.uploadButton')"
        :loading="uploadState === 'initiating' || uploadState === 'uploading'"
        @click="openUpload"
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
      style="display:none"
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
      <div v-for="video in videos" :key="video.id" class="row items-start no-wrap">
        <VideoStatusCard
          class="col"
          :video-id="video.id"
          :initial-status="video.operationalState"
          @status-changed="onStatusChanged(video.id, $event)"
        />
        <q-btn
          flat
          round
          icon="delete"
          color="negative"
          class="q-ml-sm q-mt-xs"
          :aria-label="t('video.management.deleteAriaLabel')"
          @click="confirmDelete(video.id)"
        />
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useRouter } from 'vue-router'
import { videoApi } from 'src/api/video.api'
import VideoStatusCard from 'src/components/video/VideoStatusCard.vue'
import QuotaUsageBar from 'src/components/video/QuotaUsageBar.vue'

const { t } = useI18n()
const $q = useQuasar()
const router = useRouter()

const videos = ref([])
const videosLoading = ref(true)
const quota = ref({ storageUsedBytes: 0, storageLimitBytes: 0, bandwidthUsedBytes: 0, bandwidthLimitBytes: 0 })
const quotaLoading = ref(true)

const fileInputRef = ref(null)
const uploadState = ref('idle') // idle | initiating | uploading | processing | error
const uploadProgress = ref(0)

async function fetchVideos() {
  videosLoading.value = true
  try {
    const { data } = await videoApi.getMyVideos()
    videos.value = data
  } catch (err) {
    if (err?.response?.status === 403) {
      $q.notify({ type: 'negative', message: t('video.management.accessDenied') })
      router.replace('/dashboard')
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
    const { data } = await videoApi.getMyQuota()
    quota.value = data
  } catch {
    // Quota display degrades silently; bar stays in loading state rather than crashing the page
  } finally {
    quotaLoading.value = false
  }
}

function onStatusChanged(videoId, newState) {
  if (newState === 'DELETED' || newState === 'PURGED') {
    videos.value = videos.value.filter((v) => v.id !== videoId)
    fetchQuota()
    return
  }
  const video = videos.value.find((v) => v.id === videoId)
  if (video) video.operationalState = newState
}

function openUpload() {
  if (uploadState.value !== 'idle') return
  fileInputRef.value?.click()
}

async function onFileSelected(event) {
  const file = event.target.files?.[0]
  // Reset the input so the same file can be re-selected after an error
  if (fileInputRef.value) fileInputRef.value.value = ''
  if (!file) return

  const MAX_BYTES = 500 * 1024 * 1024
  if (file.size > MAX_BYTES) {
    $q.notify({ type: 'negative', message: t('video.management.fileTooLarge') })
    return
  }

  uploadState.value = 'initiating'
  uploadProgress.value = 0

  try {
    const { data } = await videoApi.initiatePlayerUpload({
      fileName: file.name,
      fileSizeBytes: file.size,
      mimeType: file.type,
      videoType: 'HOMEWORK',
    })

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
        $q.notify({ type: 'positive', message: t('video.management.uploadSuccess') })
        fetchVideos()
        fetchQuota()
      },
      onError() {
        uploadState.value = 'idle'
        $q.notify({ type: 'negative', message: t('video.management.uploadError') })
      },
    })

    upload.start()
  } catch {
    uploadState.value = 'idle'
    $q.notify({ type: 'negative', message: t('video.management.uploadError') })
  }
}

async function confirmDelete(videoId) {
  const confirmed = await new Promise((resolve) => {
    $q.dialog({
      title: t('video.management.deleteConfirmTitle'),
      message: t('video.management.deleteConfirm'),
      cancel: true,
      persistent: true,
    }).onOk(() => resolve(true)).onCancel(() => resolve(false))
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
  await Promise.all([fetchVideos(), fetchQuota()])
})

onUnmounted(() => {
  // VideoStatusCard components clean up their own SSE connections via onUnmounted in the card
})
</script>
