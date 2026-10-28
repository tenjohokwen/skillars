<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.finance.title') }}</div>
        <div class="text-meta">{{ t('admin.finance.subtitle') }}</div>
      </div>

      <!-- Period -->
      <div class="row q-col-gutter-sm items-start q-mb-md">
        <div class="col-12 col-sm-4 col-md-3">
          <q-input
            v-model="fromDate"
            type="date"
            outlined
            dense
            stack-label
            :label="t('admin.finance.fromLabel')"
            data-testid="finance-from-input"
          />
        </div>
        <div class="col-12 col-sm-4 col-md-3">
          <q-input
            v-model="toDate"
            type="date"
            outlined
            dense
            stack-label
            :label="t('admin.finance.toLabel')"
            :error="rangeInvalid"
            :error-message="rangeError"
            data-testid="finance-to-input"
          />
        </div>
        <div class="col-auto">
          <q-btn
            unelevated
            color="primary"
            :label="t('admin.finance.apply')"
            :loading="overviewLoading"
            :disable="rangeInvalid"
            data-testid="finance-apply-btn"
            @click="applyPeriod"
          />
        </div>
      </div>
      <div class="text-meta q-mb-md">{{ t('admin.finance.periodHint') }}</div>

      <q-banner v-if="overviewError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.finance.loadError') }}
      </q-banner>

      <!-- Overview -->
      <div v-if="overview" class="row q-col-gutter-md q-mb-lg" data-testid="finance-overview">
        <div v-for="m in overviewMetrics" :key="m.key" class="col-12 col-sm-6 col-md-4">
          <q-card flat bordered class="q-pa-md">
            <div class="text-meta">{{ m.label }}</div>
            <div class="text-h5 text-weight-bold" :data-testid="`finance-metric-${m.key}`">
              {{ formatAmount(m.value) }}
            </div>
          </q-card>
        </div>
        <div class="col-12 col-md-6">
          <q-card flat bordered class="q-pa-md">
            <div class="text-subtitle2">{{ t('admin.finance.coachSubscriptions') }}</div>
            <div v-if="!hasEntries(overview.activeCoachSubscriptions)" class="text-meta">
              {{ t('admin.finance.noSubscriptions') }}
            </div>
            <div
              v-for="(count, tier) in overview.activeCoachSubscriptions"
              :key="tier"
              class="row justify-between"
            >
              <span>{{ tier }}</span
              ><span class="text-weight-medium">{{ count }}</span>
            </div>
          </q-card>
        </div>
        <div class="col-12 col-md-6">
          <q-card flat bordered class="q-pa-md">
            <div class="text-subtitle2">{{ t('admin.finance.playerSubscriptions') }}</div>
            <div v-if="!hasEntries(overview.activePlayerSubscriptions)" class="text-meta">
              {{ t('admin.finance.noSubscriptions') }}
            </div>
            <div
              v-for="(count, tier) in overview.activePlayerSubscriptions"
              :key="tier"
              class="row justify-between"
            >
              <span>{{ tier }}</span
              ><span class="text-weight-medium">{{ count }}</span>
            </div>
          </q-card>
        </div>
      </div>
      <div class="text-meta q-mb-lg">{{ t('admin.finance.estimateNote') }}</div>

      <!-- Per-coach drill-down -->
      <div class="text-h6 q-mb-sm">{{ t('admin.finance.coachSectionTitle') }}</div>
      <div class="row q-col-gutter-sm items-start q-mb-md">
        <div class="col-12 col-sm-4 col-md-3">
          <q-select
            v-model="coachStatus"
            :options="coachStatusOptions"
            :label="t('admin.finance.coachStatusLabel')"
            emit-value
            map-options
            outlined
            dense
            data-testid="finance-coach-status"
            @update:model-value="onCoachStatusChange"
          />
        </div>
        <div class="col-12 col-sm-5 col-md-4">
          <q-input
            v-model="coachIdInput"
            outlined
            dense
            :label="t('admin.finance.coachIdLabel')"
            data-testid="finance-coach-id-input"
            @keyup.enter="openCoachById"
          />
        </div>
        <div class="col-auto">
          <q-btn
            outline
            color="primary"
            :label="t('admin.finance.viewRevenue')"
            :disable="!coachIdValid"
            data-testid="finance-coach-id-btn"
            @click="openCoachById"
          />
        </div>
      </div>

      <q-banner v-if="coachesError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.finance.coachesLoadError') }}
      </q-banner>

      <q-table
        :rows="coaches"
        :columns="coachColumns"
        row-key="coachId"
        flat
        :loading="coachesLoading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('admin.finance.noCoaches')"
      >
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat
              dense
              size="sm"
              color="primary"
              :label="t('admin.finance.viewRevenue')"
              data-testid="finance-coach-row-btn"
              @click="openCoach(props.row.coachId, props.row.coachName)"
            />
          </q-td>
        </template>
      </q-table>
      <div v-if="coachTotalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination
          v-model="coachPage"
          :max="coachTotalPages"
          @update:model-value="loadCoaches"
        />
      </div>
    </div>

    <!-- Coach revenue -->
    <q-dialog v-model="revenueDialogOpen">
      <q-card
        data-testid="finance-coach-dialog"
        style="min-width: 320px; max-width: 640px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.finance.coachRevenueTitle') }}</div>
          <div class="text-meta">{{ selectedCoachName || selectedCoachId }}</div>
          <div class="text-meta">{{ periodLabel }}</div>
        </q-card-section>
        <q-card-section v-if="revenueLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>
        <q-card-section v-else-if="revenueError" class="text-negative">
          {{ revenueError }}
        </q-card-section>
        <q-card-section v-else-if="revenue" data-testid="finance-coach-revenue">
          <q-list dense separator>
            <q-item v-for="m in revenueMetrics" :key="m.key">
              <q-item-section>{{ m.label }}</q-item-section>
              <q-item-section
                side
                class="text-weight-medium"
                :data-testid="`coach-metric-${m.key}`"
              >
                {{ m.format(m.value) }}
              </q-item-section>
            </q-item>
          </q-list>
          <div class="text-meta q-mt-sm">{{ t('admin.finance.coachRevenueNote') }}</div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.close')" v-close-popup />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { adminApi } from 'src/api/admin.api'
