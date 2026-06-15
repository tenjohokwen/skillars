<template>
  <q-page class="q-pa-md">
    <TimezoneNotice
      v-if="firstBookingTimezone && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="firstBookingTimezone"
    />

    <div class="text-h5 q-mb-md">{{ t('booking.requests.listTitle') }}</div>

    <div v-if="bookingStore.bookingsLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div
      v-else-if="bookingStore.parentBookings.length === 0"
      class="flex flex-center column q-gutter-md q-py-xl"
      style="min-height: 40vh"
    >
      <q-icon name="calendar_today" size="64px" style="color: var(--text-secondary)" />
      <div class="text-body1" style="color: var(--text-secondary)">
        {{ t('booking.requests.emptyState') }}
      </div>
      <q-btn
        unelevated
        color="primary"
        :label="t('booking.requests.emptyStateCta')"
        to="/marketplace"
      />
    </div>

    <q-list v-else bordered separator>
      <q-item v-for="booking in bookingStore.parentBookings" :key="booking.id" class="q-py-md">
        <q-item-section>
          <q-item-label class="text-weight-bold">{{ booking.coachDisplayName }}</q-item-label>
          <q-item-label caption
            >{{ t('player.nameLabel', 'Player') }}: {{ booking.playerName }}</q-item-label
          >
          <q-item-label caption>{{
            showInMyTime[booking.id]
              ? formatDateTime(booking.requestedStartTime, browserTimezone)
              : formatDateTime(booking.requestedStartTime, booking.canonicalTimezone)
          }}</q-item-label>
          <q-btn
            flat dense
            class="self-start q-pa-none"
            :label="showInMyTime[booking.id] ? t('booking.timezone.showInSessionTime') : t('booking.timezone.showInMyTime')"
            @click="toggleTimezone(booking.id)"
          />
          <q-item-label caption class="q-mt-xs">
            {{
              t('booking.requests.creditsRemaining', { count: booking.effectiveCreditsRemaining })
            }}
          </q-item-label>
        </q-item-section>
        <q-item-section side>
          <BookingStateChip :status="booking.status" />
        </q-item-section>
      </q-item>
    </q-list>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'

const { t } = useI18n()
const bookingStore = useBookingStore()
const authStore = useAuthStore()

const showInMyTime = ref({})
const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

const firstBookingTimezone = computed(() => {
  return bookingStore.parentBookings[0]?.canonicalTimezone ?? null
})

function toggleTimezone(bookingId) {
  showInMyTime.value[bookingId] = !showInMyTime.value[bookingId]
}

function formatDateTime(isoString, timezone) {
  return new Intl.DateTimeFormat('en', {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(isoString))
}

onMounted(() => {
  bookingStore.loadParentBookings()
})
</script>
