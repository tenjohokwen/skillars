<template>
  <q-page class="q-pa-md">
    <div class="session-pack-tiers-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('payment.myTiers.pageTitle') }}</div>

      <div v-if="paymentStore.loading.sessionPackTiers" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <!-- skillars-deferred-151 review Patch: a swallowed tier-fetch failure must not silently
           read as "no active offer" — that would suppress the replace-confirmation (v-if="activeTier")
           on a coach who actually has a live offer, letting them replace it with zero warning. -->
      <div v-else-if="paymentStore.error.sessionPackTiers" class="q-py-xl q-gutter-sm">
        <div class="text-body2 text-negative">{{ t('payment.myTiers.fetchError') }}</div>
        <q-btn
          flat
          dense
          no-caps
          :label="t('common.retry')"
          @click="paymentStore.fetchSessionPackTiers"
        />
      </div>

      <template v-else>
        <!-- Current offer -->
        <div class="q-mb-lg">
          <div class="text-subtitle1 q-mb-sm">{{ t('payment.myTiers.currentOffer') }}</div>
          <div v-if="activeTier" class="glass-card q-pa-md tier-card">
            <div class="text-body1 text-weight-bold">{{ activeTier.label }}</div>
            <div class="text-body2" style="color: var(--text-secondary)">
              {{
                t('payment.sessionPack.tierLabel', {
                  label: activeTier.label,
                  count: activeTier.sessionCount,
                  price: activeTier.totalPrice,
                })
              }}
            </div>
          </div>
          <div v-else class="text-body2" style="color: var(--text-secondary)">
            {{ t('payment.myTiers.noActiveOffer') }}
          </div>
          <div class="q-mt-md row q-gutter-sm items-center">
            <q-btn
              no-caps
              color="primary"
              :label="
                activeTier ? t('payment.myTiers.replaceOffer') : t('payment.myTiers.createNew')
              "
              @click="openCreateForm"
            />
            <q-btn
              v-if="activeTier"
              no-caps
              flat
              color="negative"
              :label="t('payment.myTiers.withdrawOffer')"
              @click="openWithdrawConfirm"
            />
          </div>
        </div>

        <!-- History -->
        <div v-if="history.length" class="q-mb-lg">
          <div class="text-subtitle1 q-mb-sm">{{ t('payment.myTiers.history') }}</div>
          <q-list separator>
            <q-item v-for="tier in history" :key="tier.packTierId" class="history-item">
              <q-item-section>
                <q-item-label>{{ tier.label }}</q-item-label>
                <q-item-label caption>
                  {{
                    t('payment.sessionPack.tierLabel', {
                      label: tier.label,
                      count: tier.sessionCount,
                      price: tier.totalPrice,
                    })
                  }}
                </q-item-label>
              </q-item-section>
            </q-item>
          </q-list>
        </div>

        <!-- feat/coach-workflow-gaps A20: sold packs, with Extend where the backend allows it
             (replaces a widget that asked the coach for a raw purchase UUID nothing ever showed). -->
        <div class="glass-card q-pa-md">
          <div class="text-subtitle1 q-mb-xs">{{ t('payment.myTiers.soldPacksTitle') }}</div>
          <div class="text-caption q-mb-sm" style="color: var(--text-secondary)">
            {{ t('payment.myTiers.extendHelp') }}
          </div>
          <div v-if="paymentStore.loading.soldPacks" class="flex flex-center q-py-md">
            <q-spinner size="32px" />
          </div>
          <div v-else-if="paymentStore.error.soldPacks" class="q-gutter-sm">
            <span class="text-body2 text-negative">{{ t('payment.myTiers.soldPacksError') }}</span>
            <q-btn flat dense no-caps :label="t('common.retry')" @click="loadSoldPacks(soldPage)" />
          </div>
          <div
            v-else-if="!paymentStore.soldPacks.length"
            class="text-body2"
            style="color: var(--text-secondary)"
          >
            {{ t('payment.myTiers.soldPacksEmpty') }}
          </div>
          <template v-else>
            <q-list separator>
              <q-item
                v-for="pack in paymentStore.soldPacks"
                :key="pack.purchaseId"
                data-test="sold-pack"
              >
                <q-item-section>
                  <q-item-label class="text-weight-medium">
                    {{ pack.playerName || t('payment.myTiers.unknownPlayer') }}
                    <q-badge
                      v-if="pack.expiringSoon"
                      color="warning"
                      text-color="dark"
                      class="q-ml-xs"
                      :label="t('payment.myTiers.expiringSoon')"
                    />
                  </q-item-label>
                  <q-item-label v-if="pack.buyerName && pack.buyerName !== pack.playerName" caption>
                    {{ t('payment.myTiers.boughtBy', { name: pack.buyerName }) }}
                  </q-item-label>
                  <q-item-label caption>
                    {{ pack.tierLabel || '—' }} ·
                    {{
                      t('payment.myTiers.sessionsRemaining', {
                        remaining: pack.remainingSessions,
                        total: pack.sessionCount,
                      })
                    }}
                  </q-item-label>
                  <q-item-label caption>
                    {{ t('payment.myTiers.expiresOn', { date: formatDate(pack.expiresAt) }) }}
                    <template v-if="pack.extendedAt">
                      · {{ t('payment.myTiers.alreadyExtended') }}
                    </template>
                  </q-item-label>
                  <q-item-label
                    v-if="statusNote(pack)"
                    caption
                    class="text-deep-orange"
                    data-test="sold-pack-status-note"
                  >
                    {{ statusNote(pack) }}
                  </q-item-label>
                </q-item-section>
                <q-item-section side class="items-end q-gutter-xs">
                  <q-badge
                    :color="statusColor(pack.status)"
                    :label="statusLabel(pack.status)"
                    data-test="sold-pack-status"
                  />
                  <q-btn
                    v-if="pack.extendable && pack.status !== 'REVOKED'"
                    no-caps
                    dense
                    color="primary"
                    :label="t('payment.myTiers.extendCta')"
                    :loading="extendingId === pack.purchaseId"
                    :disable="extendingId !== null"
                    data-test="extend-pack"
                    @click="submitExtend(pack)"
                  />
                </q-item-section>
              </q-item>
            </q-list>
            <div v-if="extendError" class="text-negative text-caption q-mt-sm">
              {{ extendError }}
            </div>
            <div v-if="soldTotalPages > 1" class="flex flex-center q-mt-sm">
              <q-pagination
                :model-value="soldPage + 1"
                :max="soldTotalPages"
                direction-links
                @update:model-value="(p) => loadSoldPacks(p - 1)"
              />
            </div>
          </template>
        </div>
      </template>
    </div>

    <!-- Replace/create confirmation dialog -->
    <q-dialog v-model="createDialogOpen">
      <q-card style="min-width: 320px; max-width: 480px; width: 100%">
        <q-card-section>
          <div class="text-h6">
            {{
              activeTier ? t('payment.myTiers.replaceConfirmTitle') : t('payment.myTiers.createNew')
            }}
          </div>
        </q-card-section>
        <q-card-section v-if="activeTier" class="text-body2">
          {{ t('payment.myTiers.replaceConfirmBody') }}
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="createLabel"
            dense
            filled
            :label="t('payment.myTiers.labelField')"
            maxlength="200"
            class="q-mb-sm"
          />
          <q-input
            v-model.number="createSessionCount"
            type="number"
            dense
            filled
            :label="t('payment.myTiers.sessionCountField')"
            min="1"
            step="1"
            class="q-mb-sm"
          />
          <q-input
            v-model.number="createTotalPrice"
            type="number"
            dense
            filled
            :label="t('payment.myTiers.totalPriceField')"
            min="0.01"
            step="0.01"
          />
          <!-- skillars-deferred-151 review Patch: canSubmitCreate's own price-floor check already
               disables the submit button whenever this would show, so payment.myTiers.priceFloorError
               could never render as submitCreate's own early-return guard — show it live instead, as
               the coach types, so the message is actually reachable. -->
          <div v-if="sessionCountInvalid" class="text-negative text-caption q-mt-sm">
            {{ t('payment.myTiers.sessionCountWholeError') }}
          </div>
          <div v-else-if="priceFloorInvalid" class="text-negative text-caption q-mt-sm">
            {{ t('payment.myTiers.priceFloorError') }}
          </div>
          <div v-if="createError" class="text-negative text-caption q-mt-sm">
            {{ createError }}
          </div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            :label="activeTier ? t('payment.myTiers.replaceOffer') : t('payment.myTiers.createNew')"
            :disable="!canSubmitCreate"
            :loading="paymentStore.loading.createSessionPackTier"
            @click="submitCreate"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Withdraw confirmation dialog -->
    <q-dialog v-model="withdrawDialogOpen">
      <q-card style="min-width: 320px; max-width: 480px; width: 100%">
        <q-card-section>
          <div class="text-h6">{{ t('payment.myTiers.withdrawConfirmTitle') }}</div>
        </q-card-section>
        <q-card-section class="text-body2">
          {{ t('payment.myTiers.withdrawConfirmBody') }}
        </q-card-section>
        <div v-if="withdrawError" class="text-negative text-caption q-px-md q-pb-sm">
          {{ withdrawError }}
        </div>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="negative"
            :label="t('payment.myTiers.withdrawOffer')"
            :loading="paymentStore.loading.deactivateSessionPackTier"
            @click="submitWithdraw"
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

