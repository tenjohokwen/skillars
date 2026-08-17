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
            flat dense
            class="self-start q-pa-none"
            :label="showInMyTime[booking.id] ? t('booking.timezone.showInSessionTime') : t('booking.timezone.showInMyTime')"
            @click="toggleTimezone(booking.id)"
          />
          <!-- Pending reschedule indicator -->
          <div v-if="booking.pendingReschedule" class="text-caption q-mt-xs"
               style="color: var(--accent-warning)">
            {{ t('booking.reschedule.pendingLabel') }}
          </div>
          <div v-if="booking.pendingReschedule" class="text-caption q-mt-xs">
            <span class="text-strike">{{ formatDateTime(booking.requestedStartTime, booking.canonicalTimezone) }}</span>
            → {{ formatDateTime(booking.pendingReschedule.proposedStartTime, booking.canonicalTimezone) }}
          </div>

          <!-- Request Change button — parent-only (UAT.5): reschedule stays @PreAuthorize
               HAS_PARENT_ROLE, not widened by this story, so a player caller must not see a
               button that 403s on click. -->
          <q-btn
            v-if="authStore.isParent && ['CONFIRMED', 'UPCOMING'].includes(booking.status) && !booking.pendingReschedule"
            flat dense size="sm"
            :label="t('booking.reschedule.requestChange')"
            :loading="reschedulingId === booking.id"
            @click="openRescheduleDialog(booking)"
            class="q-mt-xs self-start"
          />
        </q-item-section>
        <q-item-section side>
          <BookingStateChip :status="booking.status" />
          <!-- Confirm Completion button — parent-only (UAT.5): session-completion confirm stays
               @PreAuthorize HAS_PARENT_ROLE, not widened by this story. -->
          <q-btn
            v-if="authStore.isParent && booking.status === 'COMPLETED_PENDING_CONFIRMATION'"
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
          <q-input v-model="rescheduleProposedStart" type="datetime-local"
                   :label="t('booking.reschedule.proposedStart')" />
          <!-- Read-only and derived: the backend requires a reschedule to keep the session's
               original length (a move, not a resize). Two freely-editable inputs where the second
               must exactly equal the first plus that length is a trap the parent cannot see. -->
          <q-input :model-value="rescheduleProposedEnd" type="datetime-local" readonly
                   :label="t('booking.reschedule.proposedEnd')" class="q-mt-sm"
                   :hint="t('booking.reschedule.endDerivedHint')" />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn unelevated color="primary"
                 :label="t('booking.reschedule.submit')"
                 @click="submitReschedule" />
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
import BookingStateChip from 'src/components/booking/BookingStateChip.vue'
import TimezoneNotice from 'src/components/booking/TimezoneNotice.vue'

const { t, locale } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()
const authStore = useAuthStore()

const showInMyTime = ref({})
const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone
const confirmingId = ref(null)

const rescheduleDialogOpen = ref(false)
const rescheduleBookingId = ref(null)
const rescheduleProposedStart = ref('')
// Length of the booking being rescheduled, in milliseconds. The proposed end is always derived
// from it, never typed.
const rescheduleDurationMs = ref(0)

const rescheduleProposedEnd = computed(() => {
  if (!rescheduleProposedStart.value || !rescheduleDurationMs.value) return ''
  const start = new Date(rescheduleProposedStart.value)
  if (Number.isNaN(start.getTime())) return ''
  return toDatetimeLocal(new Date(start.getTime() + rescheduleDurationMs.value))
})

/** datetime-local wants local wall-clock `YYYY-MM-DDTHH:mm`, which toISOString (UTC) is not. */
function toDatetimeLocal(date) {
  const pad = n => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}
const reschedulingId = ref(null)

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
    Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) ? 0 : end.getTime() - start.getTime()

  // Without a derivable length the end field — now read-only — would sit permanently blank and the
  // dialog could never be submitted. Fail visibly at open time instead of presenting a dead end.
  if (durationMs <= 0) {
    $q.notify({ message: t('booking.reschedule.endDerivedLengthUnavailable'), type: 'negative' })
    return
  }

  rescheduleBookingId.value = booking.id
  rescheduleProposedStart.value = ''
  rescheduleDurationMs.value = durationMs
  rescheduleDialogOpen.value = true
}

async function submitReschedule() {
  if (!rescheduleProposedStart.value || !rescheduleProposedEnd.value) {
    $q.notify({ message: t('booking.reschedule.requestFailed'), type: 'negative' })
    return
  }
  reschedulingId.value = rescheduleBookingId.value
  try {
    const data = {
      proposedStartTime: new Date(rescheduleProposedStart.value).toISOString(),
      proposedEndTime: new Date(rescheduleProposedEnd.value).toISOString(),
    }
    await bookingStore.handleRequestReschedule(rescheduleBookingId.value, data)
    rescheduleDialogOpen.value = false
    $q.notify({ message: t('booking.reschedule.requestSent'), type: 'positive' })
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'booking.invalidSessionDuration') {
      $q.notify({ message: t('booking.errors.invalidSessionDuration'), type: 'negative' })
    } else {
      console.warn('[booking] unmapped errorKey:', errorKey, err)
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
