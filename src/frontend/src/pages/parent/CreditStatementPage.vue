<template>
  <q-page class="q-pa-md">
    <div class="credit-statement glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('creditStatement.pageTitle') }}</div>

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
          :label="t('creditStatement.apply')"
          :loading="paymentStore.loading"
          @click="loadStatement"
        />
      </div>

      <q-table
        :rows="paymentStore.creditStatement"
        :columns="columns"
        row-key="txId"
        flat
        :loading="paymentStore.loading"
        :pagination="{ rowsPerPage: 20 }"
      >
        <template #body-cell-createdAt="props">
          <q-td :props="props">{{ formatDate(props.row.createdAt) }}</q-td>
        </template>
        <template #body-cell-amount="props">
          <q-td :props="props" :class="props.row.amount >= 0 ? 'text-positive' : 'text-negative'">
            {{ props.row.amount >= 0 ? '+' : '-' }}€{{ fmt(props.row.amount) }}
          </q-td>
        </template>
        <template #body-cell-runningBalance="props">
          <q-td :props="props">€{{ fmtBalance(props.row.runningBalance) }}</q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              v-if="props.row.type === 'BOOKING_DEDUCTION' && props.row.referenceId"
              flat dense size="sm"
              :label="t('creditStatement.receipt')"
              @click="openReceipt(props.row.referenceId)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="statementPage && statementPage.totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination
          v-model="currentPage"
          :max="statementPage.totalPages"
          @update:model-value="loadPage"
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

const statementPage = computed(() => paymentStore.creditStatementPage)

const columns = [
  { name: 'createdAt', label: t('creditStatement.date'), field: 'createdAt', align: 'left' },
  { name: 'type', label: t('creditStatement.type'), field: 'type', align: 'left' },
  { name: 'description', label: t('creditStatement.description'), field: 'description', align: 'left' },
  { name: 'amount', label: t('creditStatement.amount'), field: 'amount', align: 'right' },
  { name: 'runningBalance', label: t('creditStatement.balance'), field: 'runningBalance', align: 'right' },
  { name: 'actions', label: '', field: 'actions', align: 'center' },
]

onMounted(() => loadStatement())

async function loadStatement() {
  currentPage.value = 1
  await paymentStore.fetchCreditStatement(fromDate.value, toDate.value, 0)
}

async function loadPage(page) {
  await paymentStore.fetchCreditStatement(fromDate.value, toDate.value, page - 1)
}

function openReceipt(bookingId) {
  const route = router.resolve({ name: 'parent-receipt', params: { bookingId } })
  window.open(route.href, '_blank')
}

function fmt(val) {
  return val != null ? Math.abs(Number(val)).toFixed(2) : '0.00'
}

function fmtBalance(val) {
  return val != null ? Number(val).toFixed(2) : '0.00'
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}
</script>
