<template>
  <q-page class="q-pa-md">
    <TimezoneNotice
      v-if="firstBookingTimezone && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="firstBookingTimezone"
    />

    <div class="row items-center justify-between q-mb-md">
      <div class="text-h5">{{ t('booking.timezone.parentScheduleTitle') }}</div>
      <q-btn
        flat
        icon="sports_soccer"
        :to="{ name: 'player-locker-room', params: { playerId: route.params.playerId } }"
        :label="t('player.viewLockerRoom')"
      />
    </div>

    <div v-if="bookingStore.parentScheduleLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div
      v-else-if="!bookingStore.parentSchedule || bookingStore.parentSchedule.sessions.length === 0"
      class="flex flex-center column q-gutter-md q-py-xl"
      style="min-height: 40vh"
    >
      <q-icon name="event_busy" size="64px" style="color: var(--text-secondary)" />
      <div class="text-body1" style="color: var(--text-secondary)">
        {{ t('booking.timezone.parentScheduleEmpty') }}
      </div>
    </div>

    <q-list v-else bordered separator>
      <q-item v-for="session in bookingStore.parentSchedule.sessions" :key="session.bookingId" class="q-py-md">
        <q-item-section>
          <q-item-label class="text-weight-bold">{{ session.coachDisplayName }}</q-item-label>
          <q-item-label caption>
            {{ formatInTz(session.requestedStartTime, session.canonicalTimezone) }}
          </q-item-label>
          <div class="q-mt-sm">
            <SessionPackTracker
              :credits-remaining="session.effectiveCreditsRemaining"
              :session-count="sessionCountFor(session)"
            />
          </div>
        </q-item-section>
        <q-item-section side>
          <BookingStateChip :status="session.status" />
        </q-item-section>
      </q-item>
    </q-list>
  </q-page>
</template>

<script setup>
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'

const { t } = useI18n()
const route = useRoute()
const bookingStore = useBookingStore()
const authStore = useAuthStore()
const playerStore = usePlayerStore()

const playerId = Number(route.params.playerId)

async function loadForPlayer(id) {
  await bookingStore.loadParentSchedule(id)
  await bookingStore.loadPlayerPacks(id)
}

onMounted(() => loadForPlayer(playerId))

watch(() => playerStore.activePlayerId, (newId) => {
  if (newId && newId !== playerId) {
    loadForPlayer(newId)
  }
})

const firstBookingTimezone = computed(() => {
  return bookingStore.parentSchedule?.sessions?.[0]?.canonicalTimezone ?? null
})

function formatInTz(isoString, timezone) {
  return new Intl.DateTimeFormat('en', {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(isoString))
}

function sessionCountFor(session) {
  const pack = bookingStore.sessionPacks.find(
    (p) => p.coachId === session.coachId && p.status === 'ACTIVE',
  )
  return pack ? pack.sessionCount : 0
}
</script>
