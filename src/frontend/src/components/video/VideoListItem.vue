<template>
  <div class="video-list-item row items-start no-wrap" data-testid="video-list-item">
    <div class="col">
      <div
        v-if="video.title"
        class="text-caption text-secondary ellipsis q-mb-xs"
        data-testid="video-title"
      >
        {{ video.title }}
      </div>
      <q-chip
        v-if="unavailable"
        dense
        square
        color="grey-7"
        text-color="white"
        icon="block"
        class="q-mb-xs"
        data-testid="video-access-state"
        :data-state="video.accessState"
      >
        {{
          video.accessState === 'ARCHIVED' ? t('video.access.ARCHIVED') : t('video.access.BLOCKED')
        }}
      </q-chip>
      <VideoStatusCard
        :video-id="video.id"
        :initial-status="video.operationalState"
        :live="live"
        @status-changed="emit('status-changed', $event)"
      />
    </div>
    <div class="column q-ml-sm q-mt-lg q-gutter-xs">
      <q-btn
        v-if="video.operationalState === 'READY' && !unavailable"
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
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import VideoStatusCard from 'src/components/video/VideoStatusCard.vue'

/**
 * One row of a video list: status card plus the actions the viewer may take on it. Shared by the
 * owner's own list (VideoManagementPage — play / retry / delete) and a parent's child-videos list
 * (ParentChildVideosPage — play / delete). Play is offered only for READY videos: the backend's
 * playback eligibility is READY + ACTIVE, and anything else would just 4xx.
 */
const props = defineProps({
  video: { type: Object, required: true },
  canRetry: { type: Boolean, default: false },
  canDelete: { type: Boolean, default: true },
  retrying: { type: Boolean, default: false },
  // Live status updates (SSE) are owner-only server-side; a non-owner list passes false.
  live: { type: Boolean, default: true },
})
const emit = defineEmits(['play', 'retry', 'delete', 'status-changed'])

const { t } = useI18n()

// Pitchside ADM-11: a video an admin blocked (or the lifecycle archived) is not playable, so it shows why and
// offers no Play. An absent accessState (older payloads) counts as ACTIVE.
const unavailable = computed(() => (props.video.accessState ?? 'ACTIVE') !== 'ACTIVE')
</script>
