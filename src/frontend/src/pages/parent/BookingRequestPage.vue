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

    <!-- Packs are out of scope for a self-booking player (UAT.5): pay-per-session only, and
         purchase-sessions stays a parent-only route — hide both credit UI elements rather than
         show a dead-end "buy sessions" action. -->
    <SessionPackTracker
      v-if="!authStore.isPlayer"
      :credits-remaining="creditsForCoach"
      :session-count="0"
      class="q-mb-md"
    />

    <q-banner v-if="!authStore.isPlayer && !hasCredits" class="bg-warning text-white q-mb-md">
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

        <q-list v-else-if="slotRows.length > 0" bordered separator>
          <template v-for="row in slotRows" :key="row.key">
            <!-- A slot the parent has already requested/booked. Rendered rather than omitted:
                 the backend correctly carves it out of computedSlots, so before this it simply
                 vanished from the calendar with no explanation (deferred-18 D3). -->
            <q-item v-if="row.type === 'own'" disable>
              <q-item-section>
                <q-item-label>{{ formatSlot(row.start) }}</q-item-label>
                <q-item-label caption>{{ formatSlot(row.end) }}</q-item-label>
                <q-item-label caption>{{ t('booking.requests.ownBookingCaption') }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <BookingStateChip :status="row.status" />
              </q-item-section>
            </q-item>

            <q-item
              v-else
              clickable
              :disable="
                batchMode
                  ? !bookingStore.isSlotInBasket(row.slot.startDatetime) && batchAtMax
                  : false
              "
              :active="
                batchMode
                  ? bookingStore.isSlotInBasket(row.slot.startDatetime)
                  : selectedSlot?.startDatetime === row.slot.startDatetime
              "
              active-class="bg-primary text-white"
              @click="batchMode ? toggleSlotInBasket(row.slot) : selectSlot(row.slot)"
            >
              <q-item-section>
                <q-item-label>{{ formatSlot(row.slot.startDatetime) }}</q-item-label>
                <q-item-label caption>{{ formatSlot(row.slot.endDatetime) }}</q-item-label>
              </q-item-section>
              <q-item-section
                v-if="batchMode && bookingStore.isSlotInBasket(row.slot.startDatetime)"
                side
              >
                <q-chip dense color="positive" text-color="white" size="sm">{{
                  t('booking.batch.added')
                }}</q-chip>
              </q-item-section>
            </q-item>
          </template>
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
          <q-option-group v-model="selectedPackId" :options="packOptions" color="primary" />
        </q-card-section>
      </q-card>

      <!-- UAT.5 AC2/AC4: a self-booking player pays per-session by card only (packs out of
           scope) — the same reusable card-entry component parent pages already use for
           setup-intent/save-payment-method, wholesale, no pack-purchase logic inside it. -->
      <q-card v-if="authStore.isPlayer" flat bordered class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1 q-mb-sm">{{ t('payment.card.title') }}</div>
        </q-card-section>
        <q-card-section class="q-pt-none">
          <PaymentMethodCard />
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
          <!-- UAT.5: a self-booking player has no credit concept (packs out of scope) — the
               credit-oriented preview text would read as confusing/wrong ("0 credits available")
               for a per-session-by-card payer. -->
          <div class="q-mt-md text-caption" style="color: var(--text-secondary)">
            {{
              authStore.isPlayer
                ? t('booking.batch.sessionCountPreview', { count: bookingStore.batchBasketSize })
                : t('booking.batch.creditPreview', {
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
            :loading="bookingStore.batchSubmitting || refetchingBatchMaxSize"
            :disable="bookingStore.batchBasketSize === 0 || refetchingBatchMaxSize"
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
import { useAuthStore } from 'src/stores/auth.store'
import { useBookingStore } from 'src/stores/booking.store'
import { usePlayerStore } from 'src/stores/playerStore'
import { getBatchConfig, getBookingRequestConfig } from 'src/api/booking.api'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'

const route = useRoute()
const router = useRouter()
const { t, locale } = useI18n()
const $q = useQuasar()
const authStore = useAuthStore()
const bookingStore = useBookingStore()
const playerStore = usePlayerStore()

const coachId = route.params.coachId
// UAT.5 AC4: a self-registered player resolves playerId via their own player profile, not via
// playerStore (a parent's linked-children store, meaningless for a self-registered player).
const selfPlayerId = ref(null)
const playerId = computed(() => {
  if (route.query.playerId) return Number(route.query.playerId)
  if (authStore.isPlayer) return selfPlayerId.value
  return playerStore.activePlayerId
})
const coachName = ref(route.query.coachName ?? '')
const selectedSlot = ref(null)
const notes = ref('')
const submitting = ref(false)

const batchMode = ref(false)
const batchReviewOpen = ref(false)
const maxBatchSize = ref(5) // populated from backend on mount
// Guards the batchSizeExceeded re-fetch window: batchSubmitting has already reset to false by
// the time this branch runs (submitBatch's own finally clears it), so without this the dialog's
// submit button is re-clickable while getBatchConfig() is still in flight, letting a second
// overlapping re-fetch's out-of-order response clobber maxBatchSize.value with a stale number.
const refetchingBatchMaxSize = ref(false)

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
    label: t('booking.requests.packOptionLabel', {
      remaining: p.creditsRemaining,
      total: p.sessionCount,
    }),
    value: p.id,
  })),
])

