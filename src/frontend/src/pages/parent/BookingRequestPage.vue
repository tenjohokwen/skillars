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

    <!-- Shown to a self-booking PLAYER too: since Deferred-81/82 a self-registered player can buy
         their own packs (purchase-sessions route) and GET /api/payment/session-packs admits
         ROLE_PLAYER (packs they bought are keyed to their own user id), so the UAT.5-era
         "packs out of scope for players" gating here had gone stale. -->
    <SessionPackTracker :credits-remaining="creditsForCoach" :session-count="0" class="q-mb-md" />

    <q-banner v-if="!hasCredits" class="bg-warning text-white q-mb-md">
      {{ t('booking.requests.noCreditsWarning') }}
      <template #action>
        <q-btn flat :label="t('booking.packs.buySessions')" @click="goToPurchase" />
      </template>
    </q-banner>

    <!-- fix/booking-price-and-excused-race: a coach with no session price cannot be booked by card or
         credit (the backend refuses with booking.coachPricingUnavailable); a session pack still works. -->
    <q-banner
      v-if="coachUnpriced"
      class="bg-warning text-white q-mb-md"
      data-test="coach-pricing-unavailable"
    >
      {{ t('booking.errors.coachPricingUnavailable') }}
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
          {{ t('booking.availability.noSlotsAvailable') }}
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
          <!-- feat/coach-workflow-gaps A22 (story 3.9): per-session price, total and the € wallet /
               card split, from the backend quote — the same price source and split rule the batch
               settlement applies on acceptance. Session-pack credits are never applied to a batch
               (batch bookings carry no pack), so the old pack-credit preview here was misleading. -->
          <div class="q-mt-md" data-test="batch-quote">
            <div v-if="batchQuoteLoading" class="flex flex-center q-py-sm">
              <q-spinner-dots size="24px" />
            </div>
            <div
              v-else-if="coachUnpriced"
              class="text-caption text-negative"
              data-test="batch-quote-unpriced"
            >
              {{ t('booking.errors.coachPricingUnavailable') }}
            </div>
            <div
              v-else-if="batchQuoteError || !batchQuote"
              class="text-caption"
              style="color: var(--text-secondary)"
            >
              {{ t('booking.batch.quoteUnavailable', { count: bookingStore.batchBasketSize }) }}
            </div>
            <template v-else>
              <div class="row justify-between text-body2">
                <span>{{ t('booking.batch.perSessionPrice') }}</span>
                <span>{{ formatEuro(batchQuote.perSessionPrice) }}</span>
              </div>
              <div class="row justify-between text-body2 text-weight-bold q-mt-xs">
                <span>{{ t('booking.batch.total', { count: batchQuote.sessionCount }) }}</span>
                <span>{{ formatEuro(batchQuote.total) }}</span>
              </div>
              <div class="q-mt-sm text-caption" style="color: var(--text-secondary)">
                {{
                  hasWalletCredit
                    ? t('booking.batch.creditSplit', {
                        credit: formatEuro(batchQuote.creditApplied),
                        card: formatEuro(batchQuote.cardCharge),
                      })
                    : t('booking.batch.cardOnly', { card: formatEuro(batchQuote.cardCharge) })
                }}
              </div>
              <div class="q-mt-xs text-caption" style="color: var(--text-secondary)">
                {{ t('booking.batch.quoteNote') }}
              </div>
            </template>
          </div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            :label="t('booking.batch.confirmRequests')"
            :loading="bookingStore.batchSubmitting || refetchingBatchMaxSize"
            :disable="bookingStore.batchBasketSize === 0 || refetchingBatchMaxSize || coachUnpriced"
            @click="submitBatchRequest"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useAuthStore } from 'src/stores/auth.store'
import { useBookingStore } from 'src/stores/booking.store'
import { usePlayerStore } from 'src/stores/playerStore'
import { getBatchConfig, getBookingRequestConfig } from 'src/api/booking.api'
import { parseSharedSlot, sharedSlotWeekStart, toZonedLocalIso } from 'src/utils/sharedSlot'
import { fetchBatchQuote } from 'src/api/payment.api'
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
  if (route.query.playerId && !authStore.isPlayer) {
    const raw = String(route.query.playerId)
    if (/^[1-9]\d*$/.test(raw)) return raw
  }
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

// ─── feat/coach-workflow-gaps A22: batch cost preview ───────────────────────────────────────
const batchQuote = ref(null)
const batchQuoteLoading = ref(false)
const batchQuoteError = ref(null)
// Non-reactive ordering token: a slot removed while a quote is in flight must not let the stale
// response (for the previous count) overwrite the newer one.
let batchQuoteSequence = 0

