<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-xl">
        <div class="text-page-title">{{ t('admin.disputes.title') }}</div>
        <div class="text-meta">{{ t('admin.disputes.subtitle') }}</div>
      </div>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.disputes.loadError') }}
      </q-banner>

      <q-table
        :rows="rows"
        :columns="columns"
        row-key="disputeId"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('admin.disputes.emptyState')"
      >
        <template #body-cell-raisedByRole="props">
          <q-td :props="props">{{ props.row.raisedByRole }}</q-td>
        </template>
        <template #body-cell-reason="props">
          <q-td :props="props">{{ t(`disputes.reason.${props.row.reason}`) }}</q-td>
        </template>
        <template #body-cell-status="props">
          <q-td :props="props">{{ t(`disputes.statusValue.${props.row.status}`) }}</q-td>
        </template>
        <template #body-cell-createdAt="props">
          <q-td :props="props">{{ formatDate(props.row.createdAt) }}</q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat
              dense
              size="sm"
              color="primary"
              :label="t('admin.disputes.view')"
              data-testid="dispute-row-view-btn"
              @click="openDetail(props.row.disputeId)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
      </div>
    </div>

    <!-- Detail dialog -->
    <q-dialog v-model="detailDialogOpen">
      <q-card
        data-testid="dispute-detail-dialog"
        style="min-width: 360px; max-width: 640px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.disputes.detailTitle') }}</div>
        </q-card-section>

        <q-card-section v-if="detailLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>

        <q-card-section
          v-else-if="detailLoadError"
          class="text-negative"
          data-testid="dispute-detail-load-error"
        >
          {{ t('admin.disputes.detailLoadError') }}
        </q-card-section>

        <template v-else-if="detail">
          <q-card-section data-testid="dispute-detail-content">
            <div>{{ t('admin.disputes.coachLabel') }}: {{ detail.coachName }}</div>
            <div>{{ t('admin.disputes.colRaisedByRole') }}: {{ detail.raisedByRole }}</div>
            <div>{{ t('disputes.reasonLabel') }}: {{ t(`disputes.reason.${detail.reason}`) }}</div>
            <div>{{ t('admin.disputes.detailsLabel') }}: {{ detail.details }}</div>
            <div>
              {{ t('disputes.statusLabel') }}: {{ t(`disputes.statusValue.${detail.status}`) }}
            </div>
            <div>{{ t('admin.disputes.bookingStatusLabel') }}: {{ detail.bookingStatus }}</div>
            <div>
              {{ t('admin.disputes.sessionDateLabel') }}: {{ formatDate(detail.sessionDate) }}
            </div>
            <div>{{ t('admin.disputes.creditDebitedLabel') }}: {{ fmt(detail.creditDebited) }}</div>
            <div>{{ t('admin.disputes.stripeChargedLabel') }}: {{ fmt(detail.stripeCharged) }}</div>
            <div>{{ t('admin.disputes.sessionPriceLabel') }}: {{ fmt(detail.sessionPrice) }}</div>
            <div v-if="detail.resolution">
              {{ t('disputes.resolutionLabel') }}:
              {{ t(`disputes.resolutionValue.${detail.resolution}`) }}
            </div>
            <div v-if="detail.creditAmount != null">
              {{ t('admin.disputes.creditAmountLabel') }}: {{ fmt(detail.creditAmount) }}
            </div>
            <div v-if="detail.recoveredAmount != null" data-testid="dispute-recovered-amount">
              {{ t('admin.disputes.recoveredAmountLabel') }}: {{ fmt(detail.recoveredAmount) }}
            </div>
            <div v-if="detail.absorbedAmount != null" data-testid="dispute-absorbed-amount">
              {{ t('admin.disputes.absorbedAmountLabel') }}: {{ fmt(detail.absorbedAmount) }}
            </div>
            <div v-if="detail.resolutionNote">{{ detail.resolutionNote }}</div>

            <div
              v-if="detail.cancellationHistory && detail.cancellationHistory.length > 0"
              class="q-mt-md"
            >
              <div class="text-subtitle2">{{ t('admin.disputes.cancellationHistoryLabel') }}</div>
              <q-list dense>
                <q-item v-for="entry in detail.cancellationHistory" :key="entry.id">
                  <q-item-section>
                    {{ entry.cancelReason }} — {{ formatDate(entry.createdAt) }}
                  </q-item-section>
                </q-item>
              </q-list>
            </div>
          </q-card-section>

          <q-card-actions v-if="!['RESOLVED', 'DISMISSED'].includes(detail.status)" align="right">
            <q-btn
              flat
              color="negative"
              data-testid="dispute-dismiss-open-btn"
              :label="t('admin.disputes.dismissAction')"
              @click="openDismissDialog"
            />
            <q-btn
              unelevated
              color="primary"
              data-testid="dispute-resolve-open-btn"
              :label="t('admin.disputes.resolveAction')"
              @click="openResolveDialog"
            />
          </q-card-actions>
        </template>
      </q-card>
    </q-dialog>

    <!-- Resolve dialog -->
    <q-dialog v-model="resolveDialogOpen">
      <q-card
        data-testid="dispute-resolve-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.disputes.resolveDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-select
            v-model="resolution"
            data-testid="dispute-resolution-select"
            :options="resolutionOptions"
            :label="t('admin.disputes.resolutionLabel')"
            emit-value
            map-options
            outlined
            dense
            class="q-mb-md"
          />
          <div
            v-if="resolution === 'UPHOLD_COACH'"
            class="text-caption q-mb-md"
            data-testid="dispute-uphold-coach-help"
          >
            {{ t('admin.disputes.upholdCoachHelp') }}
          </div>
          <q-input
            v-if="takesCreditAmount"
            v-model.number="creditAmount"
            type="number"
            outlined
            dense
            data-testid="dispute-credit-amount-input"
            :label="t('admin.disputes.creditAmountLabel')"
            class="q-mb-md"
          />
          <q-input
            v-model="resolutionNote"
            type="textarea"
            outlined
            autogrow
            maxlength="1000"
            counter
            data-testid="dispute-resolution-note-input"
            :label="t('admin.disputes.resolutionNoteLabel')"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            data-testid="dispute-resolve-submit-btn"
            :label="t('admin.disputes.resolveAction')"
            :loading="submitting"
            :disable="!canSubmitResolve"
            @click="submitResolve"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Dismiss dialog -->
    <q-dialog v-model="dismissDialogOpen">
      <q-card
        data-testid="dispute-dismiss-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.disputes.dismissDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="dismissReason"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            data-testid="dispute-dismiss-reason-input"
            :label="t('admin.disputes.dismissReasonLabel')"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="negative"
            data-testid="dispute-dismiss-submit-btn"
            :label="t('admin.disputes.dismissAction')"
            :loading="submitting"
            :disable="!dismissReason"
            @click="submitDismiss"
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
import { adminApi } from 'src/api/admin.api'

