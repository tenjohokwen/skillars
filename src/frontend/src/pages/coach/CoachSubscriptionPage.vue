<template>
  <q-page class="q-pa-md">
    <div class="subscription-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('subscription.coach.pageTitle') }}</div>

      <div v-if="loading" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <template v-else>
        <!-- Current subscription banner -->
        <q-banner
          v-if="subscription?.cancelAtPeriodEnd"
          class="q-mb-md"
          rounded
          style="background: rgba(255, 165, 0, 0.15)"
        >
          <template #avatar>
            <q-icon name="mdi-alert" color="warning" />
          </template>
          {{ t('subscription.coach.cancelPending', { date: formatDate(subscription.currentPeriodEnd) }) }}
        </q-banner>

        <!-- Current tier card -->
        <div class="current-tier-card glass-card q-pa-md q-mb-lg">
          <div class="text-subtitle1 text-weight-bold q-mb-sm">{{ t('subscription.currentPlan') }}</div>
          <div class="row items-center q-gutter-md">
            <div>
              <q-badge :color="tierColor(subscription?.tier)" class="text-body1 q-px-md q-py-xs">
                {{ subscription?.tier || 'SCOUT' }}
              </q-badge>
            </div>
            <div>
              <span class="text-caption text-secondary">{{ t('subscription.status') }}:</span>
              <q-chip dense :color="statusColor(subscription?.status)" text-color="white" class="q-ml-xs">
                {{ subscription?.status || 'ACTIVE' }}
              </q-chip>
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
            <div v-if="tier.monthlyPrice" class="text-body2 text-secondary q-mb-sm">
              {{ t('subscription.perMonth', { price: tier.monthlyPrice }) }}
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
              <!-- Upgrade / Subscribe CTA — gated if current is higher tier -->
              <div
                v-if="isTierBlocked(tier.tier)"
                class="tier-gate-overlay relative-position"
              >
                <div class="blurred-preview"></div>
                <div class="overlay-cta absolute-center text-center">
                  <q-icon name="mdi-lock" size="2rem" />
                  <div class="text-caption">{{ t('subscription.upgradeToUnlock') }}</div>
                  <q-btn
                    size="sm"
                    color="primary"
                    :label="t('subscription.upgradeCTA')"
                    class="q-mt-xs"
                    @click="openSubscribeDialog(tier.tier)"
                  />
                </div>
              </div>
              <q-btn
                v-else-if="tier.tier !== subscription?.tier && tier.tier !== 'SCOUT'"
                color="primary"
                :label="isUpgrade(tier.tier) ? t('subscription.upgrade') : t('subscription.downgrade')"
                class="full-width"
                @click="handleChangeTier(tier.tier)"
              />
              <q-btn
                v-else-if="tier.tier === subscription?.tier && tier.tier !== 'SCOUT' && !subscription?.cancelAtPeriodEnd"
                color="negative"
                outline
                :label="t('subscription.cancel')"
                class="full-width"
                @click="handleCancel"
              />
              <div v-else-if="tier.tier === 'SCOUT'" class="text-center text-caption text-secondary">
                {{ t('subscription.freePlan') }}
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- Subscribe dialog -->
    <q-dialog v-model="subscribeDialog">
      <q-card class="glass-card" style="min-width: 350px">
        <q-card-section>
          <div class="text-h6">{{ t('subscription.subscribeTo', { tier: selectedTier }) }}</div>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="paymentMethodId"
            :label="t('subscription.paymentMethodId')"
            outlined
            dense
          />
          <div class="text-caption text-secondary q-mt-xs">{{ t('subscription.paymentMethodHint') }}</div>
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
import { usePaymentStore } from 'src/stores/payment.store'

const { t } = useI18n()
const $q = useQuasar()
const paymentStore = usePaymentStore()

const loading = ref(false)
const actionLoading = ref(false)
const subscribeDialog = ref(false)
const selectedTier = ref('')
const paymentMethodId = ref('')

const subscription = computed(() => paymentStore.coachSubscription)
const tiers = computed(() => paymentStore.coachTiers)

const TIER_ORDER = ['SCOUT', 'INSTRUCTOR', 'ACADEMY']

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([paymentStore.fetchCoachSubscription(), paymentStore.fetchCoachTiers()])
  } finally {
    loading.value = false
  }
})

function tierColor(tier) {
  return { SCOUT: 'grey', INSTRUCTOR: 'primary', ACADEMY: 'purple' }[tier] || 'grey'
}

function statusColor(status) {
  return { ACTIVE: 'positive', PAST_DUE: 'warning', CANCELLED: 'negative', TRIALLING: 'info' }[status] || 'grey'
}

function isUpgrade(tier) {
  const current = TIER_ORDER.indexOf(subscription.value?.tier || 'SCOUT')
  return TIER_ORDER.indexOf(tier) > current
}

function isTierBlocked(tier) {
  if (tier === 'SCOUT') return false
  if (!subscription.value) return false
  const current = TIER_ORDER.indexOf(subscription.value.tier || 'SCOUT')
  return TIER_ORDER.indexOf(tier) < current
}

function openSubscribeDialog(tier) {
  selectedTier.value = tier
  subscribeDialog.value = true
}

async function confirmSubscribe() {
  if (!paymentMethodId.value) {
    $q.notify({ type: 'warning', message: t('subscription.coach.paymentMethodRequired') })
    return
  }
  actionLoading.value = true
  try {
    await paymentStore.subscribeCoach({ tier: selectedTier.value, paymentMethodId: paymentMethodId.value })
    subscribeDialog.value = false
    $q.notify({ type: 'positive', message: t('subscription.coach.subscribeSuccess', { tier: selectedTier.value }) })
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.coach.subscribeError') })
  } finally {
    actionLoading.value = false
  }
}

async function handleChangeTier(newTier) {
  actionLoading.value = true
  try {
    await paymentStore.changeCoachTier(newTier)
    $q.notify({ type: 'positive', message: t('subscription.coach.tierChangeSuccess', { tier: newTier }) })
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.coach.tierChangeError') })
  } finally {
    actionLoading.value = false
  }
}

async function handleCancel() {
  actionLoading.value = true
  try {
    await paymentStore.cancelCoachSubscription()
    $q.notify({ type: 'info', message: t('subscription.coach.cancelSuccess', { date: formatDate(subscription.value?.currentPeriodEnd) }) })
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.coach.cancelError') })
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
.tier-gate-overlay {
  min-height: 80px;
}
.blurred-preview {
  filter: blur(4px);
  min-height: 60px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 8px;
}
.overlay-cta {
  padding: 8px;
}
</style>
