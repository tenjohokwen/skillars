<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">{{ t('booking.requests.coachInboxTitle') }}</div>

    <div v-if="bookingStore.coachRequestsLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div
      v-else-if="
        bookingStore.coachBookingRequests.length === 0 &&
        bookingStore.coachBatchGroups.length === 0
      "
      class="flex flex-center column q-gutter-md q-py-xl"
      style="min-height: 40vh"
    >
      <q-icon name="inbox" size="64px" style="color: var(--text-secondary)" />
      <div class="text-body1" style="color: var(--text-secondary)">
        {{ t('booking.requests.coachInboxEmpty') }}
      </div>
    </div>

    <template v-else>
      <!-- Batch groups -->
      <div
        v-for="group in bookingStore.coachBatchGroups"
        :key="group.batchId"
        class="q-mb-md"
      >
        <q-card flat bordered>
          <q-card-section class="q-pb-xs">
            <div class="text-subtitle2">
              {{
                t('booking.batch.groupTitle', {
                  name: group.parentName,
                  n: group.totalCount,
                })
              }}
            </div>
          </q-card-section>
          <q-list bordered separator>
            <q-item
              v-for="booking in group.bookings"
              :key="booking.id"
              class="q-py-sm"
            >
              <q-item-section>
                <q-item-label>{{ booking.playerName }}</q-item-label>
                <q-item-label caption>{{
                  formatDateTime(booking.requestedStartTime, booking.canonicalTimezone)
                }}</q-item-label>
                <q-item-label
                  v-if="failureReasonFor(group.batchId, booking.id)"
                  caption
                  class="text-negative"
                >
                  {{ failureReasonFor(group.batchId, booking.id) }}
                </q-item-label>
              </q-item-section>
              <q-item-section side>
                <div class="row q-gutter-xs">
                  <q-btn
                    unelevated
                    color="primary"
                    size="xs"
                    :label="t('booking.requests.accept')"
                    :loading="accepting[booking.id]"
                    @click="handleAccept(booking.id)"
                  />
                  <q-btn
                    flat
                    color="negative"
                    size="xs"
                    :label="t('booking.requests.decline')"
                    :loading="declining[booking.id]"
                    @click="handleDecline(booking.id)"
                  />
                </div>
              </q-item-section>
            </q-item>
          </q-list>
          <q-card-actions v-if="batchIsActionable(group.status)">
            <q-btn
              unelevated
              color="positive"
              class="full-width"
              :label="t('booking.batch.acceptAll', { n: group.totalCount })"
              :loading="acceptingAll[group.batchId]"
              @click="handleAcceptAll(group.batchId)"
            />
          </q-card-actions>
        </q-card>
      </div>

      <!-- Single bookings -->
      <q-list v-if="bookingStore.coachBookingRequests.length > 0" bordered separator>
        <q-item
          v-for="booking in bookingStore.coachBookingRequests"
          :key="booking.id"
          class="q-py-md"
        >
          <q-item-section>
            <q-item-label class="text-weight-bold">{{ booking.playerName }}</q-item-label>
            <q-item-label caption>{{
              t('booking.requests.parentLabel', { name: booking.parentName })
            }}</q-item-label>
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
    </template>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'

const { t, locale } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()

const accepting = ref({})
const declining = ref({})
const acceptingAll = ref({})

function formatDateTime(isoString, timezone) {
  return new Date(isoString).toLocaleString(locale.value, { timeZone: timezone })
}

// Post-mutation refresh contract, shared by every handler below (skillars-deferred-31 AC1):
// refresh FIRST, then toast, so the message describes state the coach can already see; and after
// every refresh — success path included — warn if it failed, because the store's loaders swallow
// their own failures and no component renders the resulting error ref (see booking.store.js).
//
// Takes the refresh's OWN return value rather than re-reading bookingStore.coachRequestsError: that
// ref is module-scoped and reset-then-written on every load, so a concurrent refresh — two rows
// accepted in quick succession, which the per-row accepting[id]/declining[id] flags deliberately
// allow — could otherwise attribute one action's failure to another, or clear it before it is read.
function notifyIfRequestsStale(refreshed) {
  if (!refreshed) {
    $q.notify({ type: 'warning', message: t('booking.errors.listMayBeStale') })
  }
}

async function handleAccept(id) {
  accepting.value[id] = true
  try {
    // approveBooking runs loadCoachBookingRequests() itself and returns that refresh's outcome.
    notifyIfRequestsStale(await bookingStore.approveBooking(id))
  } catch (err) {
    const refreshed = await bookingStore.loadCoachBookingRequests()
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'booking.coachUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.coachUnavailable') })
    } else if (errorKey === 'booking.slotUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.slotUnavailable') })
    } else {
      $q.notify({ type: 'negative', message: t('booking.requests.acceptError') })
    }
    notifyIfRequestsStale(refreshed)
  } finally {
    accepting.value[id] = false
  }
}

