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

    <div class="row items-center q-mb-md q-gutter-sm">
      <q-btn flat dense icon="chevron_left" :label="t('booking.schedule.prevWeek')" @click="prevWeek" />
      <div class="text-subtitle1">{{ t('booking.schedule.weekOf', { date: selectedWeek }) }}</div>
      <q-btn flat dense icon="chevron_right" :label="t('booking.schedule.nextWeek')" @click="nextWeek" />
    </div>

    <div v-if="bookingStore.coachScheduleLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div v-else class="command-center__layout">
      <!-- Sidebar: active clients -->
      <div class="command-center__sidebar">
        <div class="text-subtitle2 q-mb-sm">{{ t('coach.commandCenterSidebar') }}</div>
        <div v-if="activeClients.length === 0" class="text-body2" style="color: var(--text-secondary)">
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
        <div v-if="!bookingStore.coachSchedule || bookingStore.coachSchedule.bookings.length === 0"
             class="text-body2 q-py-md" style="color: var(--text-secondary)">
          {{ t('booking.schedule.noBookings') }}
        </div>
        <div v-else class="week-grid">
          <div v-for="dayIndex in 7" :key="dayIndex" class="week-grid__day">
            <div class="week-grid__day-header text-caption text-weight-bold">
              {{ dayLabel(dayIndex - 1) }}
            </div>
            <div
              v-for="booking in (bookingsByDay[dayIndex - 1] ?? [])"
              :key="booking.bookingId"
              class="week-grid__booking-block"
            >
              <BookingStateChip :status="booking.status" :booking-id="booking.bookingId" />
              <div class="text-caption">{{ booking.playerName }}</div>
              <div class="text-caption">
                {{ slotLabel(booking.requestedStartTime, bookingStore.coachSchedule.coachTimezone) }}
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
                flat dense size="sm"
                :label="t('booking.completion.quickComplete')"
                class="q-mt-xs"
                :loading="quickCompletingId === booking.bookingId"
                :disable="startingSessionId !== null || quickCompletingId !== null"
                @click="handleQuickComplete(booking)"
              />
              <q-btn
                v-if="booking.status === 'COMPLETED'"
                flat dense size="sm"
                :label="t('booking.schedule.repeatNextWeek')"
                :loading="duplicatingId === booking.bookingId"
                class="q-mt-xs"
                @click="handleRepeatNextWeek(booking)"
              />
              <template v-if="booking.pendingReschedule">
                <div class="text-caption q-mt-xs" style="color: var(--accent-warning)">
                  {{ t('booking.reschedule.pendingLabel') }}
                </div>
                <div class="text-caption q-mt-xs">
                  {{ t('booking.reschedule.proposed') }}
                  {{ slotLabel(booking.pendingReschedule.proposedStartTime, bookingStore.coachSchedule.coachTimezone) }}
                </div>
                <!-- skillars-deferred-69 AC5: a coach can no longer accept/decline their OWN
                     proposal (the backend rejects it via CANNOT_RESPOND_TO_OWN_PROPOSAL) — gate
                     the buttons so they don't even render for that case. -->
                <div v-if="booking.pendingReschedule.proposedBy === 'PARENT'" class="row q-gutter-xs q-mt-xs">
                  <q-btn
                    flat dense size="sm" color="positive"
                    :label="t('booking.reschedule.accept')"
                    :loading="rescheduleActionId === booking.bookingId"
                    @click="handleAcceptReschedule(booking)"
                  />
                  <q-btn
                    flat dense size="sm" color="negative"
                    :label="t('booking.reschedule.decline')"
                    :loading="rescheduleActionId === booking.bookingId"
                    @click="handleDeclineReschedule(booking)"
                  />
                </div>
              </template>
              <q-btn
                v-else-if="['CONFIRMED', 'UPCOMING'].includes(booking.status)"
                flat dense size="sm"
                :label="t('booking.reschedule.proposeNewTime')"
                class="q-mt-xs"
                @click="openCoachRescheduleDialog(booking)"
              />
            </div>

            <!-- Available windows without bookings -->
            <div
              v-for="(window, wIdx) in (slotsByDay[dayIndex - 1] ?? [])"
              :key="wIdx"
              class="week-grid__gap-block"
            >
              <div class="text-caption" style="color: var(--text-secondary)">
                {{ window.startTime }}
              </div>
              <q-btn
                flat dense
                :label="t('booking.schedule.shareSlot')"
                @click="shareSlot()"
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
          <q-input v-model="coachRescheduleProposedStart" type="datetime-local"
                   :label="t('booking.reschedule.proposedStart')"
                   class="q-mb-lg"
                   :hint="t('booking.reschedule.startTimezoneHint', { browser: browserTimezone, session: coachRescheduleTimezone })" />
          <q-input :model-value="coachRescheduleProposedEnd" type="datetime-local" readonly
                   :label="t('booking.reschedule.proposedEnd')" class="q-mt-sm q-mb-lg"
                   :hint="t('booking.reschedule.endDerivedHintWithTimezone', { browser: browserTimezone, session: coachRescheduleTimezone })" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn unelevated color="primary"
                 :label="t('booking.reschedule.submit')"
                 :loading="rescheduleActionId === coachRescheduleBookingId"
                 :disable="rescheduleActionId === coachRescheduleBookingId"
                 @click="submitCoachReschedule" />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'
