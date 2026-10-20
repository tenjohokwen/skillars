<template>
  <div>
    <div class="text-subtitle1 text-weight-medium q-mb-md">
      {{ $t('development.timeline.title') }}
    </div>

    <q-inner-loading :showing="store.timelineLoading" />

    <q-banner
      v-if="store.timelineError && !store.timelineLoading"
      class="bg-negative text-white q-mb-md"
      rounded
    >
      <template #avatar><q-icon name="error" /></template>
      {{ store.timelineError }}
    </q-banner>

    <q-banner
      v-else-if="store.timeline?.accessExpired"
      class="bg-orange-1 text-orange-9 q-mb-md"
      rounded
    >
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
            {{
              $te(`development.timeline.eventType.${event.eventType}`)
                ? $t(`development.timeline.eventType.${event.eventType}`)
                : event.eventType
            }}
          </template>

          <dl
            v-if="
              event.eventType === 'SESSION_COMPLETED' &&
              (event.metadata?.whatWeDid?.trim() || event.metadata?.whatsNext?.trim())
            "
            class="q-mt-sm"
          >
            <div v-if="event.metadata?.whatWeDid?.trim()">
              <dt class="text-caption text-weight-bold">
                {{ $t('development.timeline.whatWeDidLabel') }}
              </dt>
              <dd class="text-body2 recap-text">{{ event.metadata.whatWeDid }}</dd>
            </div>
            <div v-if="event.metadata?.whatsNext?.trim()">
              <dt class="text-caption text-weight-bold q-mt-xs">
                {{ $t('development.timeline.whatsNextLabel') }}
              </dt>
              <dd class="text-body2 recap-text">{{ event.metadata.whatsNext }}</dd>
            </div>
          </dl>
        </q-timeline-entry>
      </q-timeline>

      <div v-else class="text-body2 text-grey-6 q-pa-sm">
        {{ $t('development.timeline.noEvents') }}
      </div>

      <div v-if="store.timeline.hasMore" class="text-center q-mt-md">
        <q-btn
          flat
          color="primary"
          :loading="store.timelineLoadingMore"
          :label="$t('development.timeline.loadMore')"
          @click="loadMore"
        />

        <!-- skillars-deferred-153 code review: a failed "Load more" renders INLINE here, inside
             the events branch, so the already-loaded timeline above stays on screen. It used to
             set store.timelineError, which the first banner branch above renders in place of the
             whole list — taking the retry button down with it and leaving no way back. Retrying
             is just loadMore() again: the store cleared timelineLoadMoreError on entry. -->
        <div v-if="store.timelineLoadMoreError" class="text-caption text-negative q-mt-sm">
          {{ store.timelineLoadMoreError }}
          <q-btn
            flat
            dense
            no-caps
            size="sm"
            color="primary"
            :label="$t('development.timeline.loadMoreRetry')"
            @click="loadMore"
          />
        </div>
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

function loadMore() {
  store.fetchTimeline(props.playerId, store.timelinePage + 1)
}

onMounted(() => {
  if (!props.playerId) return
  store.fetchTimeline(props.playerId)
})
</script>

<style scoped>
.recap-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* skillars-deferred-155 AC11: UA default margins on dl/dt/dd (dd: margin-inline-start: 40px;
   dl: margin-block: 1em) would otherwise indent every recap value and add unwanted vertical
   space — this codebase has no dl/dt/dd reset anywhere, including Quasar's own normalize sheet. */
/* skillars-deferred-155 code review: :where() so the reset carries ZERO specificity. Written as a
   bare `dl, dt, dd` selector it compiled to `dl[data-v-hash]` — specificity (0,1,1) — which beats
   Quasar's own `.q-mt-sm` / `.q-mt-xs` utilities at (0,1,0) regardless of source order, silently
   killing the 8px and 4px spacing still declared on this very markup. :where() keeps the UA margins
   off while leaving the utility classes authoritative. */
:where(dl, dt, dd) {
  margin: 0;
}
</style>