async function loadBatchQuote(count) {
  const requestId = ++batchQuoteSequence
  batchQuoteLoading.value = true
  batchQuoteError.value = null
  try {
    const quote = await fetchBatchQuote(coachId, count)
    if (requestId !== batchQuoteSequence) return
    batchQuote.value = quote
  } catch (e) {
    if (requestId !== batchQuoteSequence) return
    batchQuote.value = null
    batchQuoteError.value = e
  } finally {
    if (requestId === batchQuoteSequence) batchQuoteLoading.value = false
  }
}

watch(
  () => [batchReviewOpen.value, bookingStore.batchBasketSize],
  ([open, count]) => {
    if (open && count > 0) {
      loadBatchQuote(count)
    } else {
      batchQuoteSequence++
      batchQuote.value = null
      batchQuoteLoading.value = false
    }
  },
)

const hasWalletCredit = computed(() => Number(batchQuote.value?.creditApplied ?? 0) > 0)

function formatEuro(amount) {
  return new Intl.NumberFormat(locale.value, { style: 'currency', currency: 'EUR' }).format(
    Number(amount ?? 0),
  )
}

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

// fix/booking-price-and-excused-race: only an explicit false blocks — an unknown (null) state must not
// disable booking; the backend refusal (booking.coachPricingUnavailable) is the real guard.
const coachUnpriced = computed(() => bookingStore.coachPriced === false)

const canSubmit = computed(
  // Do NOT gate on hasCredits: AC 3 allows booking via platform credit (Cases A/B) or full
  // Stripe charge (Case C). The backend handles payment failure gracefully (→ DECLINED).
  // playerId IS gated: without it, single-booking submit had no safety net, unlike
  // submitBatchRequest's explicit guard — a self-booking player whose profile hasn't resolved
  // yet (or a malformed query string) must not be able to submit with an undefined playerId.
  // An unpriced coach can still be booked against a session pack (its own locked price), never per session.
  () =>
    selectedSlot.value !== null &&
    !!playerId.value &&
    !submitting.value &&
    !(coachUnpriced.value && selectedPackId.value === null),
)

function selectSlot(slot) {
  selectedSlot.value = slot
}

