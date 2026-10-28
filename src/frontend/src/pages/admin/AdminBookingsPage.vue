<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.bookings.title') }}</div>
        <div class="text-meta">{{ t('admin.bookings.subtitle') }}</div>
      </div>

      <div class="row q-col-gutter-sm items-start q-mb-md">
        <div class="col-12 col-sm-8 col-md-6">
          <q-input
            v-model="bookingIdInput"
            outlined
            dense
            :label="t('admin.bookings.bookingIdLabel')"
            :error="bookingIdInput.trim().length > 0 && !bookingIdValid"
            :error-message="t('admin.bookings.bookingIdInvalid')"
            data-testid="booking-id-input"
            @keyup.enter="lookup"
          />
        </div>
        <div class="col-auto">
          <q-btn
            unelevated
            color="primary"
            :label="t('admin.bookings.lookupAction')"
            :disable="!bookingIdValid"
            :loading="loading"
            data-testid="booking-lookup-btn"
            @click="lookup"
          />
        </div>
      </div>

      <q-banner v-if="lookupError" class="bg-negative text-white q-mb-md" rounded>
        {{ lookupError }}
      </q-banner>

      <q-card v-if="booking" flat bordered data-testid="booking-lookup-result">
        <q-card-section>
          <div class="row items-center q-gutter-sm">
            <div class="text-subtitle1 text-weight-medium">
              {{ t('admin.bookings.resultTitle') }}
            </div>
            <q-chip dense outline>{{ statusLabel(booking.status) }}</q-chip>
          </div>
          <q-list dense class="q-mt-sm">
            <q-item>
              <q-item-section>{{ t('admin.bookings.bookingIdLabel') }}</q-item-section>
              <q-item-section side class="booking-id">{{ booking.bookingId }}</q-item-section>
            </q-item>
            <q-item>
              <q-item-section>{{ t('admin.bookings.sessionLabel') }}</q-item-section>
              <q-item-section side>
                {{ formatDate(booking.requestedStartTime) }} –
                {{ formatDate(booking.requestedEndTime) }}
              </q-item-section>
            </q-item>
            <q-item>
              <q-item-section>{{ t('admin.bookings.coachLabel') }}</q-item-section>
              <q-item-section side>
                <router-link
                  :to="{ path: '/admin/coaches', query: { coachId: booking.coachId } }"
                  data-testid="booking-coach-link"
                >
                  {{ booking.coachId }}
                </router-link>
              </q-item-section>
            </q-item>
            <q-item>
              <q-item-section>{{ t('admin.bookings.playerLabel') }}</q-item-section>
              <q-item-section side>{{ booking.playerId }}</q-item-section>
            </q-item>
            <q-item>
              <q-item-section>{{ t('admin.bookings.parentLabel') }}</q-item-section>
              <q-item-section side>{{ booking.parentId }}</q-item-section>
            </q-item>
            <q-item>
              <q-item-section>{{ t('admin.bookings.updatedLabel') }}</q-item-section>
              <q-item-section side>{{ formatDate(booking.updatedAt) }}</q-item-section>
            </q-item>
            <q-item>
              <q-item-section>{{ t('admin.bookings.completionRecordedLabel') }}</q-item-section>
              <q-item-section side>
                {{ booking.completionRecorded ? t('admin.bookings.yes') : t('admin.bookings.no') }}
              </q-item-section>
            </q-item>
          </q-list>
        </q-card-section>

        <q-card-section v-if="booking.forceCompletable">
          <q-banner class="bg-warning text-dark q-mb-sm" rounded>
            {{ t('admin.bookings.stuckBanner') }}
          </q-banner>
          <q-btn
            unelevated
            color="negative"
            :label="t('admin.bookings.forceCompleteAction')"
            data-testid="booking-force-complete-open-btn"
            @click="openConfirm"
          />
        </q-card-section>
        <q-card-section v-else class="text-meta" data-testid="booking-not-stuck-hint">
          {{
            booking.completionRecorded
              ? t('admin.bookings.alreadyRecordedHint')
              : t('admin.bookings.notStuckHint')
          }}
        </q-card-section>
      </q-card>
    </div>

    <!-- Force-complete confirmation -->
    <q-dialog v-model="confirmOpen">
      <q-card
        data-testid="booking-confirm-dialog"
        style="min-width: 320px; max-width: 560px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.bookings.confirmTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <div class="text-body2 q-mb-sm">{{ t('admin.bookings.consequencesIntro') }}</div>
          <ul class="q-my-none q-pl-md">
            <li>{{ t('admin.bookings.consequenceCompleted') }}</li>
            <li>{{ t('admin.bookings.consequencePlaceholder') }}</li>
            <li>{{ t('admin.bookings.consequencePayout') }}</li>
            <li>{{ t('admin.bookings.consequenceIrreversible') }}</li>
            <li>{{ t('admin.bookings.consequenceAudit') }}</li>
          </ul>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="note"
            type="textarea"
            outlined
            autogrow
            maxlength="450"
            counter
            :label="t('admin.bookings.noteLabel')"
            data-testid="booking-note-input"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="negative"
            :label="t('admin.bookings.forceCompleteAction')"
            :loading="submitting"
            :disable="note.trim().length === 0"
            data-testid="booking-force-complete-submit-btn"
            @click="submitForceComplete"
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
import { useRoute } from 'vue-router'
import { adminApi } from 'src/api/admin.api'

