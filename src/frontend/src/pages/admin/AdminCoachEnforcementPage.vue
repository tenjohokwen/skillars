<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.coaches.title') }}</div>
        <div class="text-meta">{{ t('admin.coaches.subtitle') }}</div>
      </div>

      <div class="row items-center q-mb-md">
        <q-select
          v-model="statusFilter"
          :options="statusFilterOptions"
          :label="t('admin.coaches.statusFilterLabel')"
          emit-value
          map-options
          outlined
          dense
          style="min-width: 260px"
          data-testid="coach-status-filter"
          @update:model-value="onStatusFilterChange"
        />
      </div>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.coaches.loadError') }}
      </q-banner>

      <q-table
        :rows="rows"
        :columns="columns"
        row-key="coachId"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('admin.coaches.emptyState')"
      >
        <template #body-cell-status="props">
          <q-td :props="props">{{ statusLabel(props.row.status) }}</q-td>
        </template>
        <template #body-cell-verificationTier="props">
          <q-td :props="props">
            <VerificationBadge
              v-if="props.row.verificationTier"
              :tier="props.row.verificationTier"
            />
          </q-td>
        </template>
        <template #body-cell-statusChangedAt="props">
          <q-td :props="props">{{ formatDate(props.row.statusChangedAt) }}</q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat
              dense
              size="sm"
              color="primary"
              :label="t('admin.coaches.view')"
              data-testid="coach-row-view-btn"
              @click="openProfile(props.row.coachId)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
      </div>
    </div>

    <!-- Enforcement profile -->
    <q-dialog v-model="profileDialogOpen">
      <q-card
        data-testid="coach-profile-dialog"
        style="min-width: 360px; max-width: 760px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.coaches.profileTitle') }}</div>
        </q-card-section>

        <q-card-section v-if="profileLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>
        <q-card-section v-else-if="profileLoadError" class="text-negative">
          {{ t('admin.coaches.profileLoadError') }}
        </q-card-section>

        <template v-else-if="profile">
          <q-card-section data-testid="coach-profile-content">
            <div class="text-subtitle1 text-weight-medium">{{ profile.coachName }}</div>
            <div class="row q-gutter-sm items-center q-mt-xs">
              <q-chip dense outline>{{ statusLabel(profile.currentStatus) }}</q-chip>
              <VerificationBadge v-if="profile.verificationTier" :tier="profile.verificationTier" />
            </div>
            <div class="q-mt-sm">
              {{ t('admin.coaches.activeStrikesLabel') }}: {{ profile.activeStrikes }} ·
              {{ t('admin.coaches.openAlertsLabel') }}: {{ profile.openAlerts }}
            </div>
          </q-card-section>

          <q-card-actions align="left" class="q-px-md">
            <q-btn
              v-if="profile.currentStatus !== 'SUSPENDED'"
              unelevated
              color="negative"
              :label="t('admin.coaches.suspendAction')"
              data-testid="coach-suspend-open-btn"
              @click="openAction('suspend')"
            />
            <q-btn
              v-if="canReinstate"
              unelevated
              color="positive"
              :label="t('admin.coaches.reinstateAction')"
              data-testid="coach-reinstate-open-btn"
              @click="openAction('reinstate')"
            />
            <q-btn
              outline
              color="warning"
              :label="t('admin.coaches.issueStrikeAction')"
              data-testid="coach-strike-open-btn"
              @click="openAction('strike')"
            />
          </q-card-actions>

          <q-separator class="q-my-sm" />

          <!-- Verification tier (D6) -->
          <q-card-section>
            <div class="text-subtitle2">{{ t('admin.coaches.tierSectionTitle') }}</div>
            <div class="text-meta q-mb-sm">{{ t('admin.coaches.tierSectionHint') }}</div>
            <div class="row q-col-gutter-sm items-start">
              <div class="col-12 col-sm-4">
                <q-select
                  v-model="tierDraft"
                  :options="tierOptions"
                  :label="t('admin.coaches.tierLabel')"
                  emit-value
                  map-options
                  outlined
                  dense
                  data-testid="coach-tier-select"
                />
              </div>
              <div class="col-12 col-sm-8">
                <q-input
                  v-model="tierReason"
                  outlined
                  dense
                  maxlength="450"
                  :label="t('admin.coaches.tierReasonLabel')"
                  data-testid="coach-tier-reason-input"
                />
              </div>
            </div>
            <q-btn
              class="q-mt-sm"
              unelevated
              color="primary"
              :label="t('admin.coaches.tierSave')"
              :disable="tierDraft === profile.verificationTier"
              :loading="submitting"
              data-testid="coach-tier-save-btn"
              @click="saveTier"
            />
          </q-card-section>

          <q-separator class="q-my-sm" />

          <q-card-section>
            <div class="text-subtitle2">{{ t('admin.coaches.strikeHistoryTitle') }}</div>
            <div v-if="!profile.strikeHistory || !profile.strikeHistory.length" class="text-meta">
              {{ t('admin.coaches.noStrikes') }}
            </div>
            <q-list v-else dense separator>
              <q-item v-for="s in profile.strikeHistory" :key="s.strikeId">
                <q-item-section>
                  <q-item-label>{{ strikeReasonLabel(s.reason) }}</q-item-label>
                  <q-item-label caption>
                    {{ formatDate(s.createdAt) }} · {{ t('admin.coaches.bookingLabel') }}
                    {{ s.bookingId }}
                  </q-item-label>
                </q-item-section>
                <q-item-section side>
                  <q-btn
                    flat
                    dense
                    size="sm"
                    color="negative"
                    icon="delete"
                    :aria-label="t('admin.coaches.deleteStrikeAction')"
                    data-testid="coach-strike-delete-btn"
                    @click="openAction('deleteStrike', s)"
                  />
                </q-item-section>
              </q-item>
            </q-list>
          </q-card-section>

          <q-card-section>
            <div class="text-subtitle2">{{ t('admin.coaches.cancellationHistoryTitle') }}</div>
            <div
              v-if="!profile.cancellationHistory || !profile.cancellationHistory.length"
              class="text-meta"
            >
              {{ t('admin.coaches.noCancellations') }}
            </div>
            <q-list v-else dense separator>
              <q-item v-for="c in profile.cancellationHistory" :key="c.id">
                <q-item-section>
                  <q-item-label>{{ c.cancelReason }}</q-item-label>
                  <q-item-label caption>
                    {{ formatDate(c.createdAt) }} · {{ t('admin.coaches.bookingLabel') }}
                    {{ c.bookingId }}
                  </q-item-label>
                </q-item-section>
              </q-item>
            </q-list>
          </q-card-section>

          <q-card-actions align="right">
            <q-btn flat :label="t('common.close')" v-close-popup />
          </q-card-actions>
        </template>
      </q-card>
    </q-dialog>

    <!-- Confirmation dialog shared by suspend / reinstate / issue strike / delete strike -->
    <q-dialog v-model="actionDialogOpen">
      <q-card
        data-testid="coach-action-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ actionTitle }}</div>
          <div class="text-meta">{{ actionHint }}</div>
        </q-card-section>
        <q-card-section class="q-gutter-md">
          <template v-if="actionMode === 'strike'">
            <q-input
              v-model="strikeBookingId"
              outlined
              dense
              :label="t('admin.coaches.strikeBookingIdLabel')"
              data-testid="coach-strike-booking-input"
            />
            <q-select
              v-model="strikeReason"
              :options="strikeReasonOptions"
              :label="t('admin.coaches.strikeReasonLabel')"
              emit-value
              map-options
              outlined
              dense
              data-testid="coach-strike-reason-select"
            />
          </template>
          <q-input
            v-else
            v-model="actionReason"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            :label="t('common.reason')"
            data-testid="coach-action-reason-input"
          />
          <q-checkbox
            v-if="actionMode === 'suspend'"
            v-model="notifyCoach"
            :label="t('admin.coaches.notifyCoachLabel')"
            data-testid="coach-notify-checkbox"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            :color="actionMode === 'reinstate' ? 'positive' : 'negative'"
            :label="t('common.confirm')"
            :loading="submitting"
            :disable="!canSubmitAction"
            data-testid="coach-action-submit-btn"
            @click="submitAction"
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
import VerificationBadge from 'components/marketplace/VerificationBadge.vue'

