<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.gdpr.title') }}</div>
        <div class="text-meta">{{ t('admin.gdpr.subtitle') }}</div>
      </div>

      <div class="row q-col-gutter-sm q-mb-md">
        <div class="col-12 col-sm-4 col-md-3">
          <q-select
            v-model="typeFilter"
            :options="typeOptions"
            :label="t('admin.gdpr.typeFilterLabel')"
            emit-value
            map-options
            outlined
            dense
            data-testid="gdpr-type-filter"
            @update:model-value="onFilterChange"
          />
        </div>
        <div class="col-12 col-sm-4 col-md-3">
          <q-select
            v-model="statusFilter"
            :options="statusOptions"
            :label="t('admin.gdpr.statusFilterLabel')"
            emit-value
            map-options
            outlined
            dense
            data-testid="gdpr-status-filter"
            @update:model-value="onFilterChange"
          />
        </div>
      </div>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.gdpr.loadError') }}
      </q-banner>

      <q-table
        :rows="rows"
        :columns="columns"
        row-key="requestId"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: PAGE_SIZE }"
        hide-pagination
        :no-data-label="t('admin.gdpr.emptyState')"
      >
        <template #body-cell-requestId="props">
          <q-td :props="props">
            <span class="gdpr-id" :title="props.row.requestId">
              {{ shortId(props.row.requestId) }}
            </span>
          </q-td>
        </template>
        <template #body-cell-playerId="props">
          <q-td :props="props">{{ props.row.playerId ?? '—' }}</q-td>
        </template>
        <template #body-cell-requestType="props">
          <q-td :props="props">{{ typeLabel(props.row.requestType) }}</q-td>
        </template>
        <template #body-cell-status="props">
          <q-td :props="props">
            <q-chip dense :color="statusColor(props.row.status)" text-color="white">
              {{ statusLabel(props.row.status) }}
            </q-chip>
            <q-chip
              v-if="isOverdue(props.row)"
              dense
              outline
              color="negative"
              data-testid="gdpr-overdue-chip"
            >
              {{ t('admin.gdpr.overdue') }}
            </q-chip>
          </q-td>
        </template>
        <template #body-cell-createdAt="props">
          <q-td :props="props">{{ formatDate(props.row.createdAt) }}</q-td>
        </template>
        <template #body-cell-completedAt="props">
          <q-td :props="props">{{ formatDate(props.row.completedAt) }}</q-td>
        </template>
      </q-table>

      <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
      </div>

      <div class="text-meta q-mt-md">{{ t('admin.gdpr.overdueHint') }}</div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { adminApi } from 'src/api/admin.api'
import { formatDateTime } from 'src/utils/adminFormat'

const { t, locale } = useI18n()

const PAGE_SIZE = 20
// GDPR Art. 12(3): a request must be answered within one month. Flag every unanswered request
// (still open, or FAILED — a failed export/erasure has not been answered either) past 30 days.
const OVERDUE_DAYS = 30
const UNANSWERED_STATUSES = ['PENDING', 'PROCESSING', 'FAILED']

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)
const typeFilter = ref(null)
const statusFilter = ref(null)

const typeLabels = computed(() => ({
  EXPORT: t('admin.gdpr.types.EXPORT'),
  ERASURE: t('admin.gdpr.types.ERASURE'),
}))

const statusLabels = computed(() => ({
  PENDING: t('admin.gdpr.statuses.PENDING'),
  PROCESSING: t('admin.gdpr.statuses.PROCESSING'),
  COMPLETED: t('admin.gdpr.statuses.COMPLETED'),
  FAILED: t('admin.gdpr.statuses.FAILED'),
}))

const typeOptions = computed(() => [
  { label: t('admin.gdpr.allTypes'), value: null },
  { label: typeLabels.value.EXPORT, value: 'EXPORT' },
  { label: typeLabels.value.ERASURE, value: 'ERASURE' },
])

const statusOptions = computed(() => [
  { label: t('admin.gdpr.allStatuses'), value: null },
  ...Object.entries(statusLabels.value).map(([value, label]) => ({ label, value })),
])

// The player-scope column (a parent's request about a child profile) only exists once the backend
// returns a `playerId` field on the rows — it is added by the GDPR child-scope change. Until then the
// column stays hidden rather than showing an always-empty column.
const showPlayerColumn = computed(() =>
  rows.value.some((r) => Object.prototype.hasOwnProperty.call(r, 'playerId')),
)

const columns = computed(() => [
  { name: 'requestId', label: t('admin.gdpr.colRequest'), field: 'requestId', align: 'left' },
  { name: 'userId', label: t('admin.gdpr.colUser'), field: 'userId', align: 'left' },
  ...(showPlayerColumn.value
    ? [{ name: 'playerId', label: t('admin.gdpr.colPlayer'), field: 'playerId', align: 'left' }]
    : []),
  { name: 'requestType', label: t('admin.gdpr.colType'), field: 'requestType', align: 'left' },
  { name: 'status', label: t('admin.gdpr.colStatus'), field: 'status', align: 'left' },
  { name: 'createdAt', label: t('admin.gdpr.colCreatedAt'), field: 'createdAt', align: 'left' },
  {
    name: 'completedAt',
    label: t('admin.gdpr.colCompletedAt'),
    field: 'completedAt',
    align: 'left',
  },
])

function typeLabel(type) {
  return typeLabels.value[type] ?? type
}

function statusLabel(status) {
  return statusLabels.value[status] ?? status
}

function statusColor(status) {
  return (
    { PENDING: 'grey-7', PROCESSING: 'info', COMPLETED: 'positive', FAILED: 'negative' }[status] ??
    'grey-7'
  )
}

function isOverdue(row) {
  if (!UNANSWERED_STATUSES.includes(row.status) || !row.createdAt) return false
  return Date.now() - new Date(row.createdAt).getTime() > OVERDUE_DAYS * 24 * 60 * 60 * 1000
}

function shortId(id) {
  return id ? String(id).slice(0, 8) : '—'
}

onMounted(loadList)

async function loadList() {
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.getGdprRequests({
      type: typeFilter.value,
      status: statusFilter.value,
      page: currentPage.value - 1,
      size: PAGE_SIZE,
    })
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

async function onFilterChange() {
  currentPage.value = 1
  await loadList()
}

function formatDate(iso) {
  return formatDateTime(iso, locale.value)
}
</script>

<style scoped>
.gdpr-id {
  font-family: monospace;
}
</style>
