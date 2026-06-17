<template>
  <q-page class="q-pa-md">
    <div class="row items-center q-mb-md">
      <q-btn flat round icon="arrow_back" @click="router.back()" />
      <div class="text-h6 q-ml-sm">
        {{ t('booking.requests.requestTitle', { coachName: coachName }) }}
      </div>
      <q-space />
      <q-btn
        flat
        dense
        size="sm"
        :label="batchMode ? t('booking.batch.exitBatchMode') : t('booking.batch.enterBatchMode')"
        @click="toggleBatchMode"
      />
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
            :disable="
              batchMode
                ? (!bookingStore.isSlotInBasket(slot.startTime) && batchAtMax) ||
                  bookedStartTimes.has(slot.startTime)
                : bookedStartTimes.has(slot.startTime)
            "
            :active="
              batchMode
                ? bookingStore.isSlotInBasket(slot.startTime)
                : selectedSlot?.startTime === slot.startTime
            "
            active-class="bg-primary text-white"
            @click="batchMode ? toggleSlotInBasket(slot) : selectSlot(slot)"
          >
            <q-item-section>
              <q-item-label>{{ formatSlot(slot.startTime) }}</q-item-label>
              <q-item-label caption>{{ formatSlot(slot.endTime) }}</q-item-label>
            </q-item-section>
            <q-item-section v-if="batchMode && bookingStore.isSlotInBasket(slot.startTime)" side>
              <q-chip dense color="positive" text-color="white" size="sm">{{
                t('booking.batch.added')
              }}</q-chip>
            </q-item-section>
          </q-item>
        </q-list>

        <div v-else class="text-body2 text-secondary q-py-md text-center">
          {{ t('booking.availability.noSlotsAvailable', 'No available slots this week') }}
        </div>
      </q-card-section>
    </q-card>

    <!-- Batch basket summary bar -->
    <div
      v-if="batchMode && bookingStore.batchBasketSize > 0"
      class="q-pa-sm q-mt-sm"
      style="border: 1px solid var(--border-color); border-radius: 8px"
    >
      <div class="text-caption">
        {{
          t('booking.batch.selectedCount', {
            n: bookingStore.batchBasketSize,
            max: maxBatchSize,
          })
        }}
      </div>
      <q-btn
        unelevated
        color="primary"
        class="full-width q-mt-sm"
        :label="t('booking.batch.reviewRequests')"
        @click="batchReviewOpen = true"
      />
    </div>

    <!-- Single-booking mode inputs -->
    <template v-if="!batchMode">
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
    </template>

    <!-- Batch review dialog -->
    <q-dialog v-model="batchReviewOpen">
      <q-card style="min-width: 340px; max-width: 90vw">
        <q-card-section>
          <div class="text-h6">{{ t('booking.batch.reviewTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-list separator>
            <q-item
              v-for="slot in bookingStore.batchBasket"
              :key="slot.startTime"
              class="q-py-sm"
            >
              <q-item-section>
                <q-item-label>{{ formatSlot(slot.startTime) }}</q-item-label>
                <q-item-label caption>{{ formatSlot(slot.endTime) }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  round
                  dense
                  icon="close"
                  size="sm"
                  @click="bookingStore.removeSlotFromBasket(slot.startTime)"
                />
              </q-item-section>
            </q-item>
          </q-list>
          <div class="q-mt-md text-caption" style="color: var(--text-secondary)">
            {{
              t('booking.batch.creditPreview', {
                credits: creditsForCoach,
                count: bookingStore.batchBasketSize,
              })
            }}
          </div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            :label="t('booking.batch.confirmRequests')"
            :loading="bookingStore.batchSubmitting"
            :disable="bookingStore.batchBasketSize === 0"
            @click="submitBatchRequest"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'
import { usePlayerStore } from 'src/stores/playerStore'
import { getBatchConfig } from 'src/api/booking.api'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const $q = useQuasar()
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

const batchMode = ref(false)
const batchReviewOpen = ref(false)
const maxBatchSize = ref(5) // populated from backend on mount

const creditsForCoach = computed(() => bookingStore.creditsForCoach(coachId))
const hasCredits = computed(() => creditsForCoach.value > 0)
const batchAtMax = computed(() => bookingStore.batchBasketSize >= maxBatchSize.value)

const ACTIVE_BOOKING_STATUSES = new Set(['REQUESTED', 'ACCEPTED', 'CONFIRMED', 'UPCOMING', 'IN_PROGRESS'])
const bookedStartTimes = computed(
  () =>
    new Set(
      bookingStore.parentBookings
        .filter(
          (b) =>
            b.coachId === coachId &&
            b.playerId === playerId.value &&
            ACTIVE_BOOKING_STATUSES.has(b.status),
        )
        .map((b) => b.requestedStartTime),
    ),
)

const canSubmit = computed(
  () => hasCredits.value && selectedSlot.value !== null && !submitting.value,
)

function selectSlot(slot) {
  selectedSlot.value = slot
}

function toggleSlotInBasket(slot) {
  if (bookingStore.isSlotInBasket(slot.startTime)) {
    bookingStore.removeSlotFromBasket(slot.startTime)
  } else if (bookingStore.batchBasketSize < maxBatchSize.value) {
    bookingStore.addSlotToBasket(slot)
  }
}

function toggleBatchMode() {
  batchMode.value = !batchMode.value
  if (!batchMode.value) {
    bookingStore.clearBatchBasket()
    selectedSlot.value = null
    batchReviewOpen.value = false
  }
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

async function submitBatchRequest() {
  if (!playerId.value) {
    $q.notify({ message: t('booking.batch.submitError'), type: 'negative' })
    return
  }
  try {
    await bookingStore.submitBatch(coachId, playerId.value, 0)
    batchReviewOpen.value = false
    $q.notify({ message: t('booking.batch.submitted'), type: 'positive' })
    router.push('/parent/bookings')
  } catch {
    $q.notify({ message: t('booking.batch.submitError'), type: 'negative' })
  }
}

onMounted(async () => {
  await bookingStore.loadAvailability(coachId)
  if (playerId.value) {
    await bookingStore.loadPlayerPacks(playerId.value)
  }
  bookingStore.loadParentBookings()
  try {
    const res = await getBatchConfig()
    maxBatchSize.value = res.data.maxSize
  } catch {
    console.warn('Could not load batch config, using default max size')
  }
})

onUnmounted(() => {
  bookingStore.clearBatchBasket()
})
</script>
