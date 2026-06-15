<template>
  <q-page class="q-pa-md">
    <div class="row items-center q-mb-md">
      <q-btn flat round icon="arrow_back" @click="router.back()" />
      <div class="text-h6 q-ml-sm">
        {{ t('booking.requests.requestTitle', { coachName: coachName }) }}
      </div>
    </div>

    <SessionPackTracker :credits-remaining="creditsForCoach" :session-count="0" class="q-mb-md" />

    <q-banner v-if="!hasCredits" class="bg-warning text-white q-mb-md">
      {{ t('booking.requests.noCreditsWarning') }}
      <template #action>
        <q-btn flat :label="t('booking.packs.buySessions')" @click="goToPurchase" />
      </template>
    </q-banner>

    <q-card flat bordered class="q-mb-md">
      <q-card-section>
        <div class="text-subtitle1 q-mb-sm">{{ t('booking.requests.selectSlot') }}</div>

        <div v-if="bookingStore.loading" class="text-center q-py-md">
          <q-spinner size="32px" />
        </div>

        <q-list v-else-if="bookingStore.computedSlots.length > 0" bordered separator>
          <q-item
            v-for="slot in bookingStore.computedSlots"
            :key="slot.startTime"
            clickable
            :active="selectedSlot?.startTime === slot.startTime"
            active-class="bg-primary text-white"
            @click="selectSlot(slot)"
          >
            <q-item-section>
              <q-item-label>{{ formatSlot(slot.startTime) }}</q-item-label>
              <q-item-label caption>{{ formatSlot(slot.endTime) }}</q-item-label>
            </q-item-section>
          </q-item>
        </q-list>

        <div v-else class="text-body2 text-secondary q-py-md text-center">
          {{ t('booking.availability.noSlotsAvailable', 'No available slots this week') }}
        </div>
      </q-card-section>
    </q-card>

    <q-input
      v-model="notes"
      type="textarea"
      :label="t('booking.requests.notes')"
      outlined
      class="q-mb-md"
      maxlength="500"
    />

    <q-btn
      unelevated
      color="primary"
      class="full-width"
      :label="t('booking.requests.confirmRequest')"
      :loading="submitting"
      :disable="!canSubmit"
      @click="submit"
    />
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'
import { usePlayerStore } from 'src/stores/playerStore'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const bookingStore = useBookingStore()
const playerStore = usePlayerStore()

const coachId = route.params.coachId
const playerId = computed(() =>
  route.query.playerId ? Number(route.query.playerId) : playerStore.activePlayerId,
)
const coachName = ref(route.query.coachName ?? '')
const selectedSlot = ref(null)
const notes = ref('')
const submitting = ref(false)

const creditsForCoach = computed(() => bookingStore.creditsForCoach(coachId))
const hasCredits = computed(() => creditsForCoach.value > 0)

const canSubmit = computed(
  () => hasCredits.value && selectedSlot.value !== null && !submitting.value,
)

function selectSlot(slot) {
  selectedSlot.value = slot
}

function formatSlot(isoString) {
  return new Date(isoString).toLocaleString()
}

function goToPurchase() {
  router.push(`/parent/coaches/${coachId}/purchase-sessions?playerId=${playerId.value}`)
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await bookingStore.submitBookingRequest({
      coachId,
      playerId: playerId.value,
      requestedStartTime: selectedSlot.value.startTime,
      requestedEndTime: selectedSlot.value.endTime,
      canonicalTimezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      notes: notes.value || null,
    })
    router.push('/parent/bookings')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await bookingStore.loadAvailability(coachId)
  if (playerId.value) {
    await bookingStore.loadPlayerPacks(playerId.value)
  }
})
</script>
