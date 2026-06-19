<template>
  <div>
    <div class="text-subtitle1 text-weight-medium q-mb-md">{{ $t('development.timeline.title') }}</div>

    <q-inner-loading :showing="store.timelineLoading" />

    <q-banner v-if="store.timelineError && !store.timelineLoading" class="bg-negative text-white q-mb-md" rounded>
      <template #avatar><q-icon name="error" /></template>
      {{ store.timelineError }}
    </q-banner>

    <q-banner v-else-if="store.timeline?.accessExpired" class="bg-orange-1 text-orange-9 q-mb-md" rounded>
      <template #avatar>
        <q-icon name="lock_clock" />
      </template>
      {{ $t('development.timeline.accessExpired', { days: store.timeline.accessExpiryDays }) }}
    </q-banner>

    <template v-else-if="store.timeline && !store.timelineLoading && !store.timelineError">
      <q-timeline v-if="store.timeline.events.length" color="primary">
        <q-timeline-entry
          v-for="event in store.timeline.events"
          :key="event.id"
          :icon="eventIcon(event.eventType)"
          :color="eventColour(event.eventType)"
          :subtitle="formatDate(event.occurredAt)"
        >
          <template #title>
            {{ $te(`development.timeline.eventType.${event.eventType}`) ? $t(`development.timeline.eventType.${event.eventType}`) : event.eventType }}
          </template>
        </q-timeline-entry>
      </q-timeline>

      <div v-else class="text-body2 text-grey-6 q-pa-sm">
        {{ $t('development.timeline.noEvents') }}
      </div>
    </template>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { date } from 'quasar'
import { useDevelopmentStore } from 'src/stores/development.store'

const props = defineProps({
  playerId: { type: [Number, String], required: true },
})

const store = useDevelopmentStore()

const ICON_MAP = {
  SESSION_COMPLETED: 'sports',
  RADAR_ASSESSMENT: 'radar',
  PERFORMANCE_REPORT: 'picture_as_pdf',
  MILESTONE_REACHED: 'emoji_events',
  HOMEWORK_ASSIGNED: 'assignment',
  VIDEO_UPLOADED: 'videocam',
  PAYMENT_RECEIVED: 'payments',
  REVIEW_LEFT: 'rate_review',
}

const COLOUR_MAP = {
  SESSION_COMPLETED: 'primary',
  RADAR_ASSESSMENT: 'teal',
  PERFORMANCE_REPORT: 'deep-purple',
  MILESTONE_REACHED: 'amber',
  HOMEWORK_ASSIGNED: 'blue',
  VIDEO_UPLOADED: 'cyan',
  PAYMENT_RECEIVED: 'green',
  REVIEW_LEFT: 'pink',
}

function eventIcon(type) {
  return ICON_MAP[type] ?? 'circle'
}

function eventColour(type) {
  return COLOUR_MAP[type] ?? 'grey'
}

function formatDate(isoString) {
  return date.formatDate(isoString, 'DD MMM YYYY HH:mm')
}

onMounted(() => {
  if (!props.playerId) return
  store.fetchTimeline(props.playerId)
})
</script>
