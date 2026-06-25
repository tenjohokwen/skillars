<template>
  <q-page class="q-pa-md">
    <div class="subscription-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('subscription.player.pageTitle') }}</div>

      <div v-if="loading" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <template v-else>
        <!-- Cancel pending banner -->
        <q-banner
          v-if="subscription?.cancelAtPeriodEnd"
          class="q-mb-md"
          rounded
          style="background: rgba(255, 165, 0, 0.15)"
        >
          <template #avatar>
            <q-icon name="mdi-alert" color="warning" />
          </template>
          {{ t('subscription.player.cancelPending', { date: formatDate(subscription.currentPeriodEnd) }) }}
        </q-banner>

        <!-- Current tier card -->
        <div class="current-tier-card glass-card q-pa-md q-mb-lg">
          <div class="text-subtitle1 text-weight-bold q-mb-sm">{{ t('subscription.currentPlan') }}</div>
          <div class="row items-center q-gutter-md">
            <div>
              <q-badge :color="tierColor(subscription?.tier)" class="text-body1 q-px-md q-py-xs">
                {{ subscription?.tier || 'ATHLETE' }}
              </q-badge>
            </div>
            <div>
              <span class="text-caption text-secondary">{{ t('subscription.status') }}:</span>
              <q-chip dense :color="statusColor(subscription?.status)" text-color="white" class="q-ml-xs">
                {{ subscription?.status || 'ACTIVE' }}
              </q-chip>
            </div>
            <div v-if="subscription?.billingInterval">
              <span class="text-caption text-secondary">{{ t('subscription.billingInterval') }}:</span>
              <span class="q-ml-xs">{{ subscription.billingInterval }}</span>
            </div>
            <div v-if="subscription?.currentPeriodEnd">
              <span class="text-caption text-secondary">{{ t('subscription.renewsOn') }}:</span>
              <span class="q-ml-xs">{{ formatDate(subscription.currentPeriodEnd) }}</span>
            </div>
          </div>
        </div>

        <!-- Tier comparison table -->
        <div class="text-subtitle1 text-weight-bold q-mb-sm">{{ t('subscription.availablePlans') }}</div>
        <div class="row q-gutter-md q-mb-lg">
          <div
            v-for="tier in tiers"
            :key="tier.tier"
            class="col tier-card glass-card q-pa-md"
            :class="{ 'current-tier': tier.tier === subscription?.tier }"
          >
            <div class="text-h6 text-weight-bold q-mb-xs">{{ tier.tier }}</div>
            <div v-if="tier.monthlyPrice" class="text-body2 text-secondary q-mb-xs">
              {{ t('subscription.perMonth', { price: tier.monthlyPrice }) }}
            </div>
            <div v-if="tier.annualPrice" class="text-body2 text-secondary q-mb-sm">
              {{ t('subscription.perYear', { price: tier.annualPrice }) }}
            </div>
            <q-list dense class="q-mb-md">
              <q-item v-for="feature in tier.features" :key="feature" dense class="q-px-none">
                <q-item-section avatar>
                  <q-icon name="mdi-check-circle" color="positive" size="sm" />
                </q-item-section>
                <q-item-section>{{ feature }}</q-item-section>
              </q-item>
            </q-list>
            <div class="tier-actions">
              <q-btn
                v-if="tier.tier !== subscription?.tier && tier.tier !== 'ATHLETE'"
                color="primary"
                :label="isUpgrade(tier.tier) ? t('subscription.upgrade') : t('subscription.downgrade')"
                class="full-width"
                @click="openSubscribeDialog(tier.tier)"
              />
              <q-btn
                v-else-if="tier.tier === subscription?.tier && tier.tier !== 'ATHLETE' && !subscription?.cancelAtPeriodEnd"
                color="negative"
                outline
                :label="t('subscription.cancel')"
                class="full-width"
                @click="handleCancel"
              />
              <div v-else-if="tier.tier === 'ATHLETE'" class="text-center text-caption text-secondary">
                {{ t('subscription.freePlan') }}
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- Subscribe / Change dialog -->
    <q-dialog v-model="subscribeDialog">
      <q-card class="glass-card" style="min-width: 350px">
        <q-card-section>
          <div class="text-h6">{{ t('subscription.subscribeTo', { tier: selectedTier }) }}</div>
        </q-card-section>
        <q-card-section>
          <q-select
            v-model="billingInterval"
            :options="availableIntervals"
            :label="t('subscription.billingInterval')"
            outlined
            dense
            class="q-mb-md"
          />
          <q-input
            v-model="paymentMethodId"
            :label="t('subscription.paymentMethodId')"
            outlined
            dense
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            color="primary"
            :label="t('subscription.confirm')"
            :loading="actionLoading"
            @click="confirmSubscribe"
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
import { usePaymentStore } from 'src/stores/payment.store'

