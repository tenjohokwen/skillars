<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">{{ t('booking.requests.coachInboxTitle') }}</div>

    <div v-if="bookingStore.coachRequestsLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div
      v-else-if="bookingStore.coachBookingRequests.length === 0"
      class="flex flex-center column q-gutter-md q-py-xl"
      style="min-height: 40vh"
    >
      <q-icon name="inbox" size="64px" style="color: var(--text-secondary)" />
      <div class="text-body1" style="color: var(--text-secondary)">
        {{ t('booking.requests.coachInboxEmpty') }}
      </div>
    </div>

    <q-list v-else bordered separator>
      <q-item
        v-for="booking in bookingStore.coachBookingRequests"
        :key="booking.id"
        class="q-py-md"
      >
        <q-item-section>
          <q-item-label class="text-weight-bold">{{ booking.playerName }}</q-item-label>
          <q-item-label caption>{{ t('booking.requests.parentLabel', { name: booking.parentName }) }}</q-item-label>
          <q-item-label caption>
            {{ formatDateTime(booking.requestedStartTime, booking.canonicalTimezone) }}
          </q-item-label>
          <q-item-label v-if="booking.notes" caption class="q-mt-xs">
            <q-icon name="notes" size="14px" class="q-mr-xs" />{{ booking.notes }}
          </q-item-label>
        </q-item-section>
        <q-item-section side>
          <div class="row q-gutter-sm">
            <q-btn
              unelevated
              color="primary"
              size="sm"
              :label="t('booking.requests.accept')"
              :loading="accepting[booking.id]"
              @click="handleAccept(booking.id)"
            />
            <q-btn
              flat
              color="negative"
              size="sm"
              :label="t('booking.requests.decline')"
              :loading="declining[booking.id]"
              @click="handleDecline(booking.id)"
            />
          </div>
        </q-item-section>
      </q-item>
    </q-list>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'

const { t } = useI18n()
const bookingStore = useBookingStore()

const accepting = ref({})
const declining = ref({})

function formatDateTime(isoString, timezone) {
  return new Date(isoString).toLocaleString('en', { timeZone: timezone })
}

async function handleAccept(id) {
  accepting.value[id] = true
  try {
    await bookingStore.approveBooking(id)
  } finally {
    accepting.value[id] = false
  }
}

async function handleDecline(id) {
  declining.value[id] = true
  try {
    await bookingStore.rejectBooking(id)
  } finally {
    declining.value[id] = false
  }
}

onMounted(() => {
  bookingStore.loadCoachBookingRequests()
})
</script>