const { t, locale } = useI18n()
const $q = useQuasar()

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)

const detailDialogOpen = ref(false)
const detailLoading = ref(false)
const detailLoadError = ref(false)
const detail = ref(null)
const selectedDisputeId = ref(null)

const resolveDialogOpen = ref(false)
const dismissDialogOpen = ref(false)
const submitting = ref(false)
const resolution = ref(null)
const creditAmount = ref(null)
const resolutionNote = ref('')
const dismissReason = ref('')

const columns = [
  { name: 'disputeId', label: t('admin.disputes.colDisputeId'), field: 'disputeId', align: 'left' },
  { name: 'bookingId', label: t('admin.disputes.colBookingId'), field: 'bookingId', align: 'left' },
  {
    name: 'raisedByRole',
    label: t('admin.disputes.colRaisedByRole'),
    field: 'raisedByRole',
    align: 'left',
  },
  { name: 'reason', label: t('admin.disputes.colReason'), field: 'reason', align: 'left' },
  { name: 'status', label: t('admin.disputes.colStatus'), field: 'status', align: 'left' },
  { name: 'createdAt', label: t('admin.disputes.colCreatedAt'), field: 'createdAt', align: 'left' },
  { name: 'actions', label: t('admin.disputes.colActions'), field: 'actions', align: 'center' },
]

const resolutionOptions = [
  { label: t('disputes.resolutionValue.FULL_CREDIT'), value: 'FULL_CREDIT' },
  { label: t('disputes.resolutionValue.PARTIAL_CREDIT'), value: 'PARTIAL_CREDIT' },
  { label: t('disputes.resolutionValue.NO_ACTION'), value: 'NO_ACTION' },
  { label: t('disputes.resolutionValue.COACH_WARNING'), value: 'COACH_WARNING' },
  // feat/payment-forfeit-payouts-and-clawback: reverse the parent's refund, pay the coach in full.
  { label: t('disputes.resolutionValue.UPHOLD_COACH'), value: 'UPHOLD_COACH' },
]

