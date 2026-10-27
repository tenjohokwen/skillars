<template>
  <span>
    <q-btn
      v-if="!disputeId"
      flat
      dense
      size="sm"
      color="negative"
      data-testid="dispute-raise-btn"
      :label="t('disputes.raiseAction')"
      @click="openRaiseDialog"
    />
    <q-btn
      v-else
      flat
      dense
      size="sm"
      data-testid="dispute-view-btn"
      :label="t('disputes.viewAction')"
      @click="openStatusDialog"
    />

    <q-dialog v-model="raiseDialogOpen">
      <q-card
        data-testid="dispute-raise-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('disputes.raiseDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-select
            v-model="reason"
            data-testid="dispute-reason-select"
            :options="reasonOptions"
            :label="t('disputes.reasonLabel')"
            emit-value
            map-options
            outlined
            dense
            class="q-mb-md"
          />
          <q-input
            v-model="details"
            data-testid="dispute-details-input"
            type="textarea"
            outlined
            autogrow
            maxlength="2000"
            counter
            :label="t('disputes.detailsLabel')"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            data-testid="dispute-submit-btn"
            :label="t('disputes.submit')"
            :loading="submitting"
            :disable="!reason || !details.trim()"
            @click="submitDispute"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <q-dialog v-model="statusDialogOpen">
      <q-card
        data-testid="dispute-status-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('disputes.statusDialogTitle') }}</div>
        </q-card-section>
        <q-card-section v-if="statusLoading" class="flex flex-center q-py-md">
          <q-spinner size="32px" />
        </q-card-section>
        <q-card-section
          v-else-if="statusError"
          class="text-negative"
          data-testid="dispute-status-error"
        >
          {{ t('disputes.statusLoadError') }}
        </q-card-section>
        <q-card-section v-else-if="disputeStatus" data-testid="dispute-status-content">
          <div>
            {{ t('disputes.reasonLabel') }}: {{ t(`disputes.reason.${disputeStatus.reason}`) }}
          </div>
          <div>
            {{ t('disputes.statusLabel') }}: {{ t(`disputes.statusValue.${disputeStatus.status}`) }}
          </div>
          <div v-if="disputeStatus.resolution">
            {{ t('disputes.resolutionLabel') }}:
            {{ t(`disputes.resolutionValue.${disputeStatus.resolution}`) }}
          </div>
          <div v-if="disputeStatus.resolutionNote">{{ disputeStatus.resolutionNote }}</div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.close')" v-close-popup />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </span>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { raiseDispute, getDispute } from 'src/api/disputes.api'
import { useAuthStore } from 'src/stores/auth.store'

const props = defineProps({
  bookingId: { type: String, required: true },
})

const { t } = useI18n()
const $q = useQuasar()
const authStore = useAuthStore()

const disputeId = ref(null)
const raiseDialogOpen = ref(false)
const statusDialogOpen = ref(false)
const reason = ref(null)
const details = ref('')
const submitting = ref(false)
const statusLoading = ref(false)
const statusError = ref(false)
const disputeStatus = ref(null)

const reasonOptions = [
  { label: t('disputes.reason.COACH_NO_SHOW'), value: 'COACH_NO_SHOW' },
  { label: t('disputes.reason.SESSION_QUALITY'), value: 'SESSION_QUALITY' },
  { label: t('disputes.reason.SAFETY_CONCERN'), value: 'SAFETY_CONCERN' },
  { label: t('disputes.reason.UNAUTHORISED_CHARGE'), value: 'UNAUTHORISED_CHARGE' },
  { label: t('disputes.reason.OTHER'), value: 'OTHER' },
]

// Deliberately client/session-scoped (Dev Notes): there is no booking->dispute link surfaced
// anywhere in the API today, and adding one is out of this story's scope. A page refresh losing
// this link is an acceptable, pre-existing-shaped limitation, not a regression. Scoped by user too:
// sessionStorage survives a logout/login on the same tab, and the coach and the parent see the same
// bookingId — neither may inherit the other's dispute link.
const storageKey = () => `dispute:${authStore.userId}:${props.bookingId}`

onMounted(() => {
  try {
    const stored = sessionStorage.getItem(storageKey())
    if (stored) disputeId.value = stored
  } catch {
    // sessionStorage unavailable (private mode) — the dispute link just won't persist
  }
})

function openRaiseDialog() {
  reason.value = null
  details.value = ''
  raiseDialogOpen.value = true
}

async function submitDispute() {
  submitting.value = true
  try {
    // boot/axios.js's response interceptor unwraps response.data already, so `resp` here IS the
    // DisputeCreatedResponse body, not an axios Response.
    const resp = await raiseDispute(props.bookingId, reason.value, details.value)
    disputeId.value = resp.disputeId
    try {
      sessionStorage.setItem(storageKey(), disputeId.value)
    } catch {
      // best-effort only
    }
    raiseDialogOpen.value = false
    $q.notify({ message: t('disputes.raiseSuccess'), type: 'positive' })
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    const message = err?.response?.data?.errorMsg?.message
    // disputes.notEligible/windowExpired are OperationNotAllowedException (403): errorKey carries the
    // real code. disputes.alreadyRaised/invalidReason are a raw ResponseStatusException: ApiAdvice's
    // responseStatusExceptionHandler hardcodes errorKey to "generic.requestError" and puts the real
    // reason in `message` instead (same pattern ParentBookingsPage.submitCancelBooking already uses).
    if (errorKey === 'disputes.notEligible') {
      $q.notify({ message: t('disputes.notEligible'), type: 'negative' })
    } else if (errorKey === 'disputes.windowExpired') {
      $q.notify({ message: t('disputes.windowExpired'), type: 'negative' })
    } else if (message === 'disputes.alreadyRaised') {
      $q.notify({ message: t('disputes.alreadyRaised'), type: 'negative' })
    } else if (message === 'disputes.invalidReason') {
      $q.notify({ message: t('disputes.invalidReason'), type: 'negative' })
    } else {
      $q.notify({ message: t('disputes.raiseFailed'), type: 'negative' })
    }
  } finally {
    submitting.value = false
  }
}

async function openStatusDialog() {
  statusDialogOpen.value = true
  statusLoading.value = true
  statusError.value = false
  disputeStatus.value = null
  try {
    const resp = await getDispute(disputeId.value)
    disputeStatus.value = resp
  } catch {
    statusError.value = true
  } finally {
    statusLoading.value = false
  }
}
</script>
