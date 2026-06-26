<template>
  <q-page class="q-pa-md">
    <div class="revenue-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('revenue.pageTitle') }}</div>

      <!-- Period picker -->
      <div class="row q-gutter-md q-mb-lg">
        <q-input
          v-model="fromDate"
          :label="t('common.startDate')"
          type="date"
          outlined dense
          class="col"
        />
        <q-input
          v-model="toDate"
          :label="t('common.endDate')"
          type="date"
          outlined dense
          class="col"
        />
        <q-btn
          color="primary"
          :label="t('revenue.apply')"
          :loading="paymentStore.loading"
          @click="loadRevenue"
        />
      </div>

      <!-- Summary cards -->
      <div v-if="summary" class="row q-gutter-md q-mb-lg">
        <div class="col summary-card glass-card q-pa-md text-center">
          <div class="text-caption text-secondary">{{ t('revenue.gross') }}</div>
          <div class="text-h5 text-weight-bold">€{{ fmt(summary.grossEarnings) }}</div>
        </div>
        <div class="col summary-card glass-card q-pa-md text-center">
          <div class="text-caption text-secondary">{{ t('revenue.commission') }}</div>
          <div class="text-h5 text-negative">– €{{ fmt(summary.commissionDeducted) }}</div>
        </div>
        <div class="col summary-card glass-card q-pa-md text-center">
          <div class="text-caption text-secondary">{{ t('revenue.stripeFees') }}</div>
          <div class="text-h5 text-negative">– €{{ fmt(summary.stripeFees) }}</div>
        </div>
        <div class="col summary-card glass-card q-pa-md text-center">
          <div class="text-caption text-secondary">{{ t('revenue.netPayout') }}</div>
          <div class="text-h5 text-positive text-weight-bold">€{{ fmt(summary.netPayout) }}</div>
        </div>
        <div class="col summary-card glass-card q-pa-md text-center">
          <div class="text-caption text-secondary">{{ t('revenue.refunds') }}</div>
          <div class="text-h5 text-warning">€{{ fmt(summary.refundsIssued) }}</div>
        </div>
        <div class="col summary-card glass-card q-pa-md text-center">
          <div class="text-caption text-secondary">{{ t('revenue.sessions') }}</div>
          <div class="text-h5">{{ summary.sessionCount }}</div>
        </div>
      </div>

      <div class="text-caption text-secondary q-mb-md">{{ t('revenue.stripeFeeNote') }}</div>

      <!-- Transaction table -->
      <div class="text-subtitle1 q-mb-sm">{{ t('revenue.transactions') }}</div>
      <q-table
        :rows="paymentStore.transactions"
        :columns="columns"
        row-key="bookingId"
        flat
        :loading="paymentStore.loading"
        :pagination="{ rowsPerPage: 20 }"
      >
        <template #body-cell-sessionDate="props">
          <q-td :props="props">{{ formatDate(props.row.sessionDate) }}</q-td>
        </template>
        <template #body-cell-grossAmount="props">
          <q-td :props="props">€{{ fmt(props.row.grossAmount) }}</q-td>
        </template>
        <template #body-cell-commissionAmount="props">
          <q-td :props="props">€{{ fmt(props.row.commissionAmount) }}</q-td>
        </template>
        <template #body-cell-netAmount="props">
          <q-td :props="props">€{{ fmt(props.row.netAmount) }}</q-td>
        </template>
        <template #body-cell-creditUsed="props">
          <q-td :props="props">€{{ fmt(props.row.creditUsed) }}</q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat dense size="sm"
              :label="t('revenue.viewReceipt')"
              @click="openReceipt(props.row.bookingId)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="transactionPage && transactionPage.totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination
          v-model="currentPage"
          :max="transactionPage.totalPages"
          @update:model-value="loadTransactions"
        />
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { usePaymentStore } from 'src/stores/payment.store'

const { t } = useI18n()
const router = useRouter()
const paymentStore = usePaymentStore()

const currentPage = ref(1)

const today = new Date()
const firstOfMonth = new Date(today.getFullYear(), today.getMonth(), 1)
const fromDate = ref(firstOfMonth.toISOString().slice(0, 10))
const toDate = ref(today.toISOString().slice(0, 10))

const summary = computed(() => paymentStore.revenueSummary)
const transactionPage = computed(() => paymentStore.transactionPage)

const columns = [
  { name: 'playerName', label: t('revenue.player'), field: 'playerName', align: 'left' },
  { name: 'sessionDate', label: t('revenue.date'), field: 'sessionDate', align: 'left' },
  { name: 'grossAmount', label: t('revenue.gross'), field: 'grossAmount', align: 'right' },
  { name: 'commissionAmount', label: t('revenue.commission'), field: 'commissionAmount', align: 'right' },
  { name: 'netAmount', label: t('revenue.net'), field: 'netAmount', align: 'right' },
  { name: 'creditUsed', label: t('revenue.creditUsed'), field: 'creditUsed', align: 'right' },
  { name: 'status', label: t('revenue.status'), field: 'status', align: 'center' },
  { name: 'actions', label: '', field: 'actions', align: 'center' },
]

onMounted(() => loadRevenue())

async function loadRevenue() {
  currentPage.value = 1
  await Promise.all([
    paymentStore.fetchRevenueSummary(fromDate.value, toDate.value),
    paymentStore.fetchTransactions(fromDate.value, toDate.value, 0),
  ])
}

async function loadTransactions(page) {
  await paymentStore.fetchTransactions(fromDate.value, toDate.value, page - 1)
}

function openReceipt(bookingId) {
  const route = router.resolve({ name: 'coach-receipt', params: { bookingId } })
  window.open(route.href, '_blank')
}

function fmt(val) {
  return val != null ? Number(val).toFixed(2) : '0.00'
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}
</script>

<style scoped>
.summary-card {
  min-width: 120px;
}
</style>