const { t } = useI18n()
const $q = useQuasar()
const route = useRoute()
const paymentStore = usePaymentStore()

const playerId = computed(() => Number(route.params.playerId))

const loading = ref(false)
const actionLoading = ref(false)
const subscribeDialog = ref(false)
const selectedTier = ref('')
const billingInterval = ref('MONTHLY')
const paymentMethodId = ref('')

const subscription = computed(() => paymentStore.playerSubscription)
const tiers = computed(() => paymentStore.playerTiers)

const TIER_ORDER = ['ATHLETE', 'SEMI_PRO', 'PRO']

const availableIntervals = computed(() => {
  if (selectedTier.value === 'SEMI_PRO' || selectedTier.value === 'PRO') return ['YEARLY']
  return ['MONTHLY', 'QUARTERLY', 'YEARLY']
})

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([
      paymentStore.fetchPlayerSubscription(playerId.value),
      paymentStore.fetchPlayerTiers(),
    ])
  } finally {
    loading.value = false
  }
})

function tierColor(tier) {
  return { ATHLETE: 'grey', SEMI_PRO: 'primary', PRO: 'purple' }[tier] || 'grey'
}

function statusColor(status) {
  return { ACTIVE: 'positive', PAST_DUE: 'warning', CANCELLED: 'negative', TRIALLING: 'info' }[status] || 'grey'
}

function isUpgrade(tier) {
  const current = TIER_ORDER.indexOf(subscription.value?.tier || 'ATHLETE')
  return TIER_ORDER.indexOf(tier) > current
}

function openSubscribeDialog(tier) {
  selectedTier.value = tier
  billingInterval.value = tier === 'SEMI_PRO' || tier === 'PRO' ? 'YEARLY' : 'MONTHLY'
  subscribeDialog.value = true
}

async function confirmSubscribe() {
  if (!paymentMethodId.value) {
    $q.notify({ type: 'warning', message: t('subscription.player.paymentMethodRequired') })
    return
  }
  actionLoading.value = true
  try {
    const isAlreadySubscribed =
      subscription.value?.stripeSubscriptionId != null &&
      subscription.value?.status === 'ACTIVE'

    if (isAlreadySubscribed) {
      await paymentStore.changePlayerTier({ playerId: playerId.value, newTier: selectedTier.value })
    } else {
      await paymentStore.subscribePlayer({
        playerId: playerId.value,
        tier: selectedTier.value,
        billingInterval: billingInterval.value,
        paymentMethodId: paymentMethodId.value,
      })
    }
    subscribeDialog.value = false
    $q.notify({ type: 'positive', message: t('subscription.player.subscribeSuccess', { tier: selectedTier.value }) })
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.player.subscribeError') })
  } finally {
    actionLoading.value = false
  }
}

async function handleCancel() {
  actionLoading.value = true
  try {
    await paymentStore.cancelPlayerSubscription(playerId.value)
    $q.notify({ type: 'info', message: t('subscription.player.cancelSuccess', { date: formatDate(subscription.value?.currentPeriodEnd) }) })
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.player.cancelError') })
  } finally {
    actionLoading.value = false
  }
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString()
}
</script>

<style scoped>
.tier-card {
  min-width: 200px;
  flex: 1;
}
.current-tier {
  border: 2px solid var(--q-primary);
}
</style>
