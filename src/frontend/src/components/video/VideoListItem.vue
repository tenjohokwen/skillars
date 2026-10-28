<template>
  <div class="video-list-item row items-start no-wrap" data-testid="video-list-item">
    <div class="col">
      <div v-if="video.title" class="text-caption text-secondary ellipsis q-mb-xs">
        {{ video.title }}
      </div>
      <VideoStatusCard
        :video-id="video.id"
        :initial-status="video.operationalState"
        :live="live"
        @status-changed="emit('status-changed', $event)"
      />
    </div>
    <div class="column q-ml-sm q-mt-lg q-gutter-xs">
      <q-btn
        v-if="video.operationalState === 'READY'"
        flat
        round
        icon="play_circle"
        color="primary"
        data-testid="video-play-btn"
        :aria-label="t('video.player.playAriaLabel')"
        @click="emit('play', video)"
      />
      <q-btn
        v-if="canRetry && video.operationalState === 'FAILED'"
        flat
        round
        icon="replay"
        color="warning"
        data-testid="video-retry-btn"
        :loading="retrying"
        :aria-label="t('video.management.retryAriaLabel')"
        @click="emit('retry', video)"
      />
      <q-btn
        v-if="canDelete"
        flat
        round
        icon="delete"
        color="negative"
        data-testid="video-delete-btn"
        :aria-label="t('video.management.deleteAriaLabel')"
        @click="emit('delete', video)"
      />
    </div>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import VideoStatusCard from 'src/components/video/VideoStatusCard.vue'

/**
 * One row of a video list: status card plus the actions the viewer may take on it. Shared by the
 * owner's own list (VideoManagementPage — play / retry / delete) and a parent's child-videos list
 * (ParentChildVideosPage — play / delete). Play is offered only for READY videos: the backend's
 * playback eligibility is READY + ACTIVE, and anything else would just 4xx.
 */
defineProps({
  video: { type: Object, required: true },
  canRetry: { type: Boolean, default: false },
  canDelete: { type: Boolean, default: true },
  retrying: { type: Boolean, default: false },
  // Live status updates (SSE) are owner-only server-side; a non-owner list passes false.
  live: { type: Boolean, default: true },
})
const emit = defineEmits(['play', 'retry', 'delete', 'status-changed'])

const { t } = useI18n()
</script>