import { useAuthStore } from 'src/stores/auth.store'
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'
import ActiveSessionScreen from 'src/components/booking/ActiveSessionScreen.vue'
import WrapUpSequence from 'src/components/booking/WrapUpSequence.vue'
import SessionDNAChart from 'src/components/booking/SessionDNAChart.vue'

const { t, locale } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()
const authStore = useAuthStore()

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

const coachRescheduleProposedEnd = computed(() => {
  if (!coachRescheduleProposedStart.value || !coachRescheduleDurationMs.value) return ''
  const start = new Date(coachRescheduleProposedStart.value)
  if (Number.isNaN(start.getTime())) return ''
  return toDatetimeLocal(new Date(start.getTime() + coachRescheduleDurationMs.value))
})

/** datetime-local wants local wall-clock `YYYY-MM-DDTHH:mm`, which toISOString (UTC) is not. */
function toDatetimeLocal(date) {
  const pad = n => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}

let sessionEventSource = null

function startSessionSse(bookingId) {
  sessionEventSource?.close()
  const es = new EventSource(`/api/bookings/${bookingId}/events`, { withCredentials: true })
  es.addEventListener('status', (e) => { activeBookingStatus.value = e.data })
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
})

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

// Deliberately hardcoded 'en': the result is matched against the hardcoded English weekday
// array below via .indexOf. Localizing the formatter alone without also rewriting that array
// would make every non-English user's schedule silently misbucket into the wrong day column.
function getDayIndex(instant, timezone) {
  const parts = new Intl.DateTimeFormat('en', { timeZone: timezone, weekday: 'long' }).formatToParts(
    new Date(instant),
  )
  const day = parts.find((p) => p.type === 'weekday').value
  return ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].indexOf(day)
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

function generateSlotLink() {
  // TODO(3.x): deep-link to coach profile with pre-selected slot
  return window.location.origin + '/marketplace'
}

async function handleStartSession(booking) {
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
  } catch {
    $q.notify({ message: t('booking.completion.actionError'), type: 'negative' })
  } finally {
    startingSessionId.value = null
  }
}

async function handleQuickComplete(booking) {
  quickCompletingId.value = booking.bookingId
  try {
    await bookingStore.handleInitiateQuickComplete(booking.bookingId)
    activeBookingId.value = String(booking.bookingId)
    activePlayerName.value = booking.playerName
    activePlayerId.value = booking.playerId
    isLiveMode.value = false
    showWrapUp.value = true
  } catch {
    $q.notify({ message: t('booking.completion.actionError'), type: 'negative' })
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

async function handleAcceptReschedule(booking) {
  rescheduleActionId.value = booking.bookingId
  try {
    await bookingStore.handleAcceptReschedule(booking.bookingId, booking.pendingReschedule.id)
    notifyIfScheduleStale(await bookingStore.loadCoachSchedule(selectedWeek.value))
    $q.notify({ message: t('booking.reschedule.accepted'), type: 'positive' })
  } catch (err) {
    const refreshed = await bookingStore.loadCoachSchedule(selectedWeek.value)
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    // RescheduleService.acceptReschedule used to answer four of these with MISSING_RIGHTS
    // (skillars-deferred-31 AC3). Post-split, MISSING_RIGHTS here means exactly one thing — this
    // coach does not own the booking — so it gets the authorization wording, not retry advice.
    // rescheduleNotPending covers both PENDING checks in that method: the unlocked early-out and the
    // locked re-read that loses the race against a concurrent decline.
    if (errorKey === 'booking.coachUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.coachUnavailable') })
    } else if (errorKey === 'booking.slotUnavailable') {
      $q.notify({ type: 'negative', message: t('booking.errors.slotUnavailable') })
    } else if (errorKey === 'booking.rescheduleNotPending') {
      $q.notify({ type: 'negative', message: t('booking.errors.rescheduleNotPending') })
    } else if (errorKey === 'booking.notReschedulable') {
      $q.notify({ type: 'negative', message: t('booking.errors.notReschedulable') })
    } else if (errorKey === 'booking.startTimeInPast') {
      $q.notify({ type: 'negative', message: t('booking.errors.startTimeInPast') })
    } else if (errorKey === 'booking.slotOutsideAvailability') {
      $q.notify({ type: 'negative', message: t('booking.errors.slotOutsideAvailability') })
    } else if (errorKey === 'MISSING_RIGHTS') {
      $q.notify({ type: 'negative', message: t('booking.errors.requestNotAllowed') })
    } else {
      $q.notify({ type: 'negative', message: t('booking.reschedule.acceptFailed') })
    }
    notifyIfScheduleStale(refreshed)
  } finally {
    rescheduleActionId.value = null
  }
}

