<template>
  <q-page class="command-center q-pa-md">
    <!-- Live session overlay -->
    <ActiveSessionScreen
      v-if="showActiveSession"
      :booking-id="activeBookingId"
      :player-name="activePlayerName"
      :session-start-time="activeSessionStart"
      :booking-status="activeBookingStatus"
      @session-ended="onSessionEnded"
      @close="handleCloseActiveSession"
    />

    <!-- Wrap-up overlay -->
    <WrapUpSequence
      v-if="showWrapUp"
      :booking-id="activeBookingId"
      :player-id="activePlayerId"
      :player-name="activePlayerName"
      :is-live-mode="isLiveMode"
      @wrap-up-complete="onWrapUpComplete"
      @cancelled="showWrapUp = false"
      @recap-still-editable="onRecapStillEditable"
    />

    <!-- Post wrap-up summary overlay -->
    <div v-if="showPostWrapUpSummary" class="post-wrap-up-overlay">
      <SessionDNAChart :booking-id="activeBookingId" variant="compact" />
      <div class="text-body1 text-center q-mt-md">{{ t('booking.completion.summaryTitle') }}</div>
    </div>
    <TimezoneNotice
      v-if="bookingStore.coachSchedule && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="bookingStore.coachSchedule.coachTimezone"
    />

    <!-- Story 7.1: "Complete your Stripe setup to accept bookings" while onboarding is not COMPLETE
         (PENDING / RESTRICTED / NOT_CONNECTED). Rendered only once the status has actually loaded —
         a failed status fetch shows nothing here (CoachPaymentSettingsPage owns that error state).
         Same rule as CoachPaymentSettingsPage's own banner; this copy adds the link to it. -->
    <q-banner
      v-if="stripeSetupIncomplete"
      rounded
      inline-actions
      class="q-mb-md stripe-setup-banner"
      data-testid="stripe-setup-banner"
    >
      <template #avatar>
        <q-icon name="mdi-alert-circle" color="warning" />
      </template>
      {{ t('payment.stripe.bannerWarning') }}
      <template #action>
        <q-btn
          flat
          dense
          no-caps
          color="primary"
          to="/coach/payment-settings"
          data-testid="stripe-setup-link"
          :label="t('coach.stripeBannerAction')"
        />
      </template>
    </q-banner>

    <!-- skillars-12-7 AC9: sessions that completed without a recap. Five-state convention: loading and
         empty render nothing (a secondary nudge must not shift the page), error is a quiet inline line
         (no toast: it would nag on every page load), data is the banner. -->
    <div
      v-if="missingRecapsError"
      class="text-caption text-negative q-mb-md"
      data-testid="missing-recaps-error"
    >
      {{ t('booking.recap.missingLoadError') }}
    </div>
    <q-banner
      v-else-if="!missingRecapsLoading && missingRecaps.count > 0"
      rounded
      class="q-mb-md missing-recaps-banner"
      data-testid="missing-recaps-banner"
    >
      <div class="text-subtitle2">
        {{ t('booking.recap.missingBanner', { count: missingRecaps.count }, missingRecaps.count) }}
      </div>
      <template #action>
        <q-btn
          flat
          dense
          data-testid="missing-recaps-toggle"
          :label="
            showMissingRecaps ? t('booking.recap.missingHide') : t('booking.recap.missingToggle')
          "
          @click="showMissingRecaps = !showMissingRecaps"
        />
      </template>
      <q-list v-if="showMissingRecaps" dense class="q-mt-sm">
        <q-item-label header class="q-px-none" data-testid="missing-recaps-title">
          {{ t('booking.recap.missingTitle') }}
        </q-item-label>
        <q-item
          v-for="item in missingRecaps.items"
          :key="item.bookingId"
          data-testid="missing-recap-item"
        >
          <q-item-section>
            <q-item-label>{{ item.playerName }}</q-item-label>
            <q-item-label caption>{{ formatRecapDate(item.sessionDate) }}</q-item-label>
          </q-item-section>
          <q-item-section side>
            <q-btn
              flat
              dense
              color="primary"
              data-testid="missing-recap-add"
              :label="
                item.awaitingWrapUp
                  ? t('booking.schedule.resumeWrapUp')
                  : t('booking.recap.addAction')
              "
              @click="openMissingRecap(item)"
            />
          </q-item-section>
        </q-item>
      </q-list>
    </q-banner>

    <div class="row items-center q-mb-md q-gutter-sm">
      <q-btn
        flat
        dense
        icon="chevron_left"
        :label="t('booking.schedule.prevWeek')"
        @click="prevWeek"
      />
      <div class="text-subtitle1">{{ t('booking.schedule.weekOf', { date: selectedWeek }) }}</div>
      <q-btn
        flat
        dense
        icon="chevron_right"
        :label="t('booking.schedule.nextWeek')"
        @click="nextWeek"
      />
      <q-space />
      <q-btn
        outline
        no-caps
        icon="inbox"
        color="primary"
        to="/coach/booking-requests"
        data-testid="booking-requests-link"
        :label="t('coach.bookingRequestsCta')"
      />
    </div>

    <div v-if="bookingStore.coachScheduleLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div v-else class="command-center__layout">
      <!-- Sidebar: active clients -->
      <div class="command-center__sidebar">
        <div class="text-subtitle2 q-mb-sm">{{ t('coach.commandCenterSidebar') }}</div>
        <div
          v-if="activeClients.length === 0"
          class="text-body2"
          style="color: var(--text-secondary)"
        >
          {{ t('coach.commandCenterNoClients') }}
        </div>
        <q-list v-else dense>
          <q-item v-for="client in activeClients" :key="client.id">
            <q-item-section>{{ client.name }}</q-item-section>
          </q-item>
        </q-list>
      </div>

      <!-- Schedule pane -->
      <div class="command-center__schedule">
        <div class="text-subtitle2 q-mb-sm">{{ t('coach.commandCenterSchedule') }}</div>
        <div
          v-if="!bookingStore.coachSchedule || bookingStore.coachSchedule.bookings.length === 0"
          class="text-body2 q-py-md"
          style="color: var(--text-secondary)"
        >
          {{ t('booking.schedule.noBookings') }}
        </div>
        <div v-else class="week-grid">
          <div v-for="dayIndex in 7" :key="dayIndex" class="week-grid__day">
            <div class="week-grid__day-header text-caption text-weight-bold">
              {{ dayLabel(dayIndex - 1) }}
            </div>
            <div
              v-for="booking in bookingsByDay[dayIndex - 1] ?? []"
              :key="booking.bookingId"
              class="week-grid__booking-block"
            >
              <BookingStateChip :status="booking.status" :booking-id="booking.bookingId" />
              <div class="text-caption">{{ booking.playerName }}</div>
              <div class="text-caption">
                {{
                  slotLabel(booking.requestedStartTime, bookingStore.coachSchedule.coachTimezone)
                }}
              </div>
              <q-btn
                v-if="booking.status === 'UPCOMING'"
                unelevated
                class="start-session-btn q-mt-xs"
                :label="t('booking.schedule.startSession')"
                :loading="startingSessionId === booking.bookingId"
                :disable="startingSessionId !== null || quickCompletingId !== null"
                @click="handleStartSession(booking)"
              />
              <q-btn
                v-if="booking.status === 'UPCOMING'"
                flat
                dense
                size="sm"
                :label="t('booking.completion.quickComplete')"
                class="q-mt-xs"
                :loading="quickCompletingId === booking.bookingId"
                :disable="startingSessionId !== null || quickCompletingId !== null"
                @click="handleQuickComplete(booking)"
              />
              <q-btn
                v-if="['CONFIRMED', 'UPCOMING'].includes(booking.status)"
                flat
                dense
                size="sm"
                :label="t('coach.sessionPrepButton')"
                class="q-mt-xs"
                @click="openSessionPrep(booking)"
              />
              <!-- wrapUpSubmitted guard: a COMPLETED booking with no session_completion_data row
                   (e.g. completed before 12.7 without a wrap-up) would 404 on GET /recap. -->
              <q-btn
                v-if="booking.status === 'COMPLETED' && booking.wrapUpSubmitted === true"
                flat
                dense
                size="sm"
                data-testid="recap-btn"
                :label="
                  booking.hasRecap
                    ? t('booking.schedule.editRecap')
                    : t('booking.schedule.addRecap')
                "
                class="q-mt-xs"
                @click="openRecapDialog(booking)"
              />
              <!-- fix/audit3-completion-dispute-integrity: also for a COMPLETED booking the family confirmed
                   before the wrap-up (awaitingWrapUp) — submitWrapUp fills in attendance, ratings and homework
                   there; the recap-only dialog would lose them (the server refuses it: booking.wrapUpAwaited). -->
              <q-btn
                v-if="
                  (booking.status === 'COMPLETED_PENDING_CONFIRMATION' &&
                    booking.wrapUpSubmitted === false) ||
                  (booking.status === 'COMPLETED' && booking.awaitingWrapUp === true)
                "
                flat
                dense
                size="sm"
                data-testid="resume-wrap-up-btn"
                :label="t('booking.schedule.resumeWrapUp')"
                class="q-mt-xs"
                @click="handleResumeWrapUp(booking)"
              />
              <q-btn
                v-if="booking.status === 'COMPLETED'"
                flat
                dense
                size="sm"
                :label="t('booking.schedule.repeatNextWeek')"
                :loading="duplicatingId === booking.bookingId"
                class="q-mt-xs"
                @click="handleRepeatNextWeek(booking)"
              />
              <q-btn
                v-if="booking.status === 'UPCOMING'"
                flat
                dense
                size="sm"
                color="negative"
                :label="t('booking.schedule.reportNoShow')"
                class="q-mt-xs"
                :loading="noShowActionId === booking.bookingId"
                @click="openNoShowPlayerDialog(booking)"
              />
              <q-btn
                v-if="['CONFIRMED', 'UPCOMING'].includes(booking.status)"
                flat
                dense
                size="sm"
                color="negative"
                :label="t('booking.schedule.cancelSession')"
                class="q-mt-xs"
                :loading="coachCancelActionId === booking.bookingId"
                @click="openCancelCoachDialog(booking)"
              />
              <template v-if="booking.pendingReschedule">
                <div class="text-caption q-mt-xs" style="color: var(--accent-warning)">
                  {{ t('booking.reschedule.pendingLabel') }}
                </div>
                <div class="text-caption q-mt-xs">
                  {{ t('booking.reschedule.proposed') }}
                  {{
                    slotLabel(
                      booking.pendingReschedule.proposedStartTime,
                      bookingStore.coachSchedule.coachTimezone,
                    )
                  }}
                </div>
                <!-- skillars-deferred-69 AC5: a coach can no longer accept/decline their OWN
                     proposal (the backend rejects it via CANNOT_RESPOND_TO_OWN_PROPOSAL) — gate
                     the buttons so they don't even render for that case. -->
                <div
                  v-if="booking.pendingReschedule.proposedBy === 'PARENT'"
                  class="row q-gutter-xs q-mt-xs"
                >
                  <q-btn
                    flat
                    dense
                    size="sm"
                    color="positive"
                    :label="t('booking.reschedule.accept')"
                    :loading="rescheduleActionId === booking.bookingId"
                    @click="handleAcceptReschedule(booking)"
                  />
                  <q-btn
                    flat
                    dense
                    size="sm"
                    color="negative"
                    :label="t('booking.reschedule.decline')"
                    :loading="rescheduleActionId === booking.bookingId"
                    @click="handleDeclineReschedule(booking)"
                  />
                </div>
              </template>
              <q-btn
                v-else-if="['CONFIRMED', 'UPCOMING'].includes(booking.status)"
                flat
                dense
                size="sm"
                :label="t('booking.reschedule.proposeNewTime')"
                class="q-mt-xs"
                @click="openCoachRescheduleDialog(booking)"
              />

              <!-- skillars-deferred-165 AC2: raise/view a dispute on an eligible booking, coach side. -->
              <DisputeAction
                v-if="isDisputeEligible(booking.status)"
                :booking-id="booking.bookingId"
                class="q-mt-xs"
              />
            </div>

            <!-- Available windows without bookings -->
            <div
              v-for="(window, wIdx) in slotsByDay[dayIndex - 1] ?? []"
              :key="wIdx"
              class="week-grid__gap-block"
            >
              <div class="text-caption" style="color: var(--text-secondary)">
                {{ window.startTime }}
              </div>
              <q-btn
                flat
                dense
                :label="t('booking.schedule.shareSlot')"
                @click="shareSlot(window)"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- Revenue panel -->
      <div class="command-center__revenue">
        <div class="text-subtitle2 q-mb-sm">{{ t('booking.revenue.projectedTitle') }}</div>
        <div v-if="bookingStore.coachSchedule" class="revenue-panel">
          <div class="revenue-panel__row">
            <span>{{ t('booking.revenue.gross') }}</span>
            <span>€{{ formatCurrency(bookingStore.coachSchedule.projectedGrossRevenue) }}</span>
          </div>
          <div class="revenue-panel__row revenue-panel__row--deduction">
            <span>{{ t('booking.revenue.commission', { rate: commissionRatePercent }) }}</span>
            <span>– €{{ formatCurrency(bookingStore.coachSchedule.commissionDeduction) }}</span>
          </div>
          <q-separator class="q-my-sm" />
          <div class="revenue-panel__row revenue-panel__row--net text-weight-bold">
            <span>{{ t('booking.revenue.net') }}</span>
            <span>€{{ formatCurrency(bookingStore.coachSchedule.projectedNetRevenue) }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Propose New Time dialog (skillars-deferred-69 AC5) -->
    <q-dialog v-model="coachRescheduleDialogOpen">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-h6">{{ t('booking.reschedule.dialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="coachRescheduleProposedStart"
            type="datetime-local"
            :label="t('booking.reschedule.proposedStart')"
            class="q-mb-lg"
            :hint="
              t('booking.reschedule.startTimezoneHint', {
                browser: browserTimezone,
                session: coachRescheduleTimezone,
              })
            "
          />
          <q-input
            :model-value="coachRescheduleProposedEnd"
            type="datetime-local"
            readonly
            :label="t('booking.reschedule.proposedEnd')"
            class="q-mt-sm q-mb-lg"
            :hint="
              t('booking.reschedule.endDerivedHintWithTimezone', {
                browser: browserTimezone,
                session: coachRescheduleTimezone,
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
            :loading="rescheduleActionId === coachRescheduleBookingId"
            :disable="rescheduleActionId === coachRescheduleBookingId"
            @click="submitCoachReschedule"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <CancelCoachModal v-model="cancelCoachModalOpen" @confirm="handleCoachCancelConfirm" />

    <!-- Report No-Show confirm dialog -->
    <q-dialog v-model="noShowPlayerDialogOpen">
      <q-card style="min-width: 320px">
        <q-card-section>
          <div class="text-h6">{{ t('cancellation.noShowPlayerConfirmTitle') }}</div>
        </q-card-section>
        <q-card-section>
          {{ t('cancellation.noShowPlayerConfirmBody') }}
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="negative"
            :label="t('common.confirm')"
            @click="confirmNoShowPlayer"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Session recap add/edit dialog (skillars-12-7 AC6) -->
    <SessionRecapDialog
      v-model="showRecapDialog"
      :booking-id="recapBookingId"
      :player-name="recapPlayerName"
      :prefill="recapPrefill"
      @saved="onRecapSaved"
    />

    <!-- Session Prep Card dialog (skillars-12-3 AC5/AC6) -->
    <q-dialog v-model="showSessionPrep">
      <SessionPrepCard :booking-id="activePrepBookingId" :player-name="activePrepPlayerName" />
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import { usePaymentStore } from 'src/stores/payment.store'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'
import ActiveSessionScreen from 'src/components/booking/ActiveSessionScreen.vue'
import WrapUpSequence from 'src/components/booking/WrapUpSequence.vue'
import SessionDNAChart from 'src/components/booking/SessionDNAChart.vue'
import CancelCoachModal from 'src/components/booking/CancelCoachModal.vue'
import SessionPrepCard from 'src/components/booking/SessionPrepCard.vue'
import SessionRecapDialog from 'src/components/booking/SessionRecapDialog.vue'
import DisputeAction from 'src/components/booking/DisputeAction.vue'

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
import { getMissingRecaps } from 'src/api/booking.api'
import { getProfileBuilderStatus } from 'src/api/marketplace.api'

const { t, locale } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()
const authStore = useAuthStore()
const paymentStore = usePaymentStore()

// Story 7.1 banner. Only a status loaded by THIS mount's fetch, that is not COMPLETE, counts as
// incomplete. payment.store keeps the previous stripeStatus while a refetch is in flight and after
// a failed one (and is not reset on logout), so a stale value — e.g. another coach's PENDING from
// earlier in the tab — is ignored while loading or errored rather than nagging a fully-onboarded
// coach.
const stripeStatusFetched = ref(false)
const stripeSetupIncomplete = computed(() => {
  if (!stripeStatusFetched.value || paymentStore.error.stripeStatus) return false
  const status = paymentStore.stripeStatus
  return status != null && status.onboardingStatus !== 'COMPLETE'
})

const showActiveSession = ref(false)
const showWrapUp = ref(false)
const showPostWrapUpSummary = ref(false)
const activeBookingId = ref(null)
const activePlayerName = ref('')
const activeSessionStart = ref('')
const activePlayerId = ref(null)
const isLiveMode = ref(true)
const activeBookingStatus = ref('IN_PROGRESS')
const duplicatingId = ref(null)
const rescheduleActionId = ref(null)
const startingSessionId = ref(null)
const quickCompletingId = ref(null)

const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone
const coachRescheduleDialogOpen = ref(false)
const coachRescheduleBookingId = ref(null)
const coachRescheduleTimezone = ref('')
const coachRescheduleProposedStart = ref('')
// Length of the booking being rescheduled, in milliseconds. The proposed end is always derived
// from it, never typed — mirrors ParentBookingsPage.vue's own reschedule dialog.
const coachRescheduleDurationMs = ref(0)

const cancelCoachModalOpen = ref(false)
const cancelCoachBookingId = ref(null)
const coachCancelActionId = ref(null)
const noShowPlayerDialogOpen = ref(false)
const noShowPlayerBookingId = ref(null)
const noShowPlayerRequestedStartTime = ref(null)
const noShowActionId = ref(null)

const showSessionPrep = ref(false)
// skillars-12-7: recap dialog + missing-recap nudge
const showRecapDialog = ref(false)
const recapBookingId = ref(null)
const recapPlayerName = ref('')
// skillars-deferred-163 AC2.2: text recovered from a wrap-up the coach could no longer submit
// (the booking was already force-completed) -- carried into SessionRecapDialog's prefill prop.
const recapPrefill = ref(null)
const missingRecaps = ref({ count: 0, items: [] })
const showMissingRecaps = ref(false)
const missingRecapsLoading = ref(true)
const missingRecapsError = ref(false)
const activePrepBookingId = ref(null)
const activePrepPlayerName = ref('')

const coachRescheduleProposedEnd = computed(() => {
  if (!coachRescheduleProposedStart.value || !coachRescheduleDurationMs.value) return ''
  const start = new Date(coachRescheduleProposedStart.value)
  if (Number.isNaN(start.getTime())) return ''
  return toDatetimeLocal(new Date(start.getTime() + coachRescheduleDurationMs.value))
})

/** datetime-local wants local wall-clock `YYYY-MM-DDTHH:mm`, which toISOString (UTC) is not. */
function toDatetimeLocal(date) {
  const pad = (n) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

let sessionEventSource = null

function startSessionSse(bookingId) {
  sessionEventSource?.close()
  const es = new EventSource(`/api/bookings/${bookingId}/events`, { withCredentials: true })
  es.addEventListener('status', (e) => {
    activeBookingStatus.value = e.data
  })
  sessionEventSource = es
}

function stopSessionSse() {
  sessionEventSource?.close()
  sessionEventSource = null
}

onUnmounted(stopSessionSse)

function localDateString(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function addDaysToIsoDate(isoDate, days) {
  const [y, m, d] = isoDate.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + days)
  return localDateString(dt)
}

function currentMonday() {
  const today = new Date()
  const dow = today.getDay()
  const diff = dow === 0 ? -6 : 1 - dow
  const monday = new Date(today)
  monday.setDate(today.getDate() + diff)
  return localDateString(monday)
}

const selectedWeek = ref(currentMonday())

onMounted(() => {
  bookingStore.loadCoachSchedule(selectedWeek.value)
  loadMissingRecaps()
  loadStripeStatus()
})

async function loadStripeStatus() {
  // fetchStripeStatus never rejects (it catches into paymentStore.error.stripeStatus).
  await paymentStore.fetchStripeStatus()
  stripeStatusFetched.value = true
}

function prevWeek() {
  selectedWeek.value = addDaysToIsoDate(selectedWeek.value, -7)
  bookingStore.loadCoachSchedule(selectedWeek.value)
}

function nextWeek() {
  selectedWeek.value = addDaysToIsoDate(selectedWeek.value, 7)
  bookingStore.loadCoachSchedule(selectedWeek.value)
}

const activeClients = computed(() => {
  const bookings = bookingStore.coachSchedule?.bookings ?? []
  const seen = new Set()
  return bookings
    .filter((b) => {
      if (seen.has(b.playerId)) return false
      seen.add(b.playerId)
      return true
    })
    .map((b) => ({ id: b.playerId, name: b.playerName }))
})

// skillars-deferred-90 AC11: weekday index (0=Monday … 6=Sunday) of `instant` as seen in
// `timezone`, computed NUMERICALLY so there is no hardcoded-English formatter paired with a
// hardcoded-English array to keep in sync. 'en-CA' here is a machine format (ISO YYYY-MM-DD
// used only as an intermediate), not user-facing text — same allowed pattern as WeeklyCalendar.
function getDayIndex(instant, timezone) {
  const isoDate = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(instant))
  const utcDow = new Date(`${isoDate}T00:00:00Z`).getUTCDay() // 0=Sunday … 6=Saturday
  return (utcDow + 6) % 7 // shift to 0=Monday … 6=Sunday
}

function dayLabel(index) {
  const monday = new Date('2024-01-01T00:00:00')
  const d = new Date(monday)
  d.setDate(d.getDate() + index)
  return new Intl.DateTimeFormat(locale.value, { weekday: 'short' }).format(d)
}

const bookingsByDay = computed(() => {
  const schedule = bookingStore.coachSchedule
  if (!schedule) return {}
  const tz = schedule.coachTimezone
  const groups = {}
  for (const b of schedule.bookings) {
    const idx = getDayIndex(b.requestedStartTime, tz)
    if (!groups[idx]) groups[idx] = []
    groups[idx].push(b)
  }
  return groups
})

// Groups availability windows by 0-based day index; startTime is a wall-clock string
// in the coach's timezone (e.g. "09:00") — NOT converted to UTC.
const slotsByDay = computed(() => {
  const windows = bookingStore.coachSchedule?.availabilityWindows ?? []
  const groups = {}
  for (const w of windows) {
    const idx = w.dayOfWeek - 1
    if (!groups[idx]) groups[idx] = []
    groups[idx].push(w)
  }
  return groups
})

const commissionRatePercent = computed(() => {
  const rate = bookingStore.coachSchedule?.commissionRate
  return rate != null ? Math.round(rate * 100) : 0
})

function slotLabel(instant, timezone) {
  return new Intl.DateTimeFormat(locale.value, {
    timeZone: timezone,
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(instant))
}

function formatCurrency(value) {
  if (value == null) return '0.00'
  return Number(value).toFixed(2)
}

// The coach's own CoachProfile id — the public profile route is keyed on it, not on the user id.
// Resolved lazily (only a share needs it) and cached for the page's lifetime.
const ownCoachId = ref(null)
async function resolveOwnCoachId() {
  if (!ownCoachId.value) {
    const status = await getProfileBuilderStatus()
    ownCoachId.value = status?.coachId ?? null
  }
  return ownCoachId.value
}

// Deep link to the coach's public profile (hash router: '#/coaches/<id>') carrying the shared slot
// as ?slot=<coach-local YYYY-MM-DDTHH:mm>; the profile page shows it and forwards it to the booking
// request page, which preselects the matching slot. The date is the window's ISO dayOfWeek
// (Monday = 1) within the displayed week (selectedWeek is that week's Monday).
function generateSlotLink(coachId, slotWindow) {
  const date = addDaysToIsoDate(selectedWeek.value, (slotWindow?.dayOfWeek ?? 1) - 1)
  const time = String(slotWindow?.startTime ?? '').slice(0, 5)
  const base = window.location.origin + window.location.pathname
  const slot = /^\d{2}:\d{2}$/.test(time) ? `?slot=${encodeURIComponent(`${date}T${time}`)}` : ''
  return `${base}#/coaches/${coachId}${slot}`
}

// skillars-deferred-151 AC6: client-side pre-check for immediate UX feedback (skip the API call
// entirely for the common case); the backend's own SESSION_NOT_YET_STARTED guard is the real
// enforcement and still fires for the race case (pre-check passes, then the clock ticks past a
// page the coach left open, or they are simply a few seconds ahead of the server).
//
// skillars-deferred-151 review Decision: a zero-tolerance comparison against the device clock
// hard-blocks a coach whose laptop clock is merely slow (e.g. NTP drift after sleep), even though
// the server would allow the action. SESSION_START_GRACE_MS absorbs ordinary skew. An unparseable
// requestedStartTime previously sailed straight past this guard (NaN > now is false) — fail closed
// instead and let the authoritative server guard be the one to decide.
const SESSION_START_GRACE_MS = 2 * 60 * 1000

function isBeforeScheduledStart(booking) {
  const start = new Date(booking.requestedStartTime).getTime()
  if (Number.isNaN(start)) return true
  return start - SESSION_START_GRACE_MS > Date.now()
}

async function handleStartSession(booking) {
  if (isBeforeScheduledStart(booking)) {
    $q.notify({ message: t('booking.errors.sessionNotYetStarted'), type: 'negative' })
    return
  }
  startingSessionId.value = booking.bookingId
  try {
    await bookingStore.handleStartSession(booking.bookingId)
    activeBookingId.value = String(booking.bookingId)
    activePlayerName.value = booking.playerName
    activeSessionStart.value = booking.requestedStartTime
    activePlayerId.value = booking.playerId
    isLiveMode.value = true
    activeBookingStatus.value = 'IN_PROGRESS'
    startSessionSse(booking.bookingId)
    showActiveSession.value = true
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'booking.sessionNotYetStarted') {
      $q.notify({ message: t('booking.errors.sessionNotYetStarted'), type: 'negative' })
    } else {
      $q.notify({ message: t('booking.completion.actionError'), type: 'negative' })
    }
  } finally {
    startingSessionId.value = null
  }
}

async function handleQuickComplete(booking) {
  if (isBeforeScheduledStart(booking)) {
    $q.notify({ message: t('booking.errors.sessionNotYetStarted'), type: 'negative' })
    return
  }
  quickCompletingId.value = booking.bookingId
  try {
    await bookingStore.handleInitiateQuickComplete(booking.bookingId)
    activeBookingId.value = String(booking.bookingId)
    activePlayerName.value = booking.playerName
    activePlayerId.value = booking.playerId
    isLiveMode.value = false
    showWrapUp.value = true
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'booking.sessionNotYetStarted') {
      $q.notify({ message: t('booking.errors.sessionNotYetStarted'), type: 'negative' })
    } else {
      $q.notify({ message: t('booking.completion.actionError'), type: 'negative' })
    }
  } finally {
    quickCompletingId.value = null
  }
}

function onSessionEnded() {
  stopSessionSse()
  showActiveSession.value = false
  showWrapUp.value = true
}

function handleCloseActiveSession() {
  stopSessionSse()
  showActiveSession.value = false
}

function onWrapUpComplete() {
  showWrapUp.value = false
  showPostWrapUpSummary.value = true
  setTimeout(() => {
    showPostWrapUpSummary.value = false
    bookingStore.loadCoachSchedule(selectedWeek.value)
    loadMissingRecaps()
  }, 3000)
}

// Post-mutation refresh contract for the reschedule handlers (skillars-deferred-31 AC1): refresh
// FIRST, then toast, so the message describes state the coach can already see; and after every
// refresh — success path included — warn if it failed, because loadCoachSchedule swallows its own
// failures into coachScheduleError and no component renders that ref (see booking.store.js).
//
// Takes the refresh's OWN return value rather than re-reading bookingStore.coachScheduleError. This
// page fires loadCoachSchedule from several places that do not await it — the week-selector
// handlers, onMounted, and onWrapUpComplete's 3-second setTimeout — any of which can reset or
// overwrite that shared ref between a handler's await and its check.
function notifyIfScheduleStale(refreshed) {
  if (!refreshed) {
    $q.notify({ type: 'warning', message: t('booking.errors.listMayBeStale') })
  }
}

// skillars-deferred-151 AC4: shared mutation->refresh->toast helper, DRY cleanup only — NOT a bug
// fix (see Finding 4). loadCoachSchedule (booking.store.js:360-368) carries an explicit "NEVER
// RETHROW" contract comment: two independent 2026-08-18 review passes already filed and withdrew
// an "unguarded refresh -> unhandled rejection" defect against call sites of this loader. A fourth
// pass should read that comment before refiling it, not re-derive the same false claim.
//
// Each caller keeps its own loading-ref `finally` block (actionIdRef/actionId here) and any
// handler-specific pre-validation/dialog-close logic the helper has no reason to know about —
// dialog-close-on-success lives inside the handler's own `mutate` callback (see
// submitCoachReschedule), not in this helper.
async function runCoachAction({
  actionIdRef,
  actionId,
  mutate,
  successMessage,
  errorMessages = {},
  genericErrorMessage,
  refreshOnError = true,
}) {
  actionIdRef.value = actionId
  try {
    await mutate()
    notifyIfScheduleStale(await bookingStore.loadCoachSchedule(selectedWeek.value))
    $q.notify({ message: t(successMessage), type: 'positive' })
  } catch (err) {
    // submitCoachReschedule is the one outlier: its catch does NOT refresh the schedule at all,
    // unlike the other four handlers (`refreshOnError: false`).
    const refreshed = refreshOnError
      ? await bookingStore.loadCoachSchedule(selectedWeek.value)
      : null
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    const messageKey =
      (errorKey && Object.hasOwn(errorMessages, errorKey) && errorMessages[errorKey]) ||
      genericErrorMessage
    $q.notify({ type: 'negative', message: t(messageKey) })
    if (refreshOnError) {
      notifyIfScheduleStale(refreshed)
    }
  } finally {
    actionIdRef.value = null
  }
}

async function handleAcceptReschedule(booking) {
  // RescheduleService.acceptReschedule used to answer four of these with MISSING_RIGHTS
  // (skillars-deferred-31 AC3). Post-split, MISSING_RIGHTS here means exactly one thing — this
  // coach does not own the booking — so it gets the authorization wording, not retry advice.
  // rescheduleNotPending covers both PENDING checks in that method: the unlocked early-out and the
  // locked re-read that loses the race against a concurrent decline.
  await runCoachAction({
    actionIdRef: rescheduleActionId,
    actionId: booking.bookingId,
    mutate: () =>
      bookingStore.handleAcceptReschedule(booking.bookingId, booking.pendingReschedule.id),
    successMessage: 'booking.reschedule.accepted',
    errorMessages: {
      'booking.coachUnavailable': 'booking.errors.coachUnavailable',
      'booking.slotUnavailable': 'booking.errors.slotUnavailable',
      'booking.rescheduleNotPending': 'booking.errors.rescheduleNotPending',
      'booking.notReschedulable': 'booking.errors.notReschedulable',
      'booking.startTimeInPast': 'booking.errors.startTimeInPast',
      'booking.slotOutsideAvailability': 'booking.errors.slotOutsideAvailability',
      // fix/audit3-booking-scheduling-integrity: the family's pack is paused at the proposed time.
      'payment.packPaused': 'payment.sessionPack.coachPackPaused',
      MISSING_RIGHTS: 'booking.errors.requestNotAllowed',
    },
    genericErrorMessage: 'booking.reschedule.acceptFailed',
  })
}

async function handleDeclineReschedule(booking) {
  // declineReschedule's not-PENDING rejection (the ordinary double-click, or losing the race to a
  // concurrent accept) is its own wire code and deserves its own message; everything else keeps
  // the generic decline failure.
  await runCoachAction({
    actionIdRef: rescheduleActionId,
    actionId: booking.bookingId,
    mutate: () =>
      bookingStore.handleDeclineReschedule(booking.bookingId, booking.pendingReschedule.id),
    successMessage: 'booking.reschedule.declined',
    errorMessages: {
      'booking.rescheduleNotPending': 'booking.errors.rescheduleNotPending',
    },
    genericErrorMessage: 'booking.reschedule.declineFailed',
  })
}

function openCancelCoachDialog(booking) {
  cancelCoachBookingId.value = booking.bookingId
  cancelCoachModalOpen.value = true
}

async function handleCoachCancelConfirm(reason) {
  await runCoachAction({
    actionIdRef: coachCancelActionId,
    actionId: cancelCoachBookingId.value,
    mutate: () => bookingStore.handleCoachCancelBooking(cancelCoachBookingId.value, reason),
    successMessage: 'cancellation.coachCancelSuccess',
    errorMessages: {
      'booking.alreadyCompleted': 'cancellation.alreadyCompleted',
      'booking.invalidTransition': 'cancellation.invalidTransition',
    },
    genericErrorMessage: 'cancellation.coachCancelFailed',
  })
}

function openNoShowPlayerDialog(booking) {
  noShowPlayerBookingId.value = booking.bookingId
  noShowPlayerRequestedStartTime.value = booking.requestedStartTime
  noShowPlayerDialogOpen.value = true
}

function formatRecapDate(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(date)
}

// A failed nudge lookup is non-fatal: it surfaces as a quiet inline line instead of a notify (which
// would nag on every page load for a secondary feature). Only the first load counts as "loading" — a
// refresh after a save keeps the current banner on screen until the new count arrives.
async function loadMissingRecaps() {
  missingRecapsError.value = false
  try {
    const res = await getMissingRecaps()
    const data = res?.data ?? res
    missingRecaps.value = { count: data?.count ?? 0, items: data?.items ?? [] }
  } catch {
    missingRecaps.value = { count: 0, items: [] }
    missingRecapsError.value = true
  } finally {
    missingRecapsLoading.value = false
  }
}

// fix/audit3-completion-dispute-integrity: a session the family confirmed before the wrap-up resumes the
// wrap-up (attendance, ratings, homework); every other missing recap opens the recap dialog.
function openMissingRecap(item) {
  if (item.awaitingWrapUp === true) {
    handleResumeWrapUp({
      bookingId: item.bookingId,
      playerName: item.playerName,
      playerId: item.playerId,
      startedLive: false,
    })
    return
  }
  openRecapDialog({ bookingId: item.bookingId, playerName: item.playerName })
}

function openRecapDialog(booking) {
  recapBookingId.value = String(booking.bookingId)
  recapPlayerName.value = booking.playerName ?? ''
  recapPrefill.value = null
  showRecapDialog.value = true
}

// skillars-deferred-163 AC2.1/AC2.2: the wrap-up wizard caught a dedicated error (the booking was
// already force-completed) and handed back the coach's typed-but-unsubmittable recap text. Close
// the wizard and redirect into the recap dialog -- this booking's recap can still be saved there.
function onRecapStillEditable(payload) {
  showWrapUp.value = false
  recapBookingId.value = String(payload.bookingId)
  recapPlayerName.value = payload.playerName ?? ''
  recapPrefill.value = {
    whatWeDid: payload.whatWeDid,
    whatsNext: payload.whatsNext,
    whatWentWell: payload.whatWentWell,
  }
  showRecapDialog.value = true
  $q.notify({ type: 'warning', message: t('booking.errors.recapStillEditableAfterForceComplete') })
  // Code review 2026-10-27: the booking is already COMPLETED -- refresh now, not only on save, so a coach
  // who closes the dialog unsaved is not left with a stale "resume wrap-up" pointing at the same dead end.
  loadMissingRecaps()
  bookingStore.loadCoachSchedule(selectedWeek.value)
}

async function onRecapSaved() {
  await Promise.all([loadMissingRecaps(), bookingStore.loadCoachSchedule(selectedWeek.value)])
}

// skillars-12-7 AC7a: re-open the wizard for a wrap-up the coach abandoned. submitWrapUp accepts
// COMPLETED_PENDING_CONFIRMATION and the parent-confirmation flow carries on. Both a quick-complete AND a
// session ended live land in that status, so the resume keeps the original mode from the schedule's
// startedLive flag (null/false → quick-complete wizard).
function handleResumeWrapUp(booking) {
  activeBookingId.value = String(booking.bookingId)
  activePlayerName.value = booking.playerName
  activePlayerId.value = booking.playerId
  isLiveMode.value = booking.startedLive === true
  showWrapUp.value = true
}

function openSessionPrep(booking) {
  activePrepBookingId.value = String(booking.bookingId)
  activePrepPlayerName.value = booking.playerName
  showSessionPrep.value = true
}

async function confirmNoShowPlayer() {
  noShowPlayerDialogOpen.value = false
  // skillars-deferred-151 AC6: same client-side pre-check as handleStartSession/handleQuickComplete
  // — this decision explicitly reverses spec-coach-booking-cancel-no-show.md's prior "Never add a
  // client-side too-early guard on no-show reporting" note (see recordNoShowPlayer's own backend
  // comment for the same reversal).
  if (isBeforeScheduledStart({ requestedStartTime: noShowPlayerRequestedStartTime.value })) {
    $q.notify({ message: t('booking.errors.noShowTooEarly'), type: 'negative' })
    return
  }
  await runCoachAction({
    actionIdRef: noShowActionId,
    actionId: noShowPlayerBookingId.value,
    mutate: () => bookingStore.handleRecordNoShowPlayer(noShowPlayerBookingId.value),
    successMessage: 'cancellation.noShowPlayerSuccess',
    errorMessages: {
      'booking.alreadyCompleted': 'cancellation.alreadyCompleted',
      'booking.invalidTransition': 'cancellation.invalidTransition',
      'booking.noShowTooEarly': 'booking.errors.noShowTooEarly',
    },
    genericErrorMessage: 'cancellation.noShowPlayerFailed',
  })
}

function openCoachRescheduleDialog(booking) {
  // Carried from the booking itself, matching ParentBookingsPage.vue's openRescheduleDialog.
  const start = new Date(booking.requestedStartTime)
  const end = new Date(booking.requestedEndTime)
  const durationMs =
    Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())
      ? 0
      : end.getTime() - start.getTime()

  if (durationMs <= 0) {
    $q.notify({ message: t('booking.reschedule.endDerivedLengthUnavailable'), type: 'negative' })
    return
  }

  coachRescheduleBookingId.value = booking.bookingId
  coachRescheduleTimezone.value = booking.canonicalTimezone
  coachRescheduleProposedStart.value = ''
  coachRescheduleDurationMs.value = durationMs
  coachRescheduleDialogOpen.value = true
}

async function submitCoachReschedule() {
  if (!coachRescheduleProposedStart.value || !coachRescheduleProposedEnd.value) {
    $q.notify({ message: t('booking.reschedule.requestFailed'), type: 'negative' })
    return
  }
  // The one genuine outlier among the five handlers (Finding 4): its catch does NOT refresh the
  // schedule at all, unlike the other four — `refreshOnError: false` below. Dialog-close-on-success
  // lives inside `mutate`, still within runCoachAction's own try, since the helper has no reason to
  // know about this handler's dialog state.
  await runCoachAction({
    actionIdRef: rescheduleActionId,
    actionId: coachRescheduleBookingId.value,
    mutate: async () => {
      const data = {
        proposedStartTime: new Date(coachRescheduleProposedStart.value).toISOString(),
        proposedEndTime: new Date(coachRescheduleProposedEnd.value).toISOString(),
      }
      await bookingStore.handleRequestRescheduleAsCoach(coachRescheduleBookingId.value, data)
      coachRescheduleDialogOpen.value = false
    },
    successMessage: 'booking.reschedule.requestSent',
    // Same error-key branching shape as ParentBookingsPage.vue's submitReschedule.
    errorMessages: {
      'booking.invalidSessionDuration': 'booking.errors.invalidSessionDuration',
      'booking.notReschedulable': 'booking.errors.notReschedulable',
      'booking.startTimeInPast': 'booking.errors.startTimeInPast',
      'booking.invalidTimeRange': 'booking.errors.invalidTimeRange',
      'booking.slotOutsideAvailability': 'booking.errors.slotOutsideAvailability',
      'booking.sessionCrossesMidnight': 'booking.errors.sessionCrossesMidnight',
      'booking.rescheduleAlreadyPending': 'booking.errors.rescheduleAlreadyPending',
      // fix/audit3-booking-scheduling-integrity: booking horizon and pack pause apply to the new start.
      'booking.beyondBookingHorizon': 'booking.errors.beyondBookingHorizon',
      'payment.packPaused': 'payment.sessionPack.coachPackPaused',
      MISSING_RIGHTS: 'booking.errors.requestNotAllowed',
    },
    genericErrorMessage: 'booking.reschedule.requestFailed',
    refreshOnError: false,
  })
}

async function handleRepeatNextWeek(booking) {
  if (duplicatingId.value !== null) return
  await runCoachAction({
    actionIdRef: duplicatingId,
    actionId: booking.bookingId,
    mutate: () => bookingStore.handleDuplicateNextWeek(booking.bookingId),
    successMessage: 'booking.schedule.repeatProposed',
    // fix/audit3-booking-scheduling-integrity: the slot is already held (an earlier proposal or a pending
    // request), or the family's pack is paused / fully reserved for that week.
    errorMessages: {
      'booking.slotUnavailable': 'booking.errors.slotUnavailable',
      'payment.packPaused': 'payment.sessionPack.coachPackPaused',
      'payment.packSessionsReserved': 'payment.sessionPack.coachPackSessionsReserved',
    },
    genericErrorMessage: 'booking.schedule.repeatFailed',
  })
}

async function shareSlot(slotWindow) {
  try {
    const coachId = await resolveOwnCoachId()
    if (!coachId) throw new Error('No coach profile id for this account')
    await navigator.clipboard.writeText(generateSlotLink(coachId, slotWindow))
    $q.notify({ message: t('booking.schedule.slotCopied'), type: 'positive' })
  } catch {
    $q.notify({ message: t('booking.schedule.slotCopyFailed'), type: 'negative' })
  }
}
</script>

<style lang="scss" scoped>
.stripe-setup-banner {
  background: rgba(255, 165, 0, 0.15);
}

.command-center__layout {
  display: grid;
  grid-template-columns: 260px 1fr 280px;
  gap: 16px;

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .command-center__layout {
    > .command-center__revenue {
      order: 1;
    }
    > .command-center__schedule {
      order: 2;
    }
    > .command-center__sidebar {
      order: 3;
    }
  }
}

.command-center__sidebar,
.command-center__schedule,
.command-center__revenue {
  background: var(--surface-raised);
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  padding: 16px;
}

.week-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 8px;

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
  }
}

.week-grid__day-header {
  text-align: center;
  padding: 4px 0;
  border-bottom: 1px solid var(--border-subtle);
  margin-bottom: 8px;
}

.week-grid__booking-block {
  background: var(--surface-card);
  border: 1px solid var(--accent-primary);
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 6px;
}

.week-grid__gap-block {
  background: var(--surface-raised);
  border: 1px dashed var(--border-subtle);
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 6px;
}

.post-wrap-up-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 3000;
  background: var(--surface-page);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.start-session-btn {
  width: 100%;
  min-height: 56px;
  background: var(--accent-primary);
  color: #fff;

  @media (max-width: 768px) {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    z-index: 100;
    border-radius: 0;
    margin: 0;
  }
}

.revenue-panel__row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;

  &--deduction {
    color: var(--color-error);
  }

  &--net {
    font-size: 15px;
  }
}
</style>
