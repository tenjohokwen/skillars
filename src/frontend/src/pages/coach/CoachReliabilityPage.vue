<template>
  <q-page class="q-pa-md">
    <div class="reliability-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('reliability.pageTitle') }}</div>

      <q-banner
        v-if="coachProfile?.status === 'REDUCED'"
        class="q-mb-md"
        rounded
        style="background: rgba(255, 165, 0, 0.15)"
      >
        <template #avatar>
          <q-icon name="mdi-eye-off" color="warning" />
        </template>
        {{ t('reliability.statusReduced') }}
      </q-banner>

      <q-banner
        v-if="coachProfile?.status === 'PENDING_REVIEW'"
        class="q-mb-md"
        rounded
        style="background: rgba(200, 0, 0, 0.12)"
      >
        <template #avatar>
          <q-icon name="mdi-alert-circle" color="negative" />
        </template>
        {{ t('reliability.statusPendingReview') }}
      </q-banner>

      <div v-if="loading" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <div v-else-if="!strikes.length" class="text-center q-py-xl text-body2 text-secondary">
        {{ t('reliability.noStrikes') }}
      </div>

      <q-list v-else separator>
        <q-item v-for="strike in strikes" :key="strike.strikeId" class="q-py-sm">
          <q-item-section>
            <q-item-label>{{ formatReason(strike.reason) }}</q-item-label>
            <q-item-label caption>
              {{ t('reliability.strikeDate') }}: {{ formatDate(strike.issuedAt) }}
            </q-item-label>
            <q-item-label v-if="strike.bookingId" caption class="text-xs">
              {{ t('reliability.strikeBooking') }}: {{ strike.bookingId }}
            </q-item-label>
          </q-item-section>

          <q-item-section side>
            <q-chip
              v-if="strike.acknowledged"
              color="positive"
              text-color="white"
              dense
              icon="mdi-check"
            >
              {{ t('reliability.strikeAcknowledged') }}
            </q-chip>
            <q-btn
              v-else
              size="sm"
              color="warning"
              outline
              :label="t('reliability.acknowledgeBtn')"
              @click="handleAcknowledge(strike.strikeId)"
            />
          </q-item-section>
        </q-item>
      </q-list>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { usePaymentStore } from 'src/stores/payment.store'
import { getProfileBuilderStatus } from 'src/api/marketplace.api'

const { t } = useI18n()
const $q = useQuasar()
const paymentStore = usePaymentStore()

const loading = ref(false)
const coachProfile = ref(null)

const strikes = computed(() => paymentStore.coachStrikes)

onMounted(async () => {
  loading.value = true
  try {
    const [, profileRes] = await Promise.all([
      paymentStore.fetchCoachStrikes(),
      getProfileBuilderStatus(),
    ])
    coachProfile.value = profileRes.data
  } finally {
    loading.value = false
  }
})

async function handleAcknowledge(strikeId) {
  try {
    await paymentStore.acknowledgeStrike(strikeId)
    $q.notify({ type: 'positive', message: t('reliability.acknowledgeSuccess') })
  } catch {
    $q.notify({ type: 'negative', message: t('common.error') })
  }
}

function formatReason(reason) {
  const map = {
    COACH_CANCELLATION_UNEXCUSED: t('reliability.reasonCoachCancellationUnexcused'),
    COACH_NO_SHOW: t('reliability.reasonCoachNoShow'),
  }
  return map[reason] ?? reason
}

function formatDate(isoString) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleDateString()
}
</script>
