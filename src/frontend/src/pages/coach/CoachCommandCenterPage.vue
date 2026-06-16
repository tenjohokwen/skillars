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
              <BookingStateChip :status="booking.status" />
              <div class="text-caption">{{ booking.playerName }}</div>
              <div class="text-caption">
                {{ slotLabel(booking.requestedStartTime, bookingStore.coachSchedule.coachTimezone) }}
              </div>
              <q-btn
                v-if="booking.status === 'UPCOMING'"
                unelevated
                class="start-session-btn q-mt-xs"
                :label="t('booking.schedule.startSession')"
                @click="handleStartSession(booking)"
              />
              <q-btn
                v-if="booking.status === 'UPCOMING'"
                flat dense size="sm"
                :label="t('booking.completion.quickComplete')"
                class="q-mt-xs"
                @click="handleQuickComplete(booking)"
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

const { t } = useI18n()
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

function getDayIndex(instant, timezone) {
  const parts = new Intl.DateTimeFormat('en', { timeZone: timezone, weekday: 'long' }).formatToParts(
    new Date(instant),
  )
  const day = parts.find((p) => p.type === 'weekday').value
  return ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'].indexOf(day)
}

function dayLabel(index) {
  return ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'][index]
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
  return new Intl.DateTimeFormat('en', {
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
  await bookingStore.handleStartSession(booking.bookingId)
  activeBookingId.value = String(booking.bookingId)
  activePlayerName.value = booking.playerName
  activeSessionStart.value = booking.requestedStartTime
  activePlayerId.value = booking.playerId
  isLiveMode.value = true
  activeBookingStatus.value = 'IN_PROGRESS'
  startSessionSse(booking.bookingId)
  showActiveSession.value = true
}

async function handleQuickComplete(booking) {
  await bookingStore.handleInitiateQuickComplete(booking.bookingId)
  activeBookingId.value = String(booking.bookingId)
  activePlayerName.value = booking.playerName
  activePlayerId.value = booking.playerId
  isLiveMode.value = false
  showWrapUp.value = true
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
