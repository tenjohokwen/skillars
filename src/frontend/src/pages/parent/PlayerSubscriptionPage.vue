<template>
  <q-page class="q-pa-md">
    <div class="subscription-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('subscription.player.pageTitle') }}</div>

      <div v-if="loading" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <template v-else>
        <!-- fix/payment-subscription-payment-method-sync: past-due recovery.
             fix/payment-subscription-resume-and-async-sync: a saved card is applied to the subscription
             asynchronously, so after saving we say the payment is being retried, not that it is paid. -->
        <q-banner
          v-if="paymentIssue"
          class="q-mb-md"
          rounded
          style="background: rgba(255, 82, 82, 0.15)"
          data-testid="payment-issue-banner"
        >
          <template #avatar>
            <q-icon name="mdi-credit-card-off" color="negative" />
          </template>
          <span v-if="paymentRetryPending" data-testid="payment-retry-pending">{{
            t('subscription.paymentRetrying')
          }}</span>
          <span v-else-if="subscription?.status === 'PAUSED'">{{
            t('subscription.paymentPaused')
          }}</span>
          <span v-else>{{ t('subscription.paymentIssue') }}</span>
          <template #action>
            <q-btn
              flat
              color="primary"
              :label="t('subscription.updateCard')"
              data-testid="subscription-update-card-btn"
              @click="addCardDialog = true"
            />
          </template>
        </q-banner>

        <!-- Cancel pending banner -->
        <q-banner
          v-if="subscription?.cancelAtPeriodEnd"
          class="q-mb-md"
          rounded
          style="background: rgba(255, 165, 0, 0.15)"
          data-testid="subscription-cancel-pending"
        >
          <template #avatar>
            <q-icon name="mdi-alert" color="warning" />
          </template>
          {{
            t('subscription.player.cancelPending', {
              date: formatDate(subscription.currentPeriodEnd),
            })
          }}
        </q-banner>

        <!-- Current tier card -->
        <div class="current-tier-card glass-card q-pa-md q-mb-lg">
          <div class="text-subtitle1 text-weight-bold q-mb-sm">
            {{ t('subscription.currentPlan') }}
          </div>
          <div class="row items-center q-gutter-md">
            <div>
              <q-badge
                :color="tierColor(currentTier)"
                class="text-body1 q-px-md q-py-xs"
                data-testid="subscription-current-tier"
              >
                {{ currentTier }}
              </q-badge>
            </div>
            <div>
              <span class="text-caption text-secondary">{{ t('subscription.status') }}:</span>
              <q-chip
                dense
                :color="statusColor(subscription?.status)"
                text-color="white"
                class="q-ml-xs"
                data-testid="subscription-status"
              >
                {{ subscription?.status || 'ACTIVE' }}
              </q-chip>
            </div>
            <div v-if="subscription?.billingInterval">
              <span class="text-caption text-secondary"
                >{{ t('subscription.billingInterval') }}:</span
              >
              <span class="q-ml-xs">{{ subscription.billingInterval }}</span>
            </div>
            <div v-if="subscription?.currentPeriodEnd">
              <span class="text-caption text-secondary">{{ t('subscription.renewsOn') }}:</span>
              <span class="q-ml-xs" data-testid="subscription-period-end">{{
                formatDate(subscription.currentPeriodEnd)
              }}</span>
            </div>
          </div>
        </div>

        <!-- Tier comparison table -->
        <div class="text-subtitle1 text-weight-bold q-mb-sm">
          {{ t('subscription.availablePlans') }}
        </div>
        <div class="row q-gutter-md q-mb-lg">
          <div
            v-for="tier in tiers"
            :key="tier.tier"
            class="col tier-card glass-card q-pa-md"
            :class="{ 'current-tier': tier.tier === currentTier }"
            data-testid="subscription-tier-card"
            :data-tier="tier.tier"
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
              <!-- Mutating controls — shown to the profile's owner: the parent, or a self-registered
                   adult PLAYER on their own profile (the only profile the route's ownership-guarded
                   GET lets a PLAYER load). Server-side, subscribe/change-tier/cancel are guarded by
                   PlayerOwnershipGuard, which admits exactly those two callers. -->
              <q-btn
                v-if="canManage && canChoose(tier.tier)"
                color="primary"
                :label="t('subscription.upgrade')"
                class="full-width"
                data-testid="subscription-change-btn"
                @click="openSubscribeDialog(tier.tier)"
              />
              <q-btn
                v-else-if="
                  canManage &&
                  tier.tier === currentTier &&
                  hasPaidPlan &&
                  !subscription?.cancelAtPeriodEnd
                "
                color="negative"
                outline
                :label="t('subscription.cancel')"
                class="full-width"
                data-testid="subscription-cancel-btn"
                @click="handleCancel"
              />
              <!-- ATHLETE is free unless a subscription pays for it (the Cancel above then shows). -->
              <div
                v-else-if="tier.tier === 'ATHLETE' && !(tier.tier === currentTier && hasPaidPlan)"
                class="text-center text-caption text-secondary"
              >
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
          <!-- A tier change keeps the subscription's billing interval (changePlayerTier), so the
               interval is chosen only when subscribing. -->
          <q-select
            v-if="!hasPaidPlan"
            v-model="billingInterval"
            :options="availableIntervals"
            :label="t('subscription.billingInterval')"
            outlined
            dense
            class="q-mb-md"
            data-testid="subscription-interval"
          />
          <div
            v-if="hasCard === false"
            class="text-body2 text-warning q-mb-sm"
            data-testid="subscription-add-card-prompt"
          >
            {{ t('payment.card.addCardPrompt') }}
            <q-btn
              flat
              dense
              size="sm"
              color="primary"
              :label="t('payment.card.addCardCta')"
              @click="addCardDialog = true"
            />
          </div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            color="primary"
            :label="t('subscription.confirm')"
            :loading="actionLoading"
            :disable="hasCard === false"
            data-testid="subscription-confirm-btn"
            @click="confirmSubscribe"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <q-dialog v-model="addCardDialog">
      <q-card class="glass-card" style="min-width: 340px">
        <q-card-section>
          <div class="text-h6">{{ t('payment.card.title') }}</div>
        </q-card-section>
        <q-card-section class="q-pt-none">
          <PaymentMethodCard subscription-note @saved="onCardSaved" />
        </q-card-section>
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
import { useAuthStore } from 'src/stores/auth.store'
import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'
import { subscriptionErrorI18nKey } from 'src/utils/subscriptionErrors'

