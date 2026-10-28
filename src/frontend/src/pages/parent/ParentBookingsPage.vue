<template>
  <q-page class="q-pa-md">
    <TimezoneNotice
      v-if="firstBookingTimezone && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="firstBookingTimezone"
    />

    <div class="text-h5 q-mb-md">{{ t('booking.requests.listTitle') }}</div>

    <q-banner v-if="bookingStore.bookingsError" class="bg-negative text-white q-mb-md" rounded>
      {{ t('booking.requests.bookingsLoadError') }}
    </q-banner>

    <div v-if="bookingStore.bookingsLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div
      v-else-if="!bookingStore.bookingsError && bookingStore.parentBookings.length === 0"
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
            flat
            dense
            class="self-start q-pa-none"
            :label="
              showInMyTime[booking.id]
                ? t('booking.timezone.showInSessionTime')
                : t('booking.timezone.showInMyTime')
            "
            @click="toggleTimezone(booking.id)"
          />
          <!-- Pending reschedule indicator -->
          <div
            v-if="booking.pendingReschedule"
            class="text-caption q-mt-xs"
            style="color: var(--accent-warning)"
          >
            {{ t('booking.reschedule.pendingLabel') }}
          </div>
          <div v-if="booking.pendingReschedule" class="text-caption q-mt-xs">
            <span class="text-strike">{{
              formatDateTime(booking.requestedStartTime, booking.canonicalTimezone)
            }}</span>
            →
            {{
              formatDateTime(booking.pendingReschedule.proposedStartTime, booking.canonicalTimezone)
            }}
          </div>
          <!-- Accept/decline a coach-proposed reschedule (skillars-deferred-69 AC5) — only a coach
               could ever be the one needing to respond before this story, so this indicator used to
               render unconditionally with no actions. -->
          <div v-if="booking.pendingReschedule?.proposedBy === 'COACH'" class="q-gutter-sm q-mt-xs">
            <q-btn
              unelevated
              color="primary"
              size="sm"
              :label="t('booking.reschedule.accept')"
              :loading="respondingId === booking.id"
              @click="handleAcceptRescheduleAsParent(booking.id, booking.pendingReschedule.id)"
            />
            <q-btn
              flat
              size="sm"
              :label="t('booking.reschedule.decline')"
              :loading="respondingId === booking.id"
              @click="handleDeclineRescheduleAsParent(booking.id, booking.pendingReschedule.id)"
            />
          </div>

          <!-- Request Change button — parent-or-self-booking-player (skillars-deferred-69 AC4:
               widened from parent-only; RescheduleService.requestReschedule's ownership check is
               self-booking-compatible). -->
          <q-btn
            v-if="
              (authStore.isParent || authStore.isPlayer) &&
              ['CONFIRMED', 'UPCOMING'].includes(booking.status) &&
              !booking.pendingReschedule
            "
            flat
            dense
            size="sm"
            :label="t('booking.reschedule.requestChange')"
            :loading="reschedulingId === booking.id"
            @click="openRescheduleDialog(booking)"
            class="q-mt-xs self-start"
          />
          <!-- Cancel button — parent-or-self-booking-player (skillars-deferred-69 AC3/AC4):
               cancelBookingAsParent has no status whitelist of its own beyond ownership (it
               transitions unconditionally; only refund eligibility is status-gated), so this uses
               the same status set as Request-Change rather than a time-based guard that doesn't
               exist server-side. -->
          <q-btn
            v-if="
              (authStore.isParent || authStore.isPlayer) &&
              ['CONFIRMED', 'UPCOMING'].includes(booking.status)
            "
            flat
            dense
            size="sm"
            color="negative"
            :label="t('booking.cancel.cta')"
            :loading="cancelingId === booking.id"
            @click="openCancelDialog(booking)"
            class="q-mt-xs self-start"
          />

          <!-- Player/parent session-prep request (skillars-deferred-155 AC7).
               skillars-deferred-155 code review: the read-only display is deliberately NOT nested
               inside the editable-status gate below. Only the *edit affordance* is status-gated;
               previously both were, so once the booking left CONFIRMED/UPCOMING the author could no
               longer see what they had written while SessionPrepService:89 kept serving it to the
               coach. -->
          <div
            v-if="(authStore.isParent || authStore.isPlayer) && booking.playerRequest"
            class="text-caption q-mt-xs"
          >
            {{ t('booking.requests.playerRequestLabel') }}: {{ booking.playerRequest }}
          </div>
          <q-btn
            v-if="
              (authStore.isParent || authStore.isPlayer) &&
              ['CONFIRMED', 'UPCOMING'].includes(booking.status)
            "
            flat
            dense
            size="sm"
            class="self-start q-pa-none q-mt-xs"
            :label="
              booking.playerRequest ? t('common.edit') : t('booking.requests.playerRequestLabel')
            "
            @click="openPlayerRequestDialog(booking)"
          />

          <!-- Story 8.1 entry point: message this booking's coach about this booking's player. -->
          <q-btn
            v-if="(authStore.isParent || authStore.isPlayer) && canMessageCoach(booking)"
            flat
            dense
            size="sm"
            icon="chat"
            class="self-start q-mt-xs"
            :data-testid="`message-coach-${booking.id}`"
            :label="t('messaging.messageCoach')"
            :loading="isStarting(booking.coachId, booking.playerId)"
            @click="startConversation(booking.coachId, booking.playerId)"
          />

          <!-- skillars-deferred-165 AC2: raise/view a dispute, parent-or-self-booking-player. -->
          <DisputeAction
            v-if="(authStore.isParent || authStore.isPlayer) && isDisputeEligible(booking.status)"
            :booking-id="booking.id"
            class="self-start q-mt-xs"
          />
        </q-item-section>
        <q-item-section side>
          <BookingStateChip :status="booking.status" :booking-id="booking.id" />
          <!-- Confirm Completion button — parent-or-self-booking-player (skillars-deferred-69 AC4:
               widened from parent-only; BookingCompletionService.confirmCompletion's ownership check
               is self-booking-compatible). -->
          <q-btn
            v-if="
              (authStore.isParent || authStore.isPlayer) &&
              booking.status === 'COMPLETED_PENDING_CONFIRMATION'
            "
            unelevated
            color="primary"
            size="sm"
            class="q-mt-sm"
            :label="t('booking.completion.confirmCompletion')"
            :loading="confirmingId === booking.id"
            @click="handleConfirmCompletion(booking.id)"
          />
        </q-item-section>
      </q-item>
    </q-list>

    <!-- Reschedule dialog -->
    <q-dialog v-model="rescheduleDialogOpen">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-h6">{{ t('booking.reschedule.dialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="rescheduleProposedStart"
            type="datetime-local"
            :label="t('booking.reschedule.proposedStart')"
            class="q-mb-lg"
            :hint="
              t('booking.reschedule.startTimezoneHint', {
                browser: browserTimezone,
                session: rescheduleBookingTimezone,
              })
            "
          />
          <!-- Read-only and derived: the backend requires a reschedule to keep the session's
               original length (a move, not a resize). Two freely-editable inputs where the second
               must exactly equal the first plus that length is a trap the parent cannot see. -->
          <q-input
            :model-value="rescheduleProposedEnd"
            type="datetime-local"
            readonly
            :label="t('booking.reschedule.proposedEnd')"
            class="q-mt-sm q-mb-lg"
            :hint="
              t('booking.reschedule.endDerivedHintWithTimezone', {
                browser: browserTimezone,
                session: rescheduleBookingTimezone,
              })
            "
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            :label="t('booking.reschedule.submit')"
            @click="submitReschedule"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Player/parent session-prep request dialog (skillars-deferred-155 AC7) -->
    <q-dialog v-model="playerRequestDialogOpen">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-h6">{{ t('booking.requests.playerRequestLabel') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input v-model="playerRequestText" type="textarea" maxlength="500" counter autofocus />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            :label="t('common.save')"
            :loading="savingPlayerRequestId === playerRequestBookingId"
            @click="submitPlayerRequest"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Cancel confirmation dialog -->
    <q-dialog v-model="cancelDialogOpen">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-h6">{{ t('booking.cancel.dialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          {{ t('booking.cancel.dialogBody') }}
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            :label="t('booking.cancel.confirm')"
            @click="submitCancelBooking"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'