import { isUuid } from 'src/utils/adminFormat'

const { t, locale } = useI18n()

const fromDate = ref('')
const toDate = ref('')
// The period the loaded figures belong to; editing the inputs does not change them until Apply.
const appliedFrom = ref('')
const appliedTo = ref('')

const overview = ref(null)
const overviewLoading = ref(false)
const overviewError = ref(false)

const coachStatus = ref('ACTIVE')
const coaches = ref([])
const coachesLoading = ref(false)
const coachesError = ref(false)
const coachPage = ref(1)
const coachTotalPages = ref(0)
const coachIdInput = ref('')

const revenueDialogOpen = ref(false)
const revenueLoading = ref(false)
const revenueError = ref('')
const revenue = ref(null)
const selectedCoachId = ref(null)
const selectedCoachName = ref('')

// The backend defaults a missing 'from' to the start of the CURRENT month, so 'to' alone (e.g. the
// end of last month) would silently query an empty range and show zeros. Require 'from' with 'to'.
const fromMissing = computed(() => !!toDate.value && !fromDate.value)
const rangeInvalid = computed(
  () => fromMissing.value || (!!fromDate.value && !!toDate.value && fromDate.value > toDate.value),
)
const rangeError = computed(() =>
  fromMissing.value ? t('admin.finance.fromRequired') : t('admin.finance.rangeInvalid'),
)
const coachIdValid = computed(() => isUuid(coachIdInput.value.trim()))

const periodLabel = computed(() => {
  if (!appliedFrom.value && !appliedTo.value) return t('admin.finance.periodDefault')
  return t('admin.finance.periodRange', {
    from: appliedFrom.value || '…',
    to: appliedTo.value || '…',
  })
})

// There is no subscription-revenue figure: no subscription price amounts are stored locally, so the
// backend no longer sends one (it used to be a hard-coded 0). Stripe fees are the platform's own
// estimated processing cost; the coach drill-down shows no fee line because coaches don't pay it.
const overviewMetrics = computed(() =>
  overview.value
    ? [
        {
          key: 'gross',
          label: t('admin.finance.totalGrossVolume'),
          value: overview.value.totalGrossVolume,
        },
        {
          key: 'commission',
          label: t('admin.finance.totalCommission'),
          value: overview.value.totalCommissionCollected,
        },
        {
          key: 'stripeFees',
          label: t('admin.finance.totalStripeFees'),
          value: overview.value.totalStripeFees,
        },
        {
          key: 'refundCredit',
          label: t('admin.finance.totalRefundCredit'),
          value: overview.value.totalRefundCredit,
        },
        {
          key: 'cashOuts',
          label: t('admin.finance.totalCashOuts'),
          value: overview.value.totalCashOuts,
        },
      ]
    : [],
)

