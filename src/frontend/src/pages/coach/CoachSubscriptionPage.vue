<template>
  <q-page class="q-pa-md">
    <div class="subscription-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('subscription.coach.pageTitle') }}</div>

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
          data-test="payment-issue-banner"
        >
          <template #avatar>
            <q-icon name="mdi-credit-card-off" color="negative" />
          </template>
          <span v-if="paymentRetryPending" data-test="payment-retry-pending">{{
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

        <!-- Current subscription banner -->
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
            t('subscription.coach.cancelPending', {
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
              <!-- A Scout coach has no paid subscription yet, so any paid tier subscribes (the dialog
                   checks for a card first); a paid coach upgrades the subscription they have. Moving
                   down to Scout is the Cancel button on the current paid tier. -->
              <q-btn
                v-if="canChoose(tier.tier)"
                color="primary"
                :label="t('subscription.upgrade')"
                class="full-width"
                :loading="actionLoading"
                data-testid="subscription-change-btn"
                @click="chooseTier(tier.tier)"
              />
              <q-btn
                v-else-if="
                  tier.tier === subscription?.tier &&
                  tier.tier !== 'SCOUT' &&
                  !subscription?.cancelAtPeriodEnd
                "
                color="negative"
                outline
                :label="t('subscription.cancel')"
                class="full-width"
                data-testid="subscription-cancel-btn"
                @click="handleCancel"
              />
              <div
                v-else-if="tier.tier === 'SCOUT'"
                class="text-center text-caption text-secondary"
              >
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
import { usePaymentStore } from 'src/stores/payment.store'
import PaymentMethodCard from 'src/components/payment/PaymentMethodCard.vue'
import { subscriptionErrorI18nKey } from 'src/utils/subscriptionErrors'

const { t, locale } = useI18n()
const $q = useQuasar()
const paymentStore = usePaymentStore()

const loading = ref(false)
const actionLoading = ref(false)
const subscribeDialog = ref(false)
const addCardDialog = ref(false)
const selectedTier = ref('')

const subscription = computed(() => paymentStore.coachSubscription)
const currentTier = computed(() => subscription.value?.tier || 'SCOUT')
const tiers = computed(() => paymentStore.coachTiers)
// AC 3: gate confirm on a saved card instead of a raw, frontend-typed payment-method id — the
// backend resolves the saved card itself (SubscriptionService.subscribeCoach).
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

const TIER_ORDER = ['SCOUT', 'INSTRUCTOR', 'ACADEMY']

onMounted(async () => {
  loading.value = true
  try {
    await Promise.all([
      paymentStore.fetchCoachSubscription(),
      paymentStore.fetchCoachTiers(),
      paymentStore.fetchSavedPaymentMethod(),
    ])
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.coach.loadError') })
  } finally {
    loading.value = false
  }
})

function tierColor(tier) {
  return { SCOUT: 'grey', INSTRUCTOR: 'primary', ACADEMY: 'purple' }[tier] || 'grey'
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

// Upgrades only. A paid downgrade (ACADEMY -> INSTRUCTOR) moves the Stripe price at once and the tier at
// period end, and this page can neither show that it is pending nor undo it, so it is not offered here;
// nor is any change once the plan is set to cancel (an upgrade would charge proration on a plan that still
// ends).
function canChoose(tier) {
  if (tier === 'SCOUT' || tier === currentTier.value) return false
  if (subscription.value?.cancelAtPeriodEnd) return false
  return isUpgrade(tier)
}

// Scout is the free tier with no Stripe subscription behind it (a revoked subscription returns the coach
// to Scout), so leaving it means subscribing; changeCoachTier would be refused with no subscription to
// change. A paid coach moves between paid tiers on the subscription they already have.
function chooseTier(tier) {
  if (currentTier.value === 'SCOUT') openSubscribeDialog(tier)
  else handleChangeTier(tier)
}

function openSubscribeDialog(tier) {
  selectedTier.value = tier
  subscribeDialog.value = true
}

async function confirmSubscribe() {
  if (hasCard.value === false) {
    $q.notify({ type: 'warning', message: t('payment.card.addCardPrompt') })
    return
  }
  actionLoading.value = true
  try {
    await paymentStore.subscribeCoach({ tier: selectedTier.value })
    subscribeDialog.value = false
    $q.notify({
      type: 'positive',
      message: t('subscription.coach.subscribeSuccess', { tier: selectedTier.value }),
    })
  } catch (err) {
    $q.notify({
      type: 'negative',
      message: t(subscriptionErrorI18nKey(err, 'subscription.coach.subscribeError')),
    })
  } finally {
    actionLoading.value = false
  }
}

async function handleChangeTier(newTier) {
  actionLoading.value = true
  try {
    await paymentStore.changeCoachTier(newTier)
    $q.notify({
      type: 'positive',
      message: t('subscription.coach.tierChangeSuccess', { tier: newTier }),
    })
  } catch (err) {
    $q.notify({
      type: 'negative',
      message: t(subscriptionErrorI18nKey(err, 'subscription.coach.tierChangeError')),
    })
  } finally {
    actionLoading.value = false
  }
}

async function handleCancel() {
  actionLoading.value = true
  try {
    await paymentStore.cancelCoachSubscription()
    $q.notify({
      type: 'info',
      message: t('subscription.coach.cancelSuccess', {
        date: formatDate(subscription.value?.currentPeriodEnd),
      }),
    })
  } catch {
    $q.notify({ type: 'negative', message: t('subscription.coach.cancelError') })
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