const { t, locale } = useI18n()
const $q = useQuasar()
const paymentStore = usePaymentStore()

const createDialogOpen = ref(false)
const createLabel = ref('')
const createSessionCount = ref(null)
const createTotalPrice = ref(null)
const createError = ref('')

const extendError = ref('')
const extendingId = ref(null)
const soldPage = ref(0)
const soldTotalPages = computed(() => paymentStore.soldPacksPage?.totalPages ?? 1)

const withdrawDialogOpen = ref(false)
const withdrawError = ref('')

const activeTier = computed(() => paymentStore.sessionPackTiers.find((t) => t.isActive))
const history = computed(() =>
  paymentStore.sessionPackTiers
    .filter((t) => !t.isActive)
    .slice()
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)),
)

// skillars-deferred-151 review Patch: the q-input's own min="1" has no step, so a fractional
// sessionCount (e.g. 2.5) reached the backend, where Jackson's ACCEPT_FLOAT_AS_INT silently
// truncated it rather than rejecting it — guard it client-side instead.
const sessionCountInvalid = computed(
  () => !!createSessionCount.value && !Number.isInteger(createSessionCount.value),
)

// skillars-deferred-151 AC2: pricePerSession is derived server-side as totalPrice / sessionCount,
// HALF_UP to 2dp. A combination rounding to 0.00 trips chk_spt_price_per_session_positive as a raw
// 500, not a validation 400 — guard it client-side before the request is even sent.
const priceFloorInvalid = computed(() => {
  if (!createSessionCount.value || !createTotalPrice.value || sessionCountInvalid.value) {
    return false
  }
  return createTotalPrice.value / createSessionCount.value < 0.005
})