const canSubmit = computed(
  // Do NOT gate on hasCredits: AC 3 allows booking via platform credit (Cases A/B) or full
  // Stripe charge (Case C). The backend handles payment failure gracefully (→ DECLINED).
  // playerId IS gated: without it, single-booking submit had no safety net, unlike
  // submitBatchRequest's explicit guard — a self-booking player whose profile hasn't resolved
  // yet (or a malformed query string) must not be able to submit with an undefined playerId.
  () => selectedSlot.value !== null && !!playerId.value && !submitting.value,
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
    return new Intl.DateTimeFormat(locale.value, {
      timeZone,
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZoneName: 'short',
    }).format(date)
  } catch {
    // Intl.DateTimeFormat throws RangeError on an unrecognized zone. Nothing validates
    // coach_profiles.canonical_timezone as a real IANA zone, so fall back rather than let a
    // throw inside a v-for label abort the render of the entire slot list.
    return new Intl.DateTimeFormat(locale.value, {
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

// ---- AC5: the parent's own pending/booked slots, shown as disabled rows ----

// ACTIVE_SLOT_STATUSES on the backend, fetched via GET /api/bookings/requests/config — populated
// from backend on mount. Hardcoded here only as a fetch-failure fallback identical to the values
// this replaced, so a slow/failed config load still blocks the parent's own occupied slots
// correctly instead of showing them all as available.
const ownBlockingStatuses = ref([
  'REQUESTED',
  'ACCEPTED',
  'PAYMENT_PENDING',
  'CONFIRMED',
  'UPCOMING',
  'IN_PROGRESS',
  'PAUSED',
])

/** Milliseconds a zone is offset from UTC at a given instant. */
function zoneOffsetMs(ts, timeZone) {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
      .formatToParts(new Date(ts))
      .map((p) => [p.type, p.value]),
  )
  return (
    Date.UTC(
      Number(parts.year),
      Number(parts.month) - 1,
      Number(parts.day),
      Number(parts.hour) % 24,
      Number(parts.minute),
      Number(parts.second),
    ) - ts
  )
}

/**
 * Midnight on a `YYYY-MM-DD` date, in `timeZone`, as an epoch-millisecond instant.
 *
 * The week bounds MUST be instants anchored in the coach's zone, not a bare local-date range:
 * bookingStore.weekStart is built from the BROWSER's date while the backend anchors the week it
 * computed slots for in the coach's zone. Comparing against browser-local or UTC midnight
 * misclassifies bookings at the week edges for any tester in a different zone — and a booking
 * wrongly judged out-of-week is dropped from this list while its slot stays carved out of
 * computedSlots, reproducing the exact hole AC5 closes, at the boundary.
 */
function zonedMidnightMs(dateStr, timeZone, dayOffset = 0) {
  const naiveUtc = Date.parse(`${dateStr}T00:00:00Z`) + dayOffset * 86400000
  if (Number.isNaN(naiveUtc)) return NaN
  try {
    const first = zoneOffsetMs(naiveUtc, timeZone)
    const candidate = naiveUtc - first
    // Second pass: the offset at the naive instant can differ from the offset at the real one
    // across a DST transition.
    const second = zoneOffsetMs(candidate, timeZone)
    return second === first ? candidate : naiveUtc - second
  } catch {
    // Intl throws RangeError on an unrecognized zone; UTC is the same fallback formatSlot uses.
    return naiveUtc
  }
}

const ownBlockingBookings = computed(() => {
  const bookings = bookingStore.parentBookings
  // Guard on the array, not on the absence of an error: loadParentBookings swallows failures into
  // bookingsError, and a failed fetch must never blank the slot list.
  if (!Array.isArray(bookings) || bookings.length === 0) return []
  const weekStart = bookingStore.weekStart
  if (!weekStart) return []

  const zone = bookingStore.coachTimezone || 'UTC'
  const from = zonedMidnightMs(weekStart, zone)
  const to = zonedMidnightMs(weekStart, zone, 7)
  if (Number.isNaN(from) || Number.isNaN(to)) return []

  return bookings.filter((b) => {
    if (String(b.coachId) !== String(coachId)) return false
    if (!ownBlockingStatuses.value.includes(b.status)) return false
    const start = Date.parse(b.requestedStartTime)
    return !Number.isNaN(start) && start >= from && start < to
  })
})

/**
 * computedSlots and the parent's own bookings, merged into one grid sorted by start time. Only
 * coherent because of AC2: with fixed-length slots a booking occupies exactly one slot position,
 * so the merged list reads as a continuous grid with some rows greyed out. Against the old
 * whole-segment behaviour the same merge produced overlapping rows of different lengths.
 *
 * Own rows are deliberately not selectable, never enter the batch basket, and do not count toward
 * batchAtMax — batchAtMax reads bookingStore.batchBasketSize, which they never join.
 */
const slotRows = computed(() => {
  const available = bookingStore.computedSlots.map((slot) => ({
    type: 'available',
    key: `slot-${slot.startDatetime}`,
    sortKey: Date.parse(slot.startDatetime),
    slot,
  }))
  const own = ownBlockingBookings.value.map((b) => ({
    type: 'own',
    key: `own-${b.id ?? b.requestedStartTime}`,
    sortKey: Date.parse(b.requestedStartTime),
    start: b.requestedStartTime,
    end: b.requestedEndTime,
    status: b.status,
  }))
  return [...available, ...own].sort((a, b) => a.sortKey - b.sortKey)
})

function goToPurchase() {
  router.push(`/parent/coaches/${coachId}/purchase-sessions?playerId=${playerId.value}`)
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  let succeeded = false
  try {
    await bookingStore.submitBookingRequest({
      coachId,
      playerId: playerId.value,
      requestedStartTime: selectedSlot.value.startDatetime,
      requestedEndTime: selectedSlot.value.endDatetime,
      notes: notes.value || null,
      sessionPackPurchaseId: selectedPackId.value,
    })
    succeeded = true
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    // I18N NAMESPACE CONVENTION (skillars-deferred-31 AC4): a toast key lives in the namespace of
    // the WIRE CODE's domain prefix, not the page that renders it. A `payment.*` errorKey therefore
    // resolves under `payment.*` even though this is a booking page. Before this rule the four
    // payment codes below landed in three different namespaces and the next author had nothing to
    // follow. When adding a branch here, put the string where the code says, not where the file is.
    if (errorKey === 'booking.coachUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.coachUnavailable') })
    } else if (errorKey === 'booking.slotUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.slotUnavailable') })
    } else if (errorKey === 'booking.invalidSessionDuration') {
      $q.notify({ type: 'negative', message: t('booking.errors.invalidSessionDuration') })
    } else if (errorKey === 'payment.coachStripeNotConfigured') {
      $q.notify({ type: 'negative', message: t('payment.error.coachStripeNotConfigured') })
    } else if (errorKey === 'payment.packExpired') {
      $q.notify({ type: 'negative', message: t('payment.sessionPack.packExpired') })
    } else if (errorKey === 'payment.packCoachMismatch') {
      $q.notify({ type: 'negative', message: t('payment.sessionPack.packCoachMismatch') })
    } else if (errorKey === 'payment.packExhausted') {
      $q.notify({ type: 'negative', message: t('payment.sessionPack.packExhausted') })
    } else if (errorKey === 'booking.startTimeInPast') {
      $q.notify({ type: 'negative', message: t('booking.errors.startTimeInPast') })
    } else if (errorKey === 'booking.invalidTimeRange') {
      $q.notify({ type: 'negative', message: t('booking.errors.invalidTimeRange') })
    } else if (errorKey === 'booking.slotOutsideAvailability') {
      $q.notify({ type: 'negative', message: t('booking.errors.slotOutsideAvailability') })
    } else if (errorKey === 'MISSING_RIGHTS') {
      // Post-split, MISSING_RIGHTS carries exactly one user-facing meaning on this path: the caller
      // does not own the player profile or the session pack they submitted. The four validation
      // causes that used to share this code now have their own branches above.
      $q.notify({ type: 'negative', message: t('booking.errors.requestNotAllowed') })
    } else {
      // skillars-deferred-31 AC6: the diagnostic value only. boot/axios.js rejects with the ORIGINAL
      // axios error, so `err` carries err.config.data — the serialized request body, which on this
      // path holds a minor's playerId and free-text notes. errorKey is a wire constant.
      console.warn('[booking] unmapped errorKey:', errorKey)
      $q.notify({ type: 'negative', message: t('booking.requests.submitError') })
    }
  } finally {
    submitting.value = false
  }
  if (succeeded) router.push('/parent/bookings')
}