// COACH_WARNING takes an OPTIONAL credit (DisputeService.resolveDispute); PARTIAL_CREDIT requires one.
const takesCreditAmount = computed(() =>
  ['PARTIAL_CREDIT', 'COACH_WARNING'].includes(resolution.value),
)
const canSubmitResolve = computed(() => {
  if (!resolution.value || !resolutionNote.value) return false
  if (resolution.value === 'PARTIAL_CREDIT') return Number(creditAmount.value) > 0
  return true
})

// Guards openDetail against a late response for a previously opened dispute overwriting the one
// whose id Resolve/Dismiss will actually target.
let detailToken = 0

onMounted(() => loadQueue())

async function loadQueue() {
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.getDisputeQueue(currentPage.value - 1)
    // Resolving/dismissing the last row of the last page leaves that page empty — step back to
    // the new last page instead of showing an empty queue with the pager hidden.
    if (resp.content.length === 0 && currentPage.value > 1 && resp.totalPages > 0) {
      currentPage.value = Math.min(currentPage.value - 1, resp.totalPages)
      return loadQueue()
    }
    rows.value = resp.content
    totalPages.value = resp.totalPages
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadPage(page) {
  currentPage.value = page
  await loadQueue()
}

async function openDetail(disputeId) {
  const token = ++detailToken
  selectedDisputeId.value = disputeId
  detailDialogOpen.value = true
  detailLoading.value = true
  detailLoadError.value = false
  detail.value = null
  try {
    const resp = await adminApi.getDisputeDetail(disputeId)
    if (token !== detailToken) return
    detail.value = resp
  } catch {
    if (token !== detailToken) return
    detailLoadError.value = true
  } finally {
    if (token === detailToken) detailLoading.value = false
  }
}

function openResolveDialog() {
  resolution.value = null
  creditAmount.value = null
  resolutionNote.value = ''
  resolveDialogOpen.value = true
}

function openDismissDialog() {
  dismissReason.value = ''
  dismissDialogOpen.value = true
}

async function submitResolve() {
  submitting.value = true
  try {
    await adminApi.resolveDispute(selectedDisputeId.value, {
      resolution: resolution.value,
      // A hidden, stale amount from a previously selected resolution must not be stored.
      creditAmount: takesCreditAmount.value ? creditAmount.value : null,
      resolutionNote: resolutionNote.value,
    })
    resolveDialogOpen.value = false
    detailDialogOpen.value = false
    $q.notify({ message: t('admin.disputes.resolveSuccess'), type: 'positive' })
    await loadQueue()
  } catch (err) {
    const message = err?.response?.data?.errorMsg?.message
    if (message === 'disputes.alreadyResolved') {
      $q.notify({ message: t('admin.disputes.alreadyResolved'), type: 'negative' })
      await closeStaleDispute()
    } else if (message === 'disputes.invalidCreditAmount') {
      $q.notify({ message: t('admin.disputes.invalidCreditAmount'), type: 'negative' })
    } else if (message === 'disputes.noRefundToReverse') {
      $q.notify({ message: t('admin.disputes.noRefundToReverse'), type: 'negative' })
    } else {
      $q.notify({ message: t('admin.disputes.resolveFailed'), type: 'negative' })
    }
  } finally {
    submitting.value = false
  }
}

async function submitDismiss() {
  submitting.value = true
  try {
    await adminApi.dismissDispute(selectedDisputeId.value, { reason: dismissReason.value })
    dismissDialogOpen.value = false
    detailDialogOpen.value = false
    $q.notify({ message: t('admin.disputes.dismissSuccess'), type: 'positive' })
    await loadQueue()
  } catch (err) {
    const message = err?.response?.data?.errorMsg?.message
    if (message === 'disputes.alreadyResolved') {
      $q.notify({ message: t('admin.disputes.alreadyResolved'), type: 'negative' })
      await closeStaleDispute()
    } else {
      $q.notify({ message: t('admin.disputes.dismissFailed'), type: 'negative' })
    }
  } finally {
    submitting.value = false
  }
}

// Another admin already closed this dispute: drop the stale dialogs and refresh the worklist.
async function closeStaleDispute() {
  resolveDialogOpen.value = false
  dismissDialogOpen.value = false
  detailDialogOpen.value = false
  await loadQueue()
}

function fmt(val) {
  return val != null ? `€${Number(val).toFixed(2)}` : '—'
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
}
</script>