import DisputeAction from 'src/components/booking/DisputeAction.vue'
import {
  useStartConversation,
  establishesMessagingRelationship,
} from 'src/composables/useStartConversation'

// skillars-deferred-165 AC2: mirrors DisputeService.ELIGIBLE_STATUSES client-side, purely for UX —
// the server is the real gate.
const DISPUTE_ELIGIBLE_STATUSES = [
  'COMPLETED',
  'CANCELLED',
  'CANCELLED_PARENT',
  'CANCELLED_COACH',
  'NO_SHOW_PLAYER',
  'NO_SHOW_COACH',
]
function isDisputeEligible(status) {
  return DISPUTE_ELIGIBLE_STATUSES.includes(status)
}

const { t, locale } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()
const authStore = useAuthStore()
const playerStore = usePlayerStore()
const { isStarting, startConversation } = useStartConversation()

// Story 8.1/8.2: a relationship-establishing booking is required, and a parent cannot open a
// conversation for an 18+ child (backend: messaging.parentalOversightNotApplicable).
function canMessageCoach(booking) {
  if (!establishesMessagingRelationship(booking)) return false
  if (authStore.isParent) {
    const child = playerStore.players.find((p) => String(p.id) === String(booking.playerId))
    if (child?.ageTier === 'ADULT') return false
  }
  return true
}

