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
            :key="slot.startDatetime"
            clickable
            :disable="
              batchMode
                ? (!bookingStore.isSlotInBasket(slot.startDatetime) && batchAtMax) ||
                  bookedStartTimes.has(slot.startDatetime)
                : bookedStartTimes.has(slot.startDatetime)
            "
            :active="
              batchMode
                ? bookingStore.isSlotInBasket(slot.startDatetime)
                : selectedSlot?.startDatetime === slot.startDatetime
            "
            active-class="bg-primary text-white"
            @click="batchMode ? toggleSlotInBasket(slot) : selectSlot(slot)"
          >
            <q-item-section>
              <q-item-label>{{ formatSlot(slot.startDatetime) }}</q-item-label>
              <q-item-label caption>{{ formatSlot(slot.endDatetime) }}</q-item-label>
            </q-item-section>
            <q-item-section
              v-if="batchMode && bookingStore.isSlotInBasket(slot.startDatetime)"
              side
            >
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
      <q-card v-if="activePacksForCoach.length > 0" flat bordered class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1 q-mb-sm">{{ t('booking.requests.selectPack') }}</div>
          <q-option-group
            v-model="selectedPackId"
            :options="packOptions"
            color="primary"
          />
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
              :key="slot.startDatetime"
              class="q-py-sm"
            >
              <q-item-section>
                <q-item-label>{{ formatSlot(slot.startDatetime) }}</q-item-label>
                <q-item-label caption>{{ formatSlot(slot.endDatetime) }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-btn
                  flat
                  round
                  dense
                  icon="close"
                  size="sm"
                  @click="bookingStore.removeSlotFromBasket(slot.startDatetime)"
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

// AC2: let the parent pick a specific pack (or "pay per session") when submitting a single
// booking request. Batch bookings don't carry a pack (Task 4 decision — credit-wallet/Stripe
// only), so this selector only applies in single-booking mode.
const selectedPackId = ref(null)
const activePacksForCoach = computed(() =>
  bookingStore.sessionPacks.filter(
    (p) => String(p.coachId) === String(coachId) && p.status === 'ACTIVE',
  ),
)
const packOptions = computed(() => [
  { label: t('booking.packs.perSession'), value: null },
  ...activePacksForCoach.value.map((p) => ({
    label: t('booking.requests.packOptionLabel', { remaining: p.creditsRemaining, total: p.sessionCount }),
    value: p.id,
  })),
])

const ACTIVE_BOOKING_STATUSES = new Set(['REQUESTED', 'ACCEPTED', 'CONFIRMED', 'UPCOMING', 'IN_PROGRESS'])
const bookedStartTimes = computed(
  () =>
    new Set(
      bookingStore.parentBookings
        .filter(
          (b) =>
            String(b.coachId) === String(coachId) &&
            String(b.playerId) === String(playerId.value) &&
            ACTIVE_BOOKING_STATUSES.has(b.status),
        )
        .map((b) => b.requestedStartTime),
    ),
)

const canSubmit = computed(
  // Do NOT gate on hasCredits: AC 3 allows booking via platform credit (Cases A/B) or full
  // Stripe charge (Case C). The backend handles payment failure gracefully (→ DECLINED).
  () => selectedSlot.value !== null && !submitting.value,
)

function selectSlot(slot) {
  selectedSlot.value = slot
}

function toggleSlotInBasket(slot) {
  if (bookingStore.isSlotInBasket(slot.startDatetime)) {
    bookingStore.removeSlotFromBasket(slot.startDatetime)
  } else if (bookingStore.batchBasketSize < maxBatchSize.value) {
    bookingStore.addSlotToBasket(slot)
  }
}

function toggleBatchMode() {
  batchMode.value = !batchMode.value
  if (batchMode.value) {
    selectedSlot.value = null // clear stale single-slot selection
  } else {
    bookingStore.clearBatchBasket()
    selectedSlot.value = null
    batchReviewOpen.value = false
  }
}

// timeZoneName is deliberate: these instants are rendered in the coach's zone, not the parent's,
// so an unlabelled "3:00 PM" would read as local time to a parent in another zone.
function formatInZone(date, timeZone) {
  try {
    return new Intl.DateTimeFormat('en', {
      timeZone,
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZoneName: 'short',
    }).format(date)
  } catch {
    // Intl.DateTimeFormat throws RangeError on an unrecognized zone. Nothing validates
    // coach_profiles.canonical_timezone as a real IANA zone, so fall back rather than let a
    // throw inside a v-for label abort the render of the entire slot list.
    return new Intl.DateTimeFormat('en', {
      timeZone: 'UTC',
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZoneName: 'short',
    }).format(date)
  }
}

function formatSlot(isoString) {
  // Guard the date before formatting: .format() throws RangeError on an Invalid Date, and a
  // zone-only fallback would rethrow it. Both throw sites have to be handled separately.
  const date = new Date(isoString)
  if (Number.isNaN(date.getTime())) return '—'
  return formatInZone(date, bookingStore.coachTimezone || 'UTC')
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
      requestedStartTime: selectedSlot.value.startDatetime,
      requestedEndTime: selectedSlot.value.endDatetime,
      notes: notes.value || null,
      sessionPackPurchaseId: selectedPackId.value,
    })
    router.push('/parent/bookings')
  } catch {
    $q.notify({ type: 'negative', message: t('booking.requests.submitError') })
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
    // totalAmount is currently a display-only stub for the acceptance email preview, not used
    // for settlement (see BookingBatchService.java:233,255) — do not compute a real total here.
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
    maxBatchSize.value = res.maxSize
  } catch {
    console.warn('Could not load batch config, using default max size')
  }
})

onUnmounted(() => {
  bookingStore.clearBatchBasket()
})
</script>