const canSubmitCreate = computed(() => {
  if (!createLabel.value.trim() || !createSessionCount.value || !createTotalPrice.value) {
    return false
  }
  return !sessionCountInvalid.value && !priceFloorInvalid.value
})

onMounted(() => {
  paymentStore.fetchSessionPackTiers()
  loadSoldPacks(0)
})

function loadSoldPacks(page) {
  soldPage.value = page
  extendError.value = ''
  return paymentStore.fetchSoldPacks(page)
}

function formatDate(iso) {
  return iso ? new Date(iso).toLocaleDateString(locale.value) : '—'
}

function statusLabel(status) {
  switch (status) {
    case 'ACTIVE':
      return t('payment.myTiers.packStatus.ACTIVE')
    case 'PAUSED':
      return t('payment.myTiers.packStatus.PAUSED')
    case 'EXPIRED':
      return t('payment.myTiers.packStatus.EXPIRED')
    case 'EXHAUSTED':
      return t('payment.myTiers.packStatus.EXHAUSTED')
    case 'FROZEN':
      return t('payment.myTiers.packStatus.FROZEN')
    case 'REVOKED':
      return t('payment.myTiers.packStatus.REVOKED')
    default:
      return status
  }
}

// fix/pack-status-badges: why a sold pack is frozen (open card dispute) or withdrawn (revokedReason).
function statusNote(pack) {
  if (pack.status === 'FROZEN') return t('payment.myTiers.packStatusNote.frozenNote')
  if (pack.status !== 'REVOKED') return null
  switch (pack.revokedReason) {
    case 'CHARGEBACK_LOST':
      return t('payment.myTiers.packStatusNote.revokedChargebackLost')
    case 'EXTERNAL_REFUND':
      return t('payment.myTiers.packStatusNote.revokedExternalRefund')
    case 'COACH_SUSPENDED':
      return t('payment.myTiers.packStatusNote.revokedCoachSuspended')
    default:
      return t('payment.myTiers.packStatusNote.revokedOther')
  }
}