const showInMyTime = ref({})
const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone
const confirmingId = ref(null)

const rescheduleDialogOpen = ref(false)
const rescheduleBookingId = ref(null)
const rescheduleBookingTimezone = ref('')
const rescheduleProposedStart = ref('')
// Length of the booking being rescheduled, in milliseconds. The proposed end is always derived
// from it, never typed.
const rescheduleDurationMs = ref(0)

// skillars-deferred-109 AC7 (+ code review): this is a FIXED-INSTANT (elapsed-ms) delta, and it
// must stay that way. RescheduleService.java:176-184 rejects the request unless
//   Duration.between(proposedStartTime, proposedEndTime).equals(Duration.between(requestedStartTime, requestedEndTime))
// on the Instants — i.e. the submitted pair must span the exact same number of elapsed milliseconds
// as the original booking. A "start wall-clock + N wall minutes" end would drift by ±3600000 ms
// across a DST boundary and be hard-rejected. Do NOT switch this to wall-clock arithmetic.
//
// THE INSTANT IS THE SOURCE OF TRUTH FOR THE WIRE. rescheduleProposedEnd below is a DISPLAY
// projection of it, and the display string must NEVER be re-parsed back into an instant:
// toDatetimeLocal() emits a local wall-clock 'YYYY-MM-DDTHH:mm', and in a DST fall-back hour that
// wall time is AMBIGUOUS (01:30 happens twice). ECMAScript resolves an ambiguous local string to
// the PRE-transition offset, so the round-trip silently loses the hour. Concretely, in
// America/New_York a 60-minute booking proposed at 2026-11-01T01:30 renders an end of
// '2026-11-01T01:30' — identical to the start — and re-parsing both yields the same Instant, so
// the payload carries PT0S and RescheduleService hard-rejects with INVALID_SESSION_DURATION on a
// field the parent cannot even edit (the end input is read-only, :model-value). Hence the split:
// submitReschedule() reads the instant, the template reads the string.
const rescheduleProposedEndInstant = computed(() => {
  if (!rescheduleProposedStart.value || !rescheduleDurationMs.value) return null
  const start = new Date(rescheduleProposedStart.value)
  if (Number.isNaN(start.getTime())) return null
  return new Date(start.getTime() + rescheduleDurationMs.value)
})

// Display only. Can legitimately read equal to rescheduleProposedStart inside a fall-back hour;
// that is a rendering artifact of an ambiguous wall time, NOT what gets submitted.
const rescheduleProposedEnd = computed(() =>
  rescheduleProposedEndInstant.value === null
    ? ''
    : toDatetimeLocal(rescheduleProposedEndInstant.value),
)

/** datetime-local wants local wall-clock `YYYY-MM-DDTHH:mm`, which toISOString (UTC) is not. */
function toDatetimeLocal(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}
const reschedulingId = ref(null)
const respondingId = ref(null)
const cancelingId = ref(null)
const cancelDialogOpen = ref(false)
const cancelBookingId = ref(null)

const playerRequestDialogOpen = ref(false)
const playerRequestBookingId = ref(null)
const playerRequestText = ref('')
const savingPlayerRequestId = ref(null)

function openCancelDialog(booking) {
  cancelBookingId.value = booking.id
  cancelDialogOpen.value = true
}

