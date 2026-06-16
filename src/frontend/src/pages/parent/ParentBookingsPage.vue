<template>
  <q-page class="q-pa-md">
    <TimezoneNotice
      v-if="firstBookingTimezone && !authStore.timezoneNoticeDismissed"
      :pitch-timezone="firstBookingTimezone"
    />

    <div class="text-h5 q-mb-md">{{ t('booking.requests.listTitle') }}</div>

    <div v-if="bookingStore.bookingsLoading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div
      v-else-if="bookingStore.parentBookings.length === 0"
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
          <q-item-label caption class="q-mt-xs">
            {{
              t('booking.requests.creditsRemaining', { count: booking.effectiveCreditsRemaining })
            }}
          </q-item-label>

          <!-- Pending reschedule indicator -->
          <div v-if="booking.pendingReschedule" class="text-caption q-mt-xs"
               style="color: var(--accent-warning)">
            {{ t('booking.reschedule.pendingLabel') }}
          </div>
          <div v-if="booking.pendingReschedule" class="text-caption q-mt-xs">
            <span class="text-strike">{{ formatDateTime(booking.requestedStartTime, booking.canonicalTimezone) }}</span>
            → {{ formatDateTime(booking.pendingReschedule.proposedStartTime, booking.canonicalTimezone) }}
          </div>

          <!-- Request Change button -->
          <q-btn
            v-if="['CONFIRMED', 'UPCOMING'].includes(booking.status) && !booking.pendingReschedule"
            flat dense size="sm"
            :label="t('booking.reschedule.requestChange')"
            :loading="reschedulingId === booking.id"
            @click="openRescheduleDialog(booking)"
            class="q-mt-xs self-start"
          />
        </q-item-section>
        <q-item-section side>
          <BookingStateChip :status="booking.status" />
          <q-btn
            v-if="booking.status === 'COMPLETED_PENDING_CONFIRMATION'"
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
          <q-input v-model="rescheduleProposedEnd" type="datetime-local"
                   :label="t('booking.reschedule.proposedEnd')" class="q-mt-sm" />
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

const { t } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()
const authStore = useAuthStore()

const showInMyTime = ref({})
const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone
const confirmingId = ref(null)

const rescheduleDialogOpen = ref(false)
const rescheduleBookingId = ref(null)
const rescheduleProposedStart = ref('')
const rescheduleProposedEnd = ref('')
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
  rescheduleBookingId.value = booking.id
  rescheduleProposedStart.value = ''
  rescheduleProposedEnd.value = ''
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
  } catch {
    $q.notify({ message: t('booking.reschedule.requestFailed'), type: 'negative' })
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
  return new Intl.DateTimeFormat('en', {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(isoString))
}

onMounted(() => {
  bookingStore.loadParentBookings()
})
</script>