async function handleDecline(id) {
  declining.value[id] = true
  try {
    // rejectBooking runs loadCoachBookingRequests() itself and returns that refresh's outcome.
    notifyIfRequestsStale(await bookingStore.rejectBooking(id))
  } catch {
    const refreshed = await bookingStore.loadCoachBookingRequests()
    $q.notify({ type: 'negative', message: t('booking.requests.declineError') })
    notifyIfRequestsStale(refreshed)
  } finally {
    declining.value[id] = false
  }
}

// O(1) per-row lookup instead of a linear Array.find() scan per call — the template calls
// failureReasonFor twice per pending row, once per re-render (skillars-deferred-34 code review
// Decision→Defer, closed by skillars-deferred-35 AC2). Vue's computed cache means this only rebuilds
// when bookingStore.batchAcceptResultsByBatch itself changes, not on every unrelated re-render. Stores
// EVERY result (accepted and failed) — skillars-deferred-35's first cut stored only failed entries,
// which was behavior-preserving for failureReasonFor's one caller but discarded which bookingIds were
// accepted, narrowing this computed's usefulness for any future caller (skillars-deferred-35 code
// review, closed here). failureReasonFor below restores the `!result || result.accepted` guard the
// pre-refactor Array.find() version used.
const resultByBatch = computed(() => {
  const byBatch = {}
  for (const [batchId, results] of Object.entries(bookingStore.batchAcceptResultsByBatch)) {
    if (!results) continue
    const byBookingId = new Map()
    for (const r of results) {
      if (!byBookingId.has(r.bookingId)) byBookingId.set(r.bookingId, r)
    }
    byBatch[batchId] = byBookingId
  }
  return byBatch
})

function failureReasonFor(batchId, bookingId) {
  const result = resultByBatch.value[batchId]?.get(bookingId)
  if (!result || result.accepted) return null
  if (result.errorKey === 'booking.slotUnavailable') return t('booking.errors.slotUnavailable')
  if (result.errorKey === 'booking.coachUnavailable') return t('booking.errors.coachUnavailable')
  return t('booking.batch.itemNotAccepted')
}

// Sourced from the batch's real, server-persisted status (BatchGroupedBookingResponse.status),
// not from this session's own batchAcceptResultsByBatch — a client-only ref would forget a known
// failure on reload/new tab, leaving "Accept All" clickable again for a batch acceptAll always
// rejects with booking.batchAlreadyProcessed. See skillars-deferred-34 code review Decision 1.
function batchIsActionable(status) {
  return status === 'PENDING'
}

async function handleAcceptAll(batchId) {
  acceptingAll.value[batchId] = true
  try {
    // handleAcceptAllBatch returns both its own refresh outcome and its own results — read results from
    // here, not from bookingStore.batchAcceptResultsByBatch[batchId], which AC1's pruning can have
    // already removed by the time this line runs (skillars-deferred-37 code review).
    const { refreshed, results } = await bookingStore.handleAcceptAllBatch(batchId)
    notifyIfRequestsStale(refreshed)
    const failedCount = results.filter((r) => !r.accepted).length
    if (failedCount > 0) {
      $q.notify({
        type: 'warning',
        message: t('booking.batch.partiallyAccepted', {
          accepted: results.length - failedCount,
          total: results.length,
        }),
      })
    } else {
      $q.notify({ message: t('booking.batch.acceptedAll'), type: 'positive' })
    }
  } catch (err) {
    const refreshed = await bookingStore.loadCoachBookingRequests()
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    // FOUR outcomes reach the client from BookingBatchService.acceptAll. Three are PRE-FLIGHT
    // checks — the per-booking throws inside the loop are swallowed by its own catch: batch
    // ownership (MISSING_RIGHTS), the already-processed batch (split out of MISSING_RIGHTS by the
    // skillars-deferred-30 review — the ordinary double-click case, not retryable) and the suspended
    // coach. The fourth is POST-loop: booking.batchNoneAccepted (skillars-deferred-31 AC2) fires
    // when the loop accepted nothing, either because every per-booking accept threw and was
    // swallowed or because the still-PENDING batch had no REQUESTED bookings left. It is the one
    // outcome that says something about the loop's own result; it still carries no per-booking
    // detail. Earlier versions of this comment counted two, then three.
    if (errorKey === 'booking.coachUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.coachUnavailable') })
    } else if (errorKey === 'booking.batchAlreadyProcessed') {
      $q.notify({ type: 'negative', message: t('booking.errors.batchAlreadyProcessed') })
    } else if (errorKey === 'booking.batchNoneAccepted') {
      $q.notify({ type: 'negative', message: t('booking.errors.batchNoneAccepted') })
    } else if (errorKey === 'MISSING_RIGHTS') {
      $q.notify({ type: 'negative', message: t('booking.errors.requestNotAllowed') })
    } else {
      $q.notify({ type: 'negative', message: t('booking.batch.acceptError') })
    }
    notifyIfRequestsStale(refreshed)
  } finally {
    acceptingAll.value[batchId] = false
  }
}

let isMounted = true
onUnmounted(() => {
  isMounted = false
})

onMounted(async () => {
  const refreshed = await bookingStore.loadCoachBookingRequests()
  if (isMounted) notifyIfRequestsStale(refreshed)
})
</script>
