<template>
  <q-page class="q-pa-lg">
    <div v-if="loading" class="flex flex-center q-py-xl">
      <q-spinner size="48px" />
    </div>

    <div v-else-if="receipt" class="receipt-container">
      <!-- Print controls (hidden when printing) -->
      <div class="no-print q-mb-md row justify-end">
        <q-btn color="primary" icon="print" :label="t('revenue.receipt.print')" @click="printPage" />
      </div>

      <!-- Receipt content -->
      <div class="receipt-card glass-card q-pa-xl">
        <div class="text-h5 text-weight-bold q-mb-xs">{{ t('revenue.receipt.title') }}</div>
        <div class="text-caption text-secondary q-mb-lg">{{ receipt.platformName }}</div>

        <q-separator class="q-mb-lg" />

        <div class="row q-mb-sm">
          <div class="col text-secondary">{{ t('revenue.receipt.coach') }}</div>
          <div class="col text-weight-medium">{{ receipt.coachName }}</div>
        </div>
        <div class="row q-mb-sm">
          <div class="col text-secondary">{{ t('revenue.receipt.player') }}</div>
          <div class="col text-weight-medium">{{ receipt.playerFirstName }}</div>
        </div>
        <div class="row q-mb-sm">
          <div class="col text-secondary">{{ t('revenue.receipt.sessionDate') }}</div>
          <div class="col">{{ formatDate(receipt.sessionDate) }}</div>
        </div>

        <q-separator class="q-my-lg" />

        <div class="row q-mb-sm">
          <div class="col text-secondary">{{ t('revenue.receipt.gross') }}</div>
          <div class="col text-right">€{{ fmt(receipt.grossAmount) }}</div>
        </div>
        <div class="row q-mb-sm text-negative">
          <div class="col text-secondary">{{ t('revenue.receipt.commission') }}</div>
          <div class="col text-right">– €{{ fmt(receipt.commissionDeducted) }}</div>
        </div>

        <q-separator class="q-my-md" />

        <div class="row text-h6 text-weight-bold">
          <div class="col">{{ t('revenue.receipt.net') }}</div>
          <div class="col text-right text-positive">€{{ fmt(receipt.netReceived) }}</div>
        </div>
      </div>
    </div>

    <div v-else class="flex flex-center q-py-xl text-secondary">
      {{ t('revenue.receipt.notFound') }}
    </div>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { fetchCoachReceipt } from 'src/api/payment.api'

const { t } = useI18n()
const route = useRoute()

const loading = ref(true)
const receipt = ref(null)

onMounted(async () => {
  try {
    const { data } = await fetchCoachReceipt(route.params.bookingId)
    receipt.value = data
  } finally {
    loading.value = false
  }
})

function printPage() {
  window.print()
}

function fmt(val) {
  return val != null ? Number(val).toFixed(2) : '0.00'
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'long', timeStyle: 'short' }).format(new Date(iso))
}
</script>

<style scoped>
.receipt-container {
  max-width: 600px;
  margin: 0 auto;
}
.receipt-card {
  border-radius: 12px;
}

@media print {
  .no-print {
    display: none;
  }
  nav,
  header,
  aside,
  .q-drawer,
  .q-header {
    display: none !important;
  }
  .receipt-container {
    max-width: 100%;
  }
}
</style>