const { t, locale } = useI18n()
const $q = useQuasar()
const route = useRoute()
const paymentStore = usePaymentStore()
const authStore = useAuthStore()

const playerId = computed(() => route.params.playerId)
// Parent of the profile, or the self-registered player who owns it (route admits only these two).
const canManage = computed(() => authStore.isParent || authStore.isPlayer)

const loading = ref(false)
const actionLoading = ref(false)
const subscribeDialog = ref(false)
const addCardDialog = ref(false)
const selectedTier = ref('')
const billingInterval = ref('MONTHLY')

const subscription = computed(() => paymentStore.playerSubscription)
const currentTier = computed(() => subscription.value?.tier || 'ATHLETE')
// A Stripe subscription backs the plan: picking a new tier changes it rather than subscribing again,
// which the server refuses as already active. Not judged from the tier: ATHLETE is both the free
// fallback and a priced tier.
const hasPaidPlan = computed(() => subscription.value?.subscribed === true)
const tiers = computed(() => paymentStore.playerTiers)
// AC 5: gate confirm on a saved card instead of a raw, frontend-typed payment-method id — the
// backend resolves the saved card itself (SubscriptionService.subscribePlayer).
const hasCard = computed(() => paymentStore.savedPaymentMethod?.hasCard ?? false)

