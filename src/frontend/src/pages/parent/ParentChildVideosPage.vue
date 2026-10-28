<template>
  <q-page class="q-pa-md">
    <q-btn
      flat
      dense
      no-caps
      icon="arrow_back"
      color="primary"
      class="q-mb-md"
      :label="t('video.parent.backToChild')"
      :to="`/parent/players/${playerId}/sessions`"
    />

    <div class="text-h5 q-mb-xs">{{ t('video.parent.title') }}</div>
    <div v-if="childName" class="text-subtitle2 text-secondary q-mb-md" data-testid="child-name">
      {{ childName }}
    </div>
    <div class="text-body2 text-secondary q-mb-md">{{ t('video.parent.subtitle') }}</div>

    <div v-if="loading" class="q-gutter-md">
      <q-skeleton v-for="i in 3" :key="i" type="QCard" height="80px" />
    </div>

    <div v-else-if="loadError" class="text-negative" data-testid="child-videos-error">
      {{ t('video.parent.loadError') }}
      <q-btn flat dense no-caps color="primary" :label="t('common.retry')" @click="load" />
    </div>

    <div v-else-if="videos.length === 0" class="text-secondary" data-testid="child-videos-empty">
      {{ t('video.parent.emptyState') }}
    </div>

    <div v-else class="q-gutter-md">
      <VideoListItem
        v-for="video in videos"
        :key="video.id"
        :video="video"
        :live="false"
        @status-changed="onStatusChanged(video.id, $event)"
        @play="openPlayer"
        @delete="confirmDelete(video.id)"
      />
    </div>

    <VideoPlayDialog
      v-model="playerOpen"
      :video-id="playingVideo?.id ?? null"
      :title="playingVideo?.title ?? ''"
    />
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { videoApi } from 'src/api/video.api'
import { usePlayerStore } from 'src/stores/playerStore'
import VideoListItem from 'src/components/video/VideoListItem.vue'
import VideoPlayDialog from 'src/components/video/VideoPlayDialog.vue'

// A parent's view of one linked child's videos (GET /api/video/players/{playerId}/videos, guarded by
// playerOwnershipGuard). Deletion reuses DELETE /api/video/{id}, whose videoAccessGuard.canDelete
// already admits the parent. No upload or retry here: those stay with the video's owner.
const { t } = useI18n()
const $q = useQuasar()
const route = useRoute()
const playerStore = usePlayerStore()

// Raw route-param string — player ids are Tsids beyond Number.MAX_SAFE_INTEGER.
const playerId = computed(() => route.params.playerId)
const childName = computed(
  () =>
    (playerStore.players ?? []).find((p) => String(p.id) === String(playerId.value))?.name ?? null,
)

const videos = ref([])
const loading = ref(true)
const loadError = ref(false)
const playerOpen = ref(false)
const playingVideo = ref(null)
let loadSeq = 0

async function load() {
  const id = playerId.value
  if (!id) return
  const seq = ++loadSeq
  loading.value = true
  loadError.value = false
  try {
    const data = await videoApi.getPlayerVideos(id)
    if (seq === loadSeq) videos.value = data ?? []
  } catch {
    if (seq === loadSeq) {
      videos.value = []
      loadError.value = true
    }
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function onStatusChanged(videoId, newState) {
  if (newState === 'DELETED' || newState === 'PURGED') {
    videos.value = videos.value.filter((v) => v.id !== videoId)
    return
  }
  const video = videos.value.find((v) => v.id === videoId)
  if (video) video.operationalState = newState
}

function openPlayer(video) {
  playingVideo.value = video
  playerOpen.value = true
}

async function confirmDelete(videoId) {
  const confirmed = await new Promise((resolve) => {
    $q.dialog({
      title: t('video.management.deleteConfirmTitle'),
      message: t('video.parent.deleteConfirm'),
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
    $q.notify({ type: 'positive', message: t('video.management.deleteSuccess') })
  } catch (err) {
    const status = err?.response?.status
    $q.notify({
      type: 'negative',
      message:
        status === 403 ? t('video.deletionNotAuthorised') : t('video.management.deleteError'),
    })
  }
}

onMounted(load)

// Param-only navigation between children reuses this component.
watch(
  () => route.params.playerId,
  (newId, oldId) => {
    if (newId && newId !== oldId) load()
  },
)
</script>