async function submitCancelBooking() {
  cancelingId.value = cancelBookingId.value
  try {
    await bookingStore.handleCancelBooking(cancelBookingId.value)
    cancelDialogOpen.value = false
    $q.notify({ message: t('booking.cancel.success'), type: 'positive' })
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    const message = err?.response?.data?.errorMsg?.message
    // cancelBookingAsParent's payment-in-progress rejection is a raw ResponseStatusException, not an
    // OperationNotAllowedException — ApiAdvice.responseStatusExceptionHandler hardcodes its errorKey
    // to the generic "generic.requestError" (no i18n entry in any locale) and only carries the real
    // reason ("booking.paymentInProgress") as the message text. errorKey alone cannot distinguish
    // this rejection from any other ResponseStatusException, so branch on message for this one case.
    if (errorKey === 'MISSING_RIGHTS') {
      $q.notify({ message: t('booking.errors.requestNotAllowed'), type: 'negative' })
    } else if (message === 'booking.paymentInProgress') {
      $q.notify({ message: t('booking.cancel.paymentInProgress'), type: 'negative' })
    } else {
      $q.notify({ message: t('booking.cancel.failed'), type: 'negative' })
    }
  } finally {
    cancelingId.value = null
  }
}

function openPlayerRequestDialog(booking) {
  playerRequestBookingId.value = booking.id
  playerRequestText.value = booking.playerRequest ?? ''
  playerRequestDialogOpen.value = true
}

async function submitPlayerRequest() {
  savingPlayerRequestId.value = playerRequestBookingId.value
  try {
    await bookingStore.handleSetPlayerRequest(playerRequestBookingId.value, playerRequestText.value)
    playerRequestDialogOpen.value = false
    $q.notify({ message: t('booking.requests.playerRequestUpdateSuccess'), type: 'positive' })
  } catch (err) {
    // skillars-deferred-155 code review: branch on errorKey rather than swallowing the error with a
    // bare `catch {}`. Every rejection here is a 403, but they are not all retryable — BOOKING_NOT_EDITABLE
    // and SESSION_ALREADY_ENDED mean the booking moved on under a stale list, so "Please try again"
    // invited a retry that can never succeed. Mirrors submitCancelBooking's own errorKey branching
    // directly above. The list is also reloaded on those two so the now-ineligible Edit button goes away.
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'BOOKING_NOT_EDITABLE' || errorKey === 'SESSION_ALREADY_ENDED') {
      const message =
        errorKey === 'SESSION_ALREADY_ENDED'
          ? t('booking.errors.sessionAlreadyEnded')
          : t('booking.errors.notEditable')
      playerRequestDialogOpen.value = false
      $q.notify({ message, type: 'negative' })
      await bookingStore.loadParentBookings()
    } else if (errorKey === 'MISSING_RIGHTS') {
      $q.notify({ message: t('booking.errors.requestNotAllowed'), type: 'negative' })
    } else if (errorKey === 'CONCURRENT_MODIFICATION') {
      $q.notify({ message: t('booking.errors.concurrentModification'), type: 'negative' })
    } else {
      $q.notify({ message: t('booking.requests.playerRequestUpdateError'), type: 'negative' })
    }
  } finally {
    savingPlayerRequestId.value = null
  }
}

async function handleAcceptRescheduleAsParent(bookingId, rescheduleId) {
  respondingId.value = bookingId
  try {
    await bookingStore.handleAcceptRescheduleAsParent(bookingId, rescheduleId)
    $q.notify({ message: t('booking.reschedule.accepted'), type: 'positive' })
  } catch {
    $q.notify({ message: t('booking.reschedule.acceptFailed'), type: 'negative' })
  } finally {
    respondingId.value = null
  }
}

async function handleDeclineRescheduleAsParent(bookingId, rescheduleId) {
  respondingId.value = bookingId
  try {
    await bookingStore.handleDeclineRescheduleAsParent(bookingId, rescheduleId)
    $q.notify({ message: t('booking.reschedule.declined'), type: 'positive' })
  } catch {
    $q.notify({ message: t('booking.reschedule.declineFailed'), type: 'negative' })
  } finally {
    respondingId.value = null
  }
}

async function handleConfirmCompletion(bookingId) {
  confirmingId.value = bookingId
  try {
    await bookingStore.handleConfirmCompletion(bookingId)
    $q.notify({ message: t('booking.completion.confirmationSuccess'), type: 'positive' })
  } catch {
    $q.notify({ message: t('error.verificationFailed'), type: 'negative' })
  } finally {
    confirmingId.value = null
  }
}