const { t, locale } = useI18n()
const $q = useQuasar()
const route = useRoute()

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)
const statusFilter = ref('ALL')

const profileDialogOpen = ref(false)
const profileLoading = ref(false)
const profileLoadError = ref(false)
const profile = ref(null)
const selectedCoachId = ref(null)

const tierDraft = ref(null)
const tierReason = ref('')

const actionDialogOpen = ref(false)
const actionMode = ref(null)
const actionReason = ref('')
const notifyCoach = ref(true)
const strikeBookingId = ref('')
const strikeReason = ref(null)
const strikeToDelete = ref(null)
const submitting = ref(false)

const statusLabels = computed(() => ({
  ACTIVE: t('admin.coaches.statuses.ACTIVE'),
  REDUCED: t('admin.coaches.statuses.REDUCED'),
  PENDING_REVIEW: t('admin.coaches.statuses.PENDING_REVIEW'),
  SUSPENDED: t('admin.coaches.statuses.SUSPENDED'),
  DRAFT: t('admin.coaches.statuses.DRAFT'),
  DEACTIVATED: t('admin.coaches.statuses.DEACTIVATED'),
}))

const statusFilterOptions = computed(() => [
  { label: t('admin.coaches.filterUnderEnforcement'), value: 'ALL' },
  { label: statusLabels.value.PENDING_REVIEW, value: 'PENDING_REVIEW' },
  { label: statusLabels.value.SUSPENDED, value: 'SUSPENDED' },
  { label: statusLabels.value.REDUCED, value: 'REDUCED' },
  { label: statusLabels.value.ACTIVE, value: 'ACTIVE' },
])