async function handleDeclineReschedule(booking) {
  rescheduleActionId.value = booking.bookingId
  try {
    await bookingStore.handleDeclineReschedule(booking.bookingId, booking.pendingReschedule.id)
    notifyIfScheduleStale(await bookingStore.loadCoachSchedule(selectedWeek.value))
    $q.notify({ message: t('booking.reschedule.declined'), type: 'positive' })
  } catch (err) {
    const refreshed = await bookingStore.loadCoachSchedule(selectedWeek.value)
    // Bare catch {} until skillars-deferred-31 AC3 — declineReschedule's not-PENDING rejection (the
    // ordinary double-click, or losing the race to a concurrent accept) is now its own wire code and
    // deserves its own message. Everything else keeps the generic decline failure.
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'booking.rescheduleNotPending') {
      $q.notify({ message: t('booking.errors.rescheduleNotPending'), type: 'negative' })
    } else {
      $q.notify({ message: t('booking.reschedule.declineFailed'), type: 'negative' })
    }
    notifyIfScheduleStale(refreshed)
  } finally {
    rescheduleActionId.value = null
  }
}

function openCoachRescheduleDialog(booking) {
  // Carried from the booking itself, matching ParentBookingsPage.vue's openRescheduleDialog.
  const start = new Date(booking.requestedStartTime)
  const end = new Date(booking.requestedEndTime)
  const durationMs =
    Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) ? 0 : end.getTime() - start.getTime()

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
  rescheduleActionId.value = coachRescheduleBookingId.value
  try {
    const data = {
      proposedStartTime: new Date(coachRescheduleProposedStart.value).toISOString(),
      proposedEndTime: new Date(coachRescheduleProposedEnd.value).toISOString(),
    }
    await bookingStore.handleRequestRescheduleAsCoach(coachRescheduleBookingId.value, data)
    coachRescheduleDialogOpen.value = false
    notifyIfScheduleStale(await bookingStore.loadCoachSchedule(selectedWeek.value))
    $q.notify({ message: t('booking.reschedule.requestSent'), type: 'positive' })
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    // Same error-key branching shape as ParentBookingsPage.vue's submitReschedule.
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
      $q.notify({ message: t('booking.reschedule.requestFailed'), type: 'negative' })
    }
  } finally {
    rescheduleActionId.value = null
  }
}

async function handleRepeatNextWeek(booking) {
  if (duplicatingId.value !== null) return
  duplicatingId.value = booking.bookingId
  try {
    await bookingStore.handleDuplicateNextWeek(booking.bookingId)
    await bookingStore.loadCoachSchedule(selectedWeek.value)
    $q.notify({ message: t('booking.schedule.repeatProposed'), type: 'positive' })
  } catch {
    $q.notify({ message: t('booking.schedule.repeatFailed'), type: 'negative' })
  } finally {
    duplicatingId.value = null
  }
}

async function shareSlot() {
  try {
    await navigator.clipboard.writeText(generateSlotLink())
    $q.notify({ message: t('booking.schedule.slotCopied'), type: 'positive' })
  } catch {
    $q.notify({ message: t('booking.schedule.slotCopyFailed'), type: 'negative' })
  }
}
</script>

<style lang="scss" scoped>
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
    > .command-center__revenue { order: 1; }
    > .command-center__schedule { order: 2; }
    > .command-center__sidebar { order: 3; }
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