function openRescheduleDialog(booking) {
  // Carried from the booking itself, not from the coach's current session length: a booking made
  // before session lengths existed keeps its own length, and the backend enforces exactly that.
  const start = new Date(booking.requestedStartTime)
  const end = new Date(booking.requestedEndTime)
  const durationMs =
    Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())
      ? 0
      : end.getTime() - start.getTime()

  // Without a derivable length the end field — now read-only — would sit permanently blank and the
  // dialog could never be submitted. Fail visibly at open time instead of presenting a dead end.
  if (durationMs <= 0) {
    $q.notify({ message: t('booking.reschedule.endDerivedLengthUnavailable'), type: 'negative' })
    return
  }

  rescheduleBookingId.value = booking.id
  rescheduleBookingTimezone.value = booking.canonicalTimezone
  rescheduleProposedStart.value = ''
  rescheduleDurationMs.value = durationMs
  rescheduleDialogOpen.value = true
}

async function submitReschedule() {
  if (!rescheduleProposedStart.value || rescheduleProposedEndInstant.value === null) {
    $q.notify({ message: t('booking.reschedule.requestFailed'), type: 'negative' })
    return
  }
  reschedulingId.value = rescheduleBookingId.value
  try {
    const data = {
      proposedStartTime: new Date(rescheduleProposedStart.value).toISOString(),
      // From the INSTANT, never from rescheduleProposedEnd — see the computed's comment.
      proposedEndTime: rescheduleProposedEndInstant.value.toISOString(),
    }
    await bookingStore.handleRequestReschedule(rescheduleBookingId.value, data)
    rescheduleDialogOpen.value = false
    $q.notify({ message: t('booking.reschedule.requestSent'), type: 'positive' })
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    // RescheduleService used to answer every one of these with SecurityError.MISSING_RIGHTS, so the
    // else branch below covered five distinct non-authorization rejections. skillars-deferred-31 AC3
    // split them; post-split MISSING_RIGHTS on this path carries exactly one meaning — the parent
    // does not own this booking — which is why it gets the authorization wording with no retry advice.
    if (errorKey === 'booking.invalidSessionDuration') {
      $q.notify({ message: t('booking.errors.invalidSessionDuration'), type: 'negative' })
    } else if (errorKey === 'booking.notReschedulable') {
      $q.notify({ message: t('booking.errors.notReschedulable'), type: 'negative' })
    } else if (errorKey === 'booking.startTimeInPast') {
      $q.notify({ message: t('booking.errors.startTimeInPast'), type: 'negative' })
    } else if (errorKey === 'booking.invalidTimeRange') {
      $q.notify({ message: t('booking.errors.invalidTimeRange'), type: 'negative' })
    } else if (errorKey === 'booking.slotOutsideAvailability') {
      $q.notify({ message: t('booking.errors.slotOutsideAvailability'), type: 'negative' })
    } else if (errorKey === 'booking.sessionCrossesMidnight') {
      $q.notify({ message: t('booking.errors.sessionCrossesMidnight'), type: 'negative' })
    } else if (errorKey === 'booking.rescheduleAlreadyPending') {
      $q.notify({ message: t('booking.errors.rescheduleAlreadyPending'), type: 'negative' })
    } else if (errorKey === 'MISSING_RIGHTS') {
      $q.notify({ message: t('booking.errors.requestNotAllowed'), type: 'negative' })
    } else {
      // skillars-deferred-31 AC6: the diagnostic value only. boot/axios.js rejects with the ORIGINAL
      // axios error, so `err` carries err.config.data — the serialized request body, which on this
      // path holds a minor's playerId and free-text notes. errorKey is a wire constant.
      console.warn('[booking] unmapped errorKey:', errorKey)
      $q.notify({ message: t('booking.reschedule.requestFailed'), type: 'negative' })
    }
  } finally {
    reschedulingId.value = null
  }
}

const firstBookingTimezone = computed(() => {
  return bookingStore.parentBookings[0]?.canonicalTimezone ?? null
})

function toggleTimezone(bookingId) {
  showInMyTime.value[bookingId] = !showInMyTime.value[bookingId]
}

function formatDateTime(isoString, timezone) {
  return new Intl.DateTimeFormat(locale.value, {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(isoString))
}

onMounted(() => {
  bookingStore.loadParentBookings()
})
</script>