const { t, locale } = useI18n()
const $q = useQuasar()
const route = useRoute()

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const bookingIdInput = ref('')
const booking = ref(null)
const loading = ref(false)
const lookupError = ref('')

const confirmOpen = ref(false)
const note = ref('')
const submitting = ref(false)

const bookingIdValid = computed(() => UUID_RE.test(bookingIdInput.value.trim()))

const statusLabels = computed(() => ({
  REQUESTED: t('booking.requests.statusRequested'),
  ACCEPTED: t('booking.requests.statusAccepted'),
  CONFIRMED: t('booking.requests.statusConfirmed'),
  UPCOMING: t('booking.requests.statusUpcoming'),
  DECLINED: t('booking.requests.statusDeclined'),
  COMPLETED: t('booking.requests.statusCompleted'),
  CANCELLED: t('booking.requests.statusCancelled'),
  DISPUTED: t('booking.requests.statusDisputed'),
  PAYMENT_PENDING: t('booking.requests.statusPaymentPending'),
  COMPLETED_PENDING_CONFIRMATION: t('admin.bookings.statusPendingConfirmation'),
}))

function statusLabel(status) {
  return statusLabels.value[status] ?? status
}

onMounted(async () => {
  const id = route?.query?.bookingId
  if (typeof id === 'string' && UUID_RE.test(id)) {
    bookingIdInput.value = id
    await lookup()
  }
})

let lookupToken = 0

async function lookup() {
  if (!bookingIdValid.value) return
  const token = ++lookupToken
  loading.value = true
  lookupError.value = ''
  booking.value = null
  try {
    const resp = await adminApi.getAdminBooking(bookingIdInput.value.trim())
    if (token !== lookupToken) return
    booking.value = resp
  } catch (err) {
    if (token !== lookupToken) return
    lookupError.value =
      err?.response?.status === 404
        ? t('admin.bookings.notFound')
        : t('admin.bookings.lookupFailed')
  } finally {
    if (token === lookupToken) loading.value = false
  }
}

function openConfirm() {
  note.value = ''
  confirmOpen.value = true
}

async function submitForceComplete() {
  const bookingId = booking.value.bookingId
  submitting.value = true
  try {
    const resp = await adminApi.forceCompleteBooking(bookingId, note.value.trim())
    confirmOpen.value = false
    const outcome = resp?.outcome
    if (outcome === 'FORCE_COMPLETED') {
      $q.notify({ message: t('admin.bookings.outcomeForceCompleted'), type: 'positive' })
    } else if (outcome === 'ALREADY_RESOLVED') {
      $q.notify({ message: t('admin.bookings.outcomeAlreadyResolved'), type: 'info' })
    } else {
      $q.notify({ message: t('admin.bookings.outcomeNotStuck'), type: 'info' })
    }
    await lookup()
  } catch (err) {
    const status = err?.response?.status
    $q.notify({
      message:
        status === 409
          ? t('admin.bookings.forceCompleteConflict')
          : t('admin.bookings.forceCompleteFailed'),
      type: 'negative',
    })
  } finally {
    submitting.value = false
  }
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
}
</script>

<style scoped>
.booking-id {
  font-family: monospace;
}
</style>