const tierOptions = computed(() => [
  { label: t('marketplace.tierBASIC'), value: 'BASIC' },
  { label: t('marketplace.tierTRUSTED'), value: 'TRUSTED' },
  { label: t('marketplace.tierFEATURED'), value: 'FEATURED' },
])

// Mirrors AdminCoachEnforcementService.VALID_STRIKE_REASONS.
const strikeReasonOptions = computed(() => [
  { label: t('admin.coaches.strikeReasons.COACH_NO_SHOW'), value: 'COACH_NO_SHOW' },
  {
    label: t('admin.coaches.strikeReasons.COACH_CANCELLATION_UNEXCUSED'),
    value: 'COACH_CANCELLATION_UNEXCUSED',
  },
])

const columns = [
  { name: 'coachName', label: t('admin.coaches.colCoach'), field: 'coachName', align: 'left' },
  { name: 'status', label: t('admin.coaches.colStatus'), field: 'status', align: 'left' },
  {
    name: 'verificationTier',
    label: t('admin.coaches.colTier'),
    field: 'verificationTier',
    align: 'left',
  },
  {
    name: 'activeStrikes',
    label: t('admin.coaches.colActiveStrikes'),
    field: 'activeStrikes',
    align: 'center',
  },
  {
    name: 'statusChangedAt',
    label: t('admin.coaches.colStatusChangedAt'),
    field: 'statusChangedAt',
    align: 'left',
  },
  { name: 'actions', label: t('admin.coaches.colActions'), field: 'actions', align: 'center' },
]

const canReinstate = computed(() =>
  ['SUSPENDED', 'PENDING_REVIEW', 'REDUCED'].includes(profile.value?.currentStatus),
)

const actionTitle = computed(
  () =>
    ({
      suspend: t('admin.coaches.suspendTitle'),
      reinstate: t('admin.coaches.reinstateTitle'),
      strike: t('admin.coaches.issueStrikeTitle'),
      deleteStrike: t('admin.coaches.deleteStrikeTitle'),
    })[actionMode.value] ?? '',
)