const paymentIssue = computed(() =>
  ['PAST_DUE', 'UNPAID', 'PAUSED'].includes(subscription.value?.status),
)
// The backend moves the subscription onto the new card on its outbox after the save returns, and the
// status only changes once Stripe's webhook arrives — so the banner switches to "retrying" copy.
const paymentRetryPending = ref(false)
function onCardSaved() {
  addCardDialog.value = false
  if (paymentIssue.value) paymentRetryPending.value = true
}

const TIER_ORDER = ['ATHLETE', 'SEMI_PRO', 'PRO']
const YEARLY_ONLY = ['SEMI_PRO', 'PRO']

const availableIntervals = computed(() => {
  if (YEARLY_ONLY.includes(selectedTier.value)) return ['YEARLY']
  return ['MONTHLY', 'QUARTERLY', 'YEARLY']
})

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([
      paymentStore.fetchPlayerSubscription(playerId.value),
      paymentStore.fetchPlayerTiers(),
      paymentStore.fetchSavedPaymentMethod(),
    ])
  } finally {
    loading.value = false
  }
})

function tierColor(tier) {
  return { ATHLETE: 'grey', SEMI_PRO: 'primary', PRO: 'purple' }[tier] || 'grey'
}

function statusColor(status) {
  return (
    {
      ACTIVE: 'positive',
      PAST_DUE: 'warning',
      UNPAID: 'negative',
      INCOMPLETE: 'warning',
      PAUSED: 'grey',
      CANCELLED: 'negative',
      TRIALLING: 'info',
    }[status] || 'grey'
  )
}

function isUpgrade(tier) {
  const current = TIER_ORDER.indexOf(currentTier.value)
  return TIER_ORDER.indexOf(tier) > current
}

// Subscribe from the free plan, or upgrade a paid one. A paid downgrade moves the Stripe price at once and
// the tier at period end, and this page can neither show that it is pending nor undo it, so it is not
// offered; nor is any change once the plan is set to cancel (an upgrade would charge proration on a plan
// that still ends).
function canChoose(tier) {
  if (tier === 'ATHLETE' || tier === currentTier.value) return false
  if (subscription.value?.cancelAtPeriodEnd) return false
  if (!hasPaidPlan.value) return true
  // A change keeps the plan's interval, and SEMI_PRO and PRO are sold yearly only.
  if (YEARLY_ONLY.includes(tier) && subscription.value?.billingInterval !== 'YEARLY') return false
  return isUpgrade(tier)
}

function openSubscribeDialog(tier) {
  selectedTier.value = tier
  billingInterval.value = tier === 'SEMI_PRO' || tier === 'PRO' ? 'YEARLY' : 'MONTHLY'
  subscribeDialog.value = true
}

async function confirmSubscribe() {
  if (hasCard.value === false) {
    $q.notify({ type: 'warning', message: t('payment.card.addCardPrompt') })
    return
  }
  actionLoading.value = true
  try {
    if (hasPaidPlan.value) {
      await paymentStore.changePlayerTier({ playerId: playerId.value, newTier: selectedTier.value })
    } else {
      await paymentStore.subscribePlayer({
        playerId: playerId.value,
        tier: selectedTier.value,
        billingInterval: billingInterval.value,
      })
    }
    subscribeDialog.value = false
    $q.notify({
      type: 'positive',
      message: t('subscription.player.subscribeSuccess', { tier: selectedTier.value }),
    })
  } catch (err) {
    $q.notify({
      type: 'negative',
      message: t(subscriptionErrorI18nKey(err, 'subscription.player.subscribeError')),
    })
  } finally {
    actionLoading.value = false
  }
}

async function handleCancel() {
  actionLoading.value = true
  try {
    await paymentStore.cancelPlayerSubscription(playerId.value)
    $q.notify({
      type: 'info',
      message: t('subscription.player.cancelSuccess', {
        date: formatDate(subscription.value?.currentPeriodEnd),
      }),
    })
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.player.cancelError') })
  } finally {
    actionLoading.value = false
  }
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString(locale.value)
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
