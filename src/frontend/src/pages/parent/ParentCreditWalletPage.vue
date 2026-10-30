<template>
  <q-page class="q-pa-md">
    <div class="row justify-center">
      <div class="col-12 col-md-8 col-lg-6">
        <h5 class="q-mt-none">{{ $t('payment.credits.title') }}</h5>

        <!-- Balance card -->
        <q-card class="glass-card q-mb-md">
          <q-card-section>
            <div class="text-subtitle2 text-grey-6">{{ $t('payment.credits.balance') }}</div>
            <div v-if="store.loading.creditBalance" class="text-h4">
              <q-skeleton type="text" width="120px" />
            </div>
            <div v-else class="text-h4 text-weight-bold">
              {{ balance !== null ? `€${Number(balance).toFixed(2)}` : '—' }}
            </div>
            <div v-if="spendOnly > 0" class="q-mt-sm" data-testid="wallet-split">
              <div class="text-body2">
                {{ $t('payment.credits.cashableBalance', { amount: cashable.toFixed(2) }) }}
              </div>
              <div class="text-body2">
                {{ $t('payment.credits.spendOnlyBalance', { amount: spendOnly.toFixed(2) }) }}
              </div>
              <div class="text-caption text-grey-6">{{ $t('payment.credits.spendOnlyHint') }}</div>
            </div>
          </q-card-section>
        </q-card>

        <!-- Payment method -->
        <q-card class="glass-card q-mb-md">
          <q-card-section>
            <div class="text-h6">{{ $t('payment.card.title') }}</div>
          </q-card-section>
          <q-card-section class="q-pt-none">
            <PaymentMethodCard />
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
              :disable="cashoutDisabled"
              @click="handleCashOut"
            />
            <q-banner
              v-if="cashoutError"
              class="q-mt-md"
              dense
              rounded
              inline-actions
              type="negative"
            >
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
import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'

const { t } = useI18n()
const $q = useQuasar()
const store = usePaymentStore()

const cashoutAmount = ref(null)
const cashingOut = ref(false)
const cashoutError = ref(null)

const balance = computed(() => store.creditBalance?.balance ?? null)
// Spend-only credit (an expired-pack refund) can be booked with but never cashed out; an older API response
// without the split counts everything as cashable.
const spendOnly = computed(() => Number(store.creditBalance?.spendOnlyBalance ?? 0))
const cashable = computed(() =>
  Number(store.creditBalance?.cashableBalance ?? store.creditBalance?.balance ?? 0),
)
const cashoutDisabled = computed(
  () =>
    !cashoutAmount.value ||
    cashoutAmount.value <= 0 ||
    balance.value === null ||
    cashoutAmount.value > cashable.value,
)

onMounted(async () => {
  await store.fetchCreditBalance()
})

// PaymentApiAdvice returns { helpCode: 'payment.<code>', errorMsg, fieldErrors } — there is no
// `message` field, so the old `data.message ?? insufficientCredit` fallback showed "insufficient
// credit" for every failure. Map the known cash-out codes; anything else gets a generic message.
const CASHOUT_ERROR_MESSAGES = {
  'payment.insufficientCredit': () => t('payment.credits.insufficientCredit'),
  'payment.noPaymentMethod': () => t('payment.credits.noPaymentMethod'),
  'payment.cashOutTooSmall': () => t('payment.credits.cashOutTooSmall'),
  'payment.cashOutExceedsRefundable': () => t('payment.credits.cashOutExceedsRefundable'),
  'payment.cashOutBlockedByDispute': () => t('payment.credits.cashOutBlockedByDispute'),
  'payment.cashOutExceedsCashable': () => t('payment.credits.cashOutExceedsCashable'),
  'payment.refundFailed': () => t('payment.credits.refundFailed'),
  'payment.refundOutcomeUnknown': () => t('payment.credits.refundOutcomeUnknown'),
  'payment.cashOutPartiallyRefunded': () => t('payment.credits.cashOutPartiallyRefunded'),
  'payment.cashOutTargetUnavailable': () => t('payment.credits.cashOutTargetUnavailable'),
}

function cashoutErrorMessage(err) {
  const message = CASHOUT_ERROR_MESSAGES[err?.response?.data?.helpCode]
  return message ? message() : t('payment.credits.cashoutFailed')
}

async function handleCashOut() {
  cashingOut.value = true
  cashoutError.value = null
  try {
    await cashOut(cashoutAmount.value)
    $q.notify({
      type: 'positive',
      message: t('payment.credits.cashoutSuccess', {
        amount: Number(cashoutAmount.value).toFixed(2),
      }),
    })
    cashoutAmount.value = null
    await store.fetchCreditBalance()
  } catch (err) {
    cashoutError.value = cashoutErrorMessage(err)
    // fix/audit3-wallet-cashout-integrity: a failed cash-out may still have moved money (a split cash-out
    // partly refunded, or an outcome still being checked), so show the real balance, not the stale one.
    // Best effort: a failed refresh must not replace the cash-out error.
    try {
      await store.fetchCreditBalance()
    } catch {
      // keep the cash-out error on screen
    }
  } finally {
    cashingOut.value = false
  }
}
</script>