function statusColor(status) {
  return (
    {
      ACTIVE: 'positive',
      PAUSED: 'info',
      FROZEN: 'deep-orange',
      REVOKED: 'grey-8',
      EXPIRED: 'grey-7',
      EXHAUSTED: 'grey-6',
    }[status] ?? 'grey'
  )
}

function openCreateForm() {
  createLabel.value = ''
  createSessionCount.value = null
  createTotalPrice.value = null
  createError.value = ''
  createDialogOpen.value = true
}

async function submitCreate() {
  createError.value = ''
  try {
    await paymentStore.createSessionPackTier({
      label: createLabel.value.trim(),
      sessionCount: createSessionCount.value,
      totalPrice: createTotalPrice.value,
    })
    createDialogOpen.value = false
  } catch {
    createError.value = t('payment.myTiers.createFailed')
  }
}

function openWithdrawConfirm() {
  withdrawError.value = ''
  withdrawDialogOpen.value = true
}

async function submitWithdraw() {
  withdrawError.value = ''
  try {
    await paymentStore.deactivateSessionPackTier(activeTier.value.packTierId)
    withdrawDialogOpen.value = false
  } catch {
    withdrawError.value = t('payment.myTiers.withdrawFailed')
  }
}

async function submitExtend(pack) {
  extendError.value = ''
  extendingId.value = pack.purchaseId
  try {
    await paymentStore.extendSessionPack(pack.purchaseId)
    $q.notify({ type: 'positive', message: t('payment.myTiers.extendSuccess') })
    // Re-read the page: the row's expiry, extendedAt and Extend eligibility all changed server-side.
    await loadSoldPacks(soldPage.value)
  } catch {
    // Both real backend failure reasons (already-extended 409, outside the 14-day window)
    // collapse to the same unbranchable errorKey — a single generic message is correct here,
    // not an attempt to distinguish them (see Finding 2 / AC2).
    extendError.value = t('payment.myTiers.extendFailed')
  } finally {
    extendingId.value = null
  }
}
</script>

<style lang="scss" scoped>
.session-pack-tiers-page {
  max-width: 700px;
  margin: 0 auto;
}

.tier-card {
  border: 1px solid var(--border-subtle);
}

.history-item {
  opacity: 0.75;
}
</style>
