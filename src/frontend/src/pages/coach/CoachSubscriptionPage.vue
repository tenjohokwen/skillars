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
          <template #action>
            <q-btn
              flat
              color="primary"
              :label="t('subscription.keepPlan')"
              :loading="actionLoading"
              data-testid="subscription-keep-plan-btn"
              @click="handleKeepPlan"
            />
          </template>
        </q-banner>

        <!-- A paid downgrade waits for the period end; until then the coach can take it back. -->
        <q-banner
          v-if="subscription?.pendingTier"
          class="q-mb-md"
          rounded
          style="background: rgba(33, 150, 243, 0.15)"
          data-testid="subscription-change-pending"
        >
          <template #avatar>
            <q-icon name="mdi-calendar-arrow-right" color="info" />
          </template>
          {{
            t('subscription.changePending', {
              tier: subscription.pendingTier,
              date: formatDate(subscription.pendingEffectiveAt),
              current: currentTier,
            })
          }}
          <template #action>
            <q-btn
              flat
              color="primary"
              :label="t('subscription.keepTier', { tier: currentTier })"
              :loading="actionLoading"
              data-testid="subscription-keep-tier-btn"
              @click="handleKeepTier"
            />
          </template>
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
                   checks for a card first); a paid coach upgrades the subscription they have, or moves
                   down to a cheaper paid tier at period end. Moving down to Scout is the Cancel button
                   on the current paid tier. -->
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
                v-else-if="canDowngrade(tier.tier)"
                color="primary"
                outline
                :label="t('subscription.downgrade')"
                class="full-width"
                data-testid="subscription-change-btn"
                @click="openDowngrade(tier.tier)"
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
                @click="cancelDialog = true"
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

    <PlanChangeDialog
      v-model="downgradeDialog"
      testid="subscription-downgrade-dialog"
      :title="t('subscription.downgradeTitle', { tier: selectedTier })"
      :body="
        t('subscription.downgradeBody', {
          tier: selectedTier,
          current: currentTier,
          date: formatDate(subscription?.currentPeriodEnd),
        })
      "
      :confirm-label="t('subscription.downgradeConfirm', { tier: selectedTier })"
      :dismiss-label="t('subscription.keepTier', { tier: currentTier })"
      :loading="actionLoading"
      @confirm="confirmDowngrade"
    />

    <PlanChangeDialog
      v-model="cancelDialog"
      testid="subscription-cancel-dialog"
      :title="t('subscription.cancelTitle', { tier: currentTier })"
      :body="
        t('subscription.cancelBody', {
          tier: currentTier,
          free: 'SCOUT',
          date: formatDate(subscription?.currentPeriodEnd),
        })
      "
      :confirm-label="t('subscription.cancelConfirm')"
      :dismiss-label="t('subscription.keepPlan')"
      confirm-color="negative"
      :loading="actionLoading"
      @confirm="handleCancel"
    />

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
import PlanChangeDialog from 'src/components/payment/PlanChangeDialog.vue'
import { subscriptionErrorI18nKey } from 'src/utils/subscriptionErrors'

const { t, locale } = useI18n()
const $q = useQuasar()
const paymentStore = usePaymentStore()

const loading = ref(false)
const actionLoading = ref(false)
const subscribeDialog = ref(false)
const addCardDialog = ref(false)
const downgradeDialog = ref(false)
const cancelDialog = ref(false)
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

// No change is offered once the plan is set to end (the server refuses it as cancelPending: an upgrade would
// charge proration on a plan that still ends). Keep my plan comes first.
function canChoose(tier) {
  if (tier === 'SCOUT' || tier === currentTier.value) return false
  if (subscription.value?.cancelAtPeriodEnd) return false
  return isUpgrade(tier)
}

// A paid downgrade (ACADEMY -> INSTRUCTOR) moves the Stripe price at once and the tier at period end; the
// pending banner shows it and takes it back. Scout is reached by cancelling, not here.
function canDowngrade(tier) {
  if (tier === 'SCOUT' || currentTier.value === 'SCOUT') return false
  if (subscription.value?.cancelAtPeriodEnd) return false
  if (tier === subscription.value?.pendingTier) return false
  return TIER_ORDER.indexOf(tier) < TIER_ORDER.indexOf(currentTier.value)
}

function openDowngrade(tier) {
  selectedTier.value = tier
  downgradeDialog.value = true
}

async function confirmDowngrade() {
  const tier = selectedTier.value
  const changed = await runAction(
    () => paymentStore.changeCoachTier(tier),
    'subscription.coach.tierChangeError',
  )
  if (!changed) return
  downgradeDialog.value = false
  $q.notify({
    type: 'positive',
    message: t('subscription.downgradeSuccess', {
      tier,
      date: formatDate(subscription.value?.pendingEffectiveAt),
    }),
  })
}

// Re-selecting the current tier takes back a pending downgrade (SubscriptionService.changeCoachTier).
async function handleKeepTier() {
  const tier = currentTier.value
  if (await runAction(() => paymentStore.changeCoachTier(tier), 'subscription.keepError')) {
    $q.notify({ type: 'positive', message: t('subscription.keepTierSuccess', { tier }) })
  }
}

async function handleKeepPlan() {
  if (await runAction(() => paymentStore.keepCoachSubscription(), 'subscription.keepError')) {
    $q.notify({ type: 'positive', message: t('subscription.keepPlanSuccess') })
  }
}

/** Runs one plan action with the shared spinner; a failure is named when the server says why. */
async function runAction(action, fallbackKey) {
  actionLoading.value = true
  try {
    await action()
    return true
  } catch (err) {
    $q.notify({ type: 'negative', message: t(subscriptionErrorI18nKey(err, fallbackKey)) })
    return false
  } finally {
    actionLoading.value = false
  }
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
  if (
    await runAction(() => paymentStore.cancelCoachSubscription(), 'subscription.coach.cancelError')
  ) {
    cancelDialog.value = false
    $q.notify({
      type: 'info',
      message: t('subscription.coach.cancelSuccess', {
        date: formatDate(subscription.value?.currentPeriodEnd),
      }),
    })
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