async function submitBatchRequest() {
  if (!playerId.value) {
    $q.notify({ message: t('booking.batch.submitError'), type: 'negative' })
    return
  }
  let succeeded = false
  try {
    // totalAmount is currently a display-only stub for the acceptance email preview, not used
    // for settlement (see BookingBatchService.java:233,255) — do not compute a real total here.
    await bookingStore.submitBatch(coachId, playerId.value, 0)
    batchReviewOpen.value = false
    $q.notify({ message: t('booking.batch.submitted'), type: 'positive' })
    succeeded = true
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'booking.batchSizeExceeded') {
      // Re-fetch before toasting: the client's cached maxBatchSize can have drifted below the
      // server's live booking.batch.maxSize by the time this rejection arrives, which is the only
      // way this branch is even reachable (toggleSlotInBasket already caps the basket at the
      // cached value) — quoting the stale number would confidently state the wrong figure. This
      // also re-syncs maxBatchSize.value, which gates batchAtMax and the basket cap for the rest
      // of the session, so do not make the re-fetch conditional on the toast succeeding.
      refetchingBatchMaxSize.value = true
      try {
        const res = await getBatchConfig()
        maxBatchSize.value = res.maxSize
        $q.notify({
          message: t('booking.errors.batchSizeExceeded', { max: maxBatchSize.value }),
          type: 'negative',
        })
      } catch {
        console.warn('Could not re-fetch batch config, using previous max size')
        $q.notify({
          message: t('booking.errors.batchSizeExceededUnknownMax'),
          type: 'negative',
        })
      } finally {
        refetchingBatchMaxSize.value = false
      }
    } else if (errorKey === 'booking.invalidSessionDuration') {
      $q.notify({ message: t('booking.errors.invalidSessionDuration'), type: 'negative' })
    } else if (errorKey === 'booking.duplicateSlotStartTime') {
      $q.notify({ message: t('booking.errors.duplicateSlotStartTime'), type: 'negative' })
    } else if (errorKey === 'booking.overlappingSlots') {
      $q.notify({ message: t('booking.errors.overlappingSlots'), type: 'negative' })
    } else if (errorKey === 'booking.coachUnavailable') {
      $q.notify({ message: t('booking.errors.coachUnavailable'), type: 'negative' })
    } else if (errorKey === 'booking.startTimeInPast') {
      $q.notify({ message: t('booking.errors.startTimeInPast'), type: 'negative' })
    } else if (errorKey === 'booking.invalidTimeRange') {
      $q.notify({ message: t('booking.errors.invalidTimeRange'), type: 'negative' })
    } else if (errorKey === 'booking.slotOutsideAvailability') {
      $q.notify({ message: t('booking.errors.slotOutsideAvailability'), type: 'negative' })
    } else if (errorKey === 'MISSING_RIGHTS') {
      $q.notify({ message: t('booking.errors.requestNotAllowed'), type: 'negative' })
    } else {
      // skillars-deferred-31 AC6: the diagnostic value only. boot/axios.js rejects with the ORIGINAL
      // axios error, so `err` carries err.config.data — the serialized request body, which on this
      // path holds a minor's playerId and free-text notes. errorKey is a wire constant.
      console.warn('[booking] unmapped errorKey:', errorKey)
      $q.notify({ message: t('booking.batch.submitError'), type: 'negative' })
    }
  }
  if (succeeded) router.push('/parent/bookings')
}

