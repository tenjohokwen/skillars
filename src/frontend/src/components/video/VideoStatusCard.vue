<template>
  <q-card v-if="statusConfig" class="video-status-card glass-card" role="region" :aria-label="t('video.statusCard.title')" aria-live="polite">
    <q-card-section class="row items-center q-gutter-sm">
      <q-icon :name="statusConfig.icon" :color="statusConfig.color" size="1.5rem" aria-hidden="true" />
      <div>
        <div class="text-caption text-secondary">{{ t('video.statusCard.title') }}</div>
        <div
          class="text-subtitle2"
          :aria-atomic="true"
        >
          {{ statusLabel }}
        </div>
      </div>
      <q-space />
      <q-spinner v-if="statusConfig.spinning" color="primary" size="1.2rem" aria-hidden="true" />
      <q-icon v-else-if="statusConfig.done" name="check_circle" color="positive" size="1.2rem" aria-hidden="true" />
      <q-icon v-else-if="statusConfig.error" name="error" color="negative" size="1.2rem" aria-hidden="true" />
    </q-card-section>
  </q-card>
  <template v-else><!-- DELETED: parent removes this card; nothing rendered --></template>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useVideoStatusSse } from 'src/stores/video.store'

const props = defineProps({
  videoId: { type: String, required: true },
  initialStatus: { type: String, default: 'UPLOADING' },
})

const emit = defineEmits(['status-changed'])

const { t } = useI18n()

// SUBSCRIPTION_LOCKED and ARCHIVED are reversible — do NOT add to TERMINAL_STATES.
// A subscription-locked video can return to ACTIVE on renewal; polling must continue.
// ARCHIVED is defensive — SSE is inactive after READY so this state will not arrive via SSE.
const TERMINAL_STATES = new Set(['READY', 'LOCKED', 'HIDDEN', 'FAILED', 'DELETED', 'ARCHIVED'])

const currentStatus = ref(props.initialStatus)

const statusConfigs = {
  UPLOADING:           { icon: 'upload',        color: 'primary',  spinning: true  },
  PROCESSING:          { icon: 'hourglass_top', color: 'primary',  spinning: true  },
  SCANNING:            { icon: 'security',      color: 'warning',  spinning: true  },
  TRANSCODING:         { icon: 'video_settings',color: 'primary',  spinning: true  },
  READY:               { icon: 'check_circle',  color: 'positive', done: true      },
  LOCKED:              { icon: 'lock',          color: 'negative', error: true     },
  HIDDEN:              { icon: 'visibility_off',color: 'warning'                   },
  FAILED:              { icon: 'error',         color: 'negative', error: true     },
  // DELETED: no config — card is removed from parent list immediately on DELETED; never rendered
  SUBSCRIPTION_LOCKED: { icon: 'lock_clock',    color: 'warning',  error: true     },
  ARCHIVED:            { icon: 'archive',       color: 'grey'                      },
}

// Guard: when displayState="DELETED" arrives, the parent removes the card before this renders.
// During the narrow window between the poll returning DELETED and the DOM removal,
// statusConfigs[DELETED] is undefined — v-if="statusConfig" prevents a TypeError.
const statusConfig = computed(() => statusConfigs[currentStatus.value] ?? null)
const statusLabel = computed(() => t(`video.status.${currentStatus.value}`, t('video.status.unknown')))

let sseHandle = null

function startSse(vid) {
  if (sseHandle) {
    sseHandle.stop()
    sseHandle = null
  }
  if (TERMINAL_STATES.has(currentStatus.value)) return
  sseHandle = useVideoStatusSse(vid, {
    onStatusChange(state) {
      currentStatus.value = state
      // Emit displayState so parent can act on DELETED without knowing internal state fields
      emit('status-changed', state)
    },
    onTerminal() {
      sseHandle = null
    },
  })
}

watch([() => props.videoId, () => props.initialStatus], ([vid, status]) => {
  currentStatus.value = status
  startSse(vid)
}, { immediate: true })

onUnmounted(() => {
  if (sseHandle) sseHandle.stop()
})
</script>

<style scoped>
.video-status-card {
  min-width: 220px;
}
</style>