const actionHint = computed(
  () =>
    ({
      suspend: t('admin.coaches.suspendHint'),
      reinstate: t('admin.coaches.reinstateHint'),
      strike: t('admin.coaches.issueStrikeHint'),
      deleteStrike: t('admin.coaches.deleteStrikeHint'),
    })[actionMode.value] ?? '',
)

const canSubmitAction = computed(() => {
  if (actionMode.value === 'strike') {
    return UUID_RE.test(strikeBookingId.value.trim()) && !!strikeReason.value
  }
  return actionReason.value.trim().length > 0
})

function statusLabel(status) {
  return statusLabels.value[status] ?? status
}

function strikeReasonLabel(reason) {
  return strikeReasonOptions.value.find((o) => o.value === reason)?.label ?? reason
}

let profileToken = 0

onMounted(async () => {
  await loadList()
  const coachId = route?.query?.coachId
  if (typeof coachId === 'string' && UUID_RE.test(coachId)) {
    await openProfile(coachId)
  }
})

async function loadList() {
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.getEnforcementCoaches(statusFilter.value, currentPage.value - 1)
    if (resp.content.length === 0 && currentPage.value > 1 && resp.totalPages > 0) {
      currentPage.value = Math.min(currentPage.value - 1, resp.totalPages)
      return loadList()
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
  await loadList()
}

async function onStatusFilterChange() {
  currentPage.value = 1
  await loadList()
}

async function openProfile(coachId) {
  selectedCoachId.value = coachId
  profileDialogOpen.value = true
  await reloadProfile()
}

async function reloadProfile() {
  const token = ++profileToken
  profileLoading.value = true
  profileLoadError.value = false
  profile.value = null
  try {
    const resp = await adminApi.getCoachEnforcementProfile(selectedCoachId.value)
    if (token !== profileToken) return
    profile.value = resp
    tierDraft.value = resp.verificationTier
    tierReason.value = ''
  } catch {
    if (token !== profileToken) return
    profileLoadError.value = true
  } finally {
    if (token === profileToken) profileLoading.value = false
  }
}

function openAction(mode, strike = null) {
  actionMode.value = mode
  actionReason.value = ''
  notifyCoach.value = true
  strikeBookingId.value = ''
  strikeReason.value = null
  strikeToDelete.value = strike
  actionDialogOpen.value = true
}

async function submitAction() {
  const coachId = selectedCoachId.value
  const reason = actionReason.value.trim()
  submitting.value = true
  try {
    if (actionMode.value === 'suspend') {
      await adminApi.suspendCoach(coachId, { reason, notifyCoach: notifyCoach.value })
    } else if (actionMode.value === 'reinstate') {
      await adminApi.reinstateCoach(coachId, { reason })
    } else if (actionMode.value === 'strike') {
      await adminApi.issueCoachStrike(coachId, {
        bookingId: strikeBookingId.value.trim(),
        reason: strikeReason.value,
      })
    } else if (actionMode.value === 'deleteStrike') {
      await adminApi.deleteCoachStrike(coachId, strikeToDelete.value.strikeId, reason)
    }
    actionDialogOpen.value = false
    $q.notify({ message: t('admin.coaches.actionSuccess'), type: 'positive' })
    await Promise.all([reloadProfile(), loadList()])
  } catch (err) {
    const status = err?.response?.status
    let message = t('admin.coaches.actionFailed')
    if (status === 409) message = t('admin.coaches.actionConflict')
    else if (status === 400 && actionMode.value === 'strike')
      message = t('admin.coaches.strikeInvalid')
    $q.notify({ message, type: 'negative' })
  } finally {
    submitting.value = false
  }
}

async function saveTier() {
  submitting.value = true
  try {
    await adminApi.setCoachVerificationTier(selectedCoachId.value, {
      tier: tierDraft.value,
      reason: tierReason.value.trim() || null,
    })
    $q.notify({ message: t('admin.coaches.tierSaved'), type: 'positive' })
    await Promise.all([reloadProfile(), loadList()])
  } catch {
    $q.notify({ message: t('admin.coaches.tierSaveFailed'), type: 'negative' })
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