// A coach's shared-slot link forwards ?slot=<coach-local YYYY-MM-DDTHH:mm>: preselect the matching
// available slot, if it is in the loaded availability. No match (taken, other week) → no-op.
function preselectSharedSlot() {
  const shared = parseSharedSlot(route.query.slot)
  if (!shared) return
  const match = (bookingStore.computedSlots ?? []).find(
    (s) => toZonedLocalIso(s.startDatetime, bookingStore.coachTimezone) === shared,
  )
  if (match) selectSlot(match)
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
  // skillars-deferred-90 AC11 exclusion: 'en-US' is a MACHINE format — the numeric parts are
  // parsed back out to compute a UTC offset, never displayed. Do NOT localize.
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
  const available = bookingStore.computedSlots
    // skillars-deferred-109 AC6: drop slots with an unparseable startDatetime BEFORE the map,
    // matching the ownBlockingBookings idiom above. A slot missing startDatetime otherwise yields
    // sortKey: NaN (implementation-defined ordering in the a.sortKey - b.sortKey comparator) and
    // key: "slot-undefined" for every such row → Vue row-reuse artifacts.
    .filter((slot) => !Number.isNaN(Date.parse(slot.startDatetime)))
    .map((slot) => ({
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
      availabilitySignature: bookingStore.availabilitySignature,
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
    } else if (errorKey === 'payment.packPlayerMismatch') {
      $q.notify({ type: 'negative', message: t('payment.sessionPack.packPlayerMismatch') })
    } else if (errorKey === 'payment.packPaused') {
      $q.notify({ type: 'negative', message: t('payment.sessionPack.packPaused') })
    } else if (errorKey === 'booking.startTimeInPast') {
      $q.notify({ type: 'negative', message: t('booking.errors.startTimeInPast') })
    } else if (errorKey === 'booking.invalidTimeRange') {
      $q.notify({ type: 'negative', message: t('booking.errors.invalidTimeRange') })
    } else if (errorKey === 'booking.slotOutsideAvailability') {
      $q.notify({ type: 'negative', message: t('booking.errors.slotOutsideAvailability') })
    } else if (errorKey === 'booking.availabilityChanged') {
      $q.notify({ type: 'negative', message: t('booking.errors.availabilityChanged') })
      await bookingStore.loadAvailability(coachId, bookingStore.weekStart)
    } else if (errorKey === 'booking.sessionCrossesMidnight') {
      $q.notify({ type: 'negative', message: t('booking.errors.sessionCrossesMidnight') })
    } else if (errorKey === 'booking.playerNotBookable') {
      $q.notify({ type: 'negative', message: t('booking.errors.playerNotBookable') })
    } else if (errorKey === 'booking.coachPricingUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.coachPricingUnavailable') })
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
    // totalAmount is ignored by the server, which computes the batch total from the coach's
    // pricing (fix/booking-integrity, B2) — do not compute a real total here.
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
        if (Number.isInteger(res.maxSize) && res.maxSize > 0) {
          maxBatchSize.value = res.maxSize
          $q.notify({
            message: t('booking.errors.batchSizeExceeded', { max: maxBatchSize.value }),
            type: 'negative',
          })
        } else {
          console.warn('Batch config response had an unexpected shape, using previous max size')
          $q.notify({
            message: t('booking.errors.batchSizeExceededUnknownMax'),
            type: 'negative',
          })
        }
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
    } else if (errorKey === 'booking.availabilityChanged') {
      // Mirrors submit()'s single-booking branch (skillars-deferred-71 AC2) — same wire code, same
      // refetch-then-toast shape, now also reachable from the batch path (skillars-deferred-72 AC3).
      $q.notify({ type: 'negative', message: t('booking.errors.availabilityChanged') })
      await bookingStore.loadAvailability(coachId, bookingStore.weekStart)
    } else if (errorKey === 'booking.sessionCrossesMidnight') {
      $q.notify({ message: t('booking.errors.sessionCrossesMidnight'), type: 'negative' })
    } else if (errorKey === 'booking.slotUnavailable') {
      // fix/booking-integrity (B2): createBatch now runs the same request-time overlap check and
      // Stripe-readiness check as the single-booking path, so these two codes are reachable here too.
      $q.notify({ message: t('booking.errors.slotUnavailable'), type: 'negative' })
    } else if (errorKey === 'payment.coachStripeNotConfigured') {
      $q.notify({ message: t('payment.error.coachStripeNotConfigured'), type: 'negative' })
    } else if (errorKey === 'booking.playerNotBookable') {
      $q.notify({ message: t('booking.errors.playerNotBookable'), type: 'negative' })
    } else if (errorKey === 'booking.coachPricingUnavailable') {
      $q.notify({ message: t('booking.errors.coachPricingUnavailable'), type: 'negative' })
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
  if (authStore.isPlayer) {
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
  // A shared-slot link may point at a later week (the coach can share from next week's grid):
  // load the week containing it so the preselect below can find the slot.
  const sharedWeek = sharedSlotWeekStart(parseSharedSlot(route.query.slot))
  if (sharedWeek) {
    await bookingStore.loadAvailability(coachId, sharedWeek)
  } else {
    await bookingStore.loadAvailability(coachId)
  }
  preselectSharedSlot()
  // AC5: the data behind the "you already requested this" rows. The store action and its API call
  // already exist; a failure is swallowed into bookingsError and leaves parentBookings empty,
  // which degrades to exactly the pre-AC5 rendering rather than blanking the page.
  await bookingStore.loadParentBookings()
  // GET /api/payment/session-packs is HAS_PARENT_OR_PLAYER_ROLE and returns the packs the caller
  // bought — a self-registered player's own packs — so players load them exactly like parents.
  if (playerId.value) {
    await bookingStore.loadPlayerPacks(playerId.value)
  }
  try {
    const res = await getBatchConfig()
    if (Number.isInteger(res.maxSize) && res.maxSize > 0) {
      maxBatchSize.value = res.maxSize
    } else {
      console.warn('Batch config response had an unexpected shape, using default max size')
    }
  } catch {
    console.warn('Could not load batch config, using default max size')
  }
  try {
    const res = await getBookingRequestConfig()
    if (Array.isArray(res.activeSlotStatuses) && res.activeSlotStatuses.length > 0) {
      ownBlockingStatuses.value = res.activeSlotStatuses
    } else {
      console.warn(
        'Booking request config response had an unexpected shape, using default active-slot statuses',
      )
    }
  } catch {
    console.warn('Could not load booking request config, using default active-slot statuses')
  }
})

onUnmounted(() => {
  bookingStore.clearBatchBasket()
})
</script>
