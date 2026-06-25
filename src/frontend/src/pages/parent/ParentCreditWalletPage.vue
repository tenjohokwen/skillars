<template>
  <q-page class="q-pa-md">
    <div class="row justify-center">
      <div class="col-12 col-md-8 col-lg-6">
        <h5 class="q-mt-none">{{ $t('payment.credits.title') }}</h5>

        <!-- Balance card -->
        <q-card class="glass-card q-mb-md">
          <q-card-section>
            <div class="text-subtitle2 text-grey-6">{{ $t('payment.credits.balance') }}</div>
            <div v-if="store.loading" class="text-h4">
              <q-skeleton type="text" width="120px" />
            </div>
            <div v-else class="text-h4 text-weight-bold">
              {{ balance !== null ? `€${Number(balance).toFixed(2)}` : '—' }}
            </div>
          </q-card-section>
        </q-card>

        <!-- Cash-out form -->
        <q-card class="glass-card q-mb-md">
          <q-card-section>
            <div class="text-h6">{{ $t('payment.credits.cashout') }}</div>
          </q-card-section>
          <q-card-section class="q-pt-none">
            <q-input
              v-model.number="cashoutAmount"
              type="number"
              :label="$t('payment.credits.cashoutLabel')"
              outlined
              :min="0.01"
              :step="0.01"
              class="q-mb-md"
            />
            <q-btn
              :label="$t('payment.credits.cashout')"
              color="primary"
              unelevated
              :loading="cashingOut"
              :disable="!cashoutAmount || cashoutAmount <= 0 || balance === null || cashoutAmount > balance"
              @click="handleCashOut"
            />
            <q-banner v-if="cashoutError" class="q-mt-md" dense rounded inline-actions type="negative">
              {{ cashoutError }}
            </q-banner>
          </q-card-section>
        </q-card>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { usePaymentStore } from 'src/stores/payment.store'
import { cashOut } from 'src/api/payment.api'

const { t } = useI18n()
const $q = useQuasar()
const store = usePaymentStore()

const cashoutAmount = ref(null)
const cashingOut = ref(false)
const cashoutError = ref(null)

const balance = computed(() => store.creditBalance?.balance ?? null)

onMounted(async () => {
  await store.fetchCreditBalance()
})

async function handleCashOut() {
  cashingOut.value = true
  cashoutError.value = null
  try {
    await cashOut(cashoutAmount.value)
    $q.notify({ type: 'positive', message: t('payment.credits.cashoutSuccess', {
      amount: Number(cashoutAmount.value).toFixed(2),
    }) })
    cashoutAmount.value = null
    await store.fetchCreditBalance()
  } catch (err) {
    cashoutError.value = err?.response?.data?.message ?? t('payment.credits.insufficientCredit')
  } finally {
    cashingOut.value = false
  }
}
</script>