const revenueMetrics = computed(() => {
  const r = revenue.value
  if (!r) return []
  const money = (v) => formatMoney(v, r.currency)
  const plain = (v) => (v ?? 0).toString()
  return [
    { key: 'gross', label: t('admin.finance.coachGross'), value: r.grossEarnings, format: money },
    {
      key: 'commission',
      label: t('admin.finance.coachCommission'),
      value: r.commissionDeducted,
      format: money,
    },
    { key: 'net', label: t('admin.finance.coachNetPayout'), value: r.netPayout, format: money },
    {
      key: 'refunds',
      label: t('admin.finance.coachRefunds'),
      value: r.refundsIssued,
      format: money,
    },
    {
      key: 'sessions',
      label: t('admin.finance.coachSessions'),
      value: r.sessionCount,
      format: plain,
    },
    {
      key: 'pendingAmount',
      label: t('admin.finance.coachPendingAmount'),
      value: r.pendingReleaseAmount,
      format: money,
    },
    {
      key: 'pendingCount',
      label: t('admin.finance.coachPendingCount'),
      value: r.pendingReleaseCount,
      format: plain,
    },
    {
      key: 'strikes',
      label: t('admin.finance.coachStrikes'),
      value: r.reliabilityStrikeCount,
      format: plain,
    },
  ]
})

const coachStatusOptions = computed(() => [
  { label: t('admin.coaches.statuses.ACTIVE'), value: 'ACTIVE' },
  { label: t('admin.coaches.statuses.REDUCED'), value: 'REDUCED' },
  { label: t('admin.coaches.statuses.PENDING_REVIEW'), value: 'PENDING_REVIEW' },
  { label: t('admin.coaches.statuses.SUSPENDED'), value: 'SUSPENDED' },
  { label: t('admin.coaches.statuses.DEACTIVATED'), value: 'DEACTIVATED' },
])

const coachColumns = computed(() => [
  { name: 'coachName', label: t('admin.coaches.colCoach'), field: 'coachName', align: 'left' },
  { name: 'actions', label: t('admin.coaches.colActions'), field: 'actions', align: 'right' },
])

onMounted(async () => {
  await Promise.all([loadOverview(), loadCoaches()])
})

async function applyPeriod() {
  if (rangeInvalid.value) return
  await loadOverview()
}

async function loadOverview() {
  appliedFrom.value = fromDate.value
  appliedTo.value = toDate.value
  overviewLoading.value = true
  overviewError.value = false
  try {
    overview.value = await adminApi.getFinanceOverview(appliedFrom.value, appliedTo.value)
  } catch {
    overviewError.value = true
  } finally {
    overviewLoading.value = false
  }
}

async function loadCoaches() {
  coachesLoading.value = true
  coachesError.value = false
  try {
    const resp = await adminApi.getEnforcementCoaches(coachStatus.value, coachPage.value - 1)
    coaches.value = resp.content
    coachTotalPages.value = resp.totalPages
  } catch {
    coachesError.value = true
  } finally {
    coachesLoading.value = false
  }
}

async function onCoachStatusChange() {
  coachPage.value = 1
  await loadCoaches()
}

async function openCoachById() {
  if (!coachIdValid.value) return
  await openCoach(coachIdInput.value.trim(), '')
}

let revenueToken = 0

async function openCoach(coachId, coachName) {
  selectedCoachId.value = coachId
  selectedCoachName.value = coachName
  revenueDialogOpen.value = true
  await loadRevenue()
}

async function loadRevenue() {
  const token = ++revenueToken
  revenueLoading.value = true
  revenueError.value = ''
  revenue.value = null
  try {
    const resp = await adminApi.getCoachRevenue(
      selectedCoachId.value,
      appliedFrom.value,
      appliedTo.value,
    )
    if (token !== revenueToken) return
    revenue.value = resp
  } catch (err) {
    if (token !== revenueToken) return
    revenueError.value =
      err?.response?.status === 404
        ? t('admin.finance.coachNotFound')
        : t('admin.finance.coachRevenueLoadError')
  } finally {
    if (token === revenueToken) revenueLoading.value = false
  }
}

function hasEntries(map) {
  return !!map && Object.keys(map).length > 0
}

function formatAmount(value) {
  return new Intl.NumberFormat(locale.value, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(Number(value ?? 0))
}

function formatMoney(value, currency) {
  if (!currency) return formatAmount(value)
  try {
    return new Intl.NumberFormat(locale.value, { style: 'currency', currency }).format(
      Number(value ?? 0),
    )
  } catch {
    return `${formatAmount(value)} ${currency}`
  }
}
</script>