onMounted(async () => {
  if (authStore.isPlayer && !route.query.playerId) {
    try {
      selfPlayerId.value = await playerStore.fetchSelfPlayerId()
    } catch (profileErr) {
      // A 404 is the expected, silent case: no player profile yet. Anything else (network/500)
      // is surfaced — canSubmit's playerId guard still blocks a broken submit either way, but
      // the player should know why rather than stare at a permanently-disabled button.
      if (profileErr.response?.status !== 404) {
        $q.notify({ type: 'negative', message: t('common.errorGeneric') })
      }
    }
  }
  await bookingStore.loadAvailability(coachId)
  // AC5: the data behind the "you already requested this" rows. The store action and its API call
  // already exist; a failure is swallowed into bookingsError and leaves parentBookings empty,
  // which degrades to exactly the pre-AC5 rendering rather than blanking the page.
  await bookingStore.loadParentBookings()
  // Packs are out of scope for a self-booking player (GET /api/payment/session-packs stays
  // @PreAuthorize(HAS_PARENT_ROLE)) — skip the guaranteed-403 request entirely rather than rely
  // on it degrading internally to 0 credits.
  if (playerId.value && !authStore.isPlayer) {
    await bookingStore.loadPlayerPacks(playerId.value)
  }
  try {
    const res = await getBatchConfig()
    maxBatchSize.value = res.maxSize
  } catch {
    console.warn('Could not load batch config, using default max size')
  }
  try {
    const res = await getBookingRequestConfig()
    ownBlockingStatuses.value = res.activeSlotStatuses
  } catch {
    console.warn('Could not load booking request config, using default active-slot statuses')
  }
})

onUnmounted(() => {
  bookingStore.clearBatchBasket()
})
</script>
