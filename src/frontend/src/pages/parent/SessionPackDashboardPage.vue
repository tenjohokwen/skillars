<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">{{ t('booking.packs.dashboardTitle') }}</div>

    <div v-if="bookingStore.packsLoading" class="text-center q-py-lg">
      <q-spinner size="40px" />
    </div>

    <template v-else-if="bookingStore.sessionPacks.length === 0">
      <div class="empty-state text-center q-py-xl">
        <q-icon name="sports_soccer" size="56px" color="grey-5" class="q-mb-md" />
        <div class="text-body1 text-grey q-mb-md">{{ t('booking.packs.emptyState') }}</div>
        <q-btn
          unelevated
          color="primary"
          :label="t('booking.packs.emptyStateCta')"
          :to="{ path: '/marketplace' }"
        />
      </div>
    </template>

    <template v-else>
      <q-card
        v-for="pack in bookingStore.sessionPacks"
        :key="pack.id"
        flat
        bordered
        class="pack-card q-mb-sm"
      >
        <q-card-section>
          <div class="row items-start">
            <div class="col">
              <div class="text-weight-medium">{{ coachNames[pack.coachId] ?? pack.coachId }}</div>
              <div class="text-caption text-grey q-mt-xs">
                {{ t('booking.packs.sessionsBundle', { count: pack.sessionCount }) }}
              </div>
              <div class="text-caption q-mt-xs">
                {{
                  t('booking.packs.creditsRemainingLabel', {
                    remaining: pack.creditsRemaining,
                    total: pack.sessionCount,
                  })
                }}
              </div>
              <div
                v-if="pack.expiresAt && pack.status !== 'REVOKED'"
                class="text-caption q-mt-xs"
                :class="expiryClass(pack)"
              >
                {{ t('booking.packs.expiresLabel', { date: formatDate(pack.expiresAt) }) }}
              </div>
              <div
                v-if="pack.status === 'REVOKED'"
                class="text-caption q-mt-xs text-grey-8"
                data-test="pack-revoked-reason"
              >
                {{ packRevokedReasonText(pack, t) }}
              </div>
              <div
                v-if="pack.status === 'FROZEN'"
                class="text-caption q-mt-xs text-deep-orange"
                data-test="pack-frozen-notice"
              >
                {{ t('booking.packs.frozenNotice') }}
              </div>
              <div
                v-if="pack.status === 'PAUSED'"
                class="text-caption q-mt-xs text-warning"
                data-test="pack-paused-until"
              >
                {{ t('booking.packs.pausedUntilLabel', { date: formatDate(pack.pausedUntil) }) }}
              </div>
              <div
                v-else-if="isUsableOrFrozen(pack) && isPauseScheduled(pack)"
                class="text-caption q-mt-xs text-warning"
                data-test="pack-pause-scheduled"
              >
                {{
                  t('booking.packs.pauseScheduledLabel', {
                    from: formatDate(pack.pausedFrom),
                    until: formatDate(pack.pausedUntil),
                  })
                }}
              </div>
              <q-btn
                v-if="pack.status === 'ACTIVE' && pack.creditsRemaining > 0 && !pack.pausedUntil"
                flat
                dense
                size="sm"
                color="primary"
                class="q-mt-xs q-px-none"
                data-test="pack-pause-cta"
                :label="t('booking.packs.pauseCta')"
                @click="openPauseDialog(pack)"
              />
              <div
                v-else-if="
                  pack.status === 'ACTIVE' && pack.creditsRemaining > 0 && pack.pausedUntil
                "
                class="text-caption q-mt-xs text-grey"
              >
                {{ t('booking.packs.alreadyPausedLabel') }}
              </div>
            </div>
            <q-badge
              :color="packBadgeColor(pack)"
              :label="packBadgeLabel(pack, t)"
              data-test="pack-status-badge"
            />
          </div>
        </q-card-section>
      </q-card>
    </template>

    <!-- Pause Dialog -->
    <q-dialog v-model="pauseDialogOpen">
      <q-card style="min-width: 340px; max-width: 90vw">
        <q-card-section>
          <div class="text-h6">{{ t('booking.packs.pauseDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="pauseForm.startDate"
            type="date"
            :min="minPauseStartDate"
            :hint="t('booking.packs.pauseStartHint')"
            :label="t('booking.packs.pauseStartLabel')"
            outlined
            class="q-mb-md"
          />
          <q-input
            v-model.number="pauseForm.durationDays"
            type="number"
            :label="t('booking.packs.pauseDurationLabel')"
            :hint="t('booking.packs.pauseDurationHint')"
            :min="1"
            :max="90"
            outlined
          />
        </q-card-section>
        <q-card-section v-if="bookingStore.packPauseConflicts.length > 0">
          <div class="text-subtitle2 text-negative q-mb-sm">
            {{ t('booking.packs.pauseConflictsTitle') }}
          </div>
          <q-list dense bordered separator>
            <q-item v-for="b in bookingStore.packPauseConflicts" :key="b.id">
              <q-item-section>
                <q-item-label>{{
                  formatDateTime(b.requestedStartTime, b.canonicalTimezone)
                }}</q-item-label>
                <q-item-label caption>{{ b.status }}</q-item-label>
                <q-item-label v-if="b.cancellable === false" caption class="text-negative">
                  {{ t('booking.packs.pauseConflictInsideNotice') }}
                </q-item-label>
              </q-item-section>
            </q-item>
          </q-list>
          <div v-if="hasBlockingConflict" class="text-caption text-negative q-mt-sm">
            {{ t('booking.packs.pauseBlockedInsideNotice') }}
          </div>
          <div v-else class="text-caption text-negative q-mt-sm">
            {{ t('booking.packs.pauseConflictsWarning') }}
          </div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn
            flat
            :label="t('common.cancel')"
            v-close-popup
            @click="bookingStore.packPauseConflicts = []"
          />
          <q-btn
            unelevated
            color="warning"
            :label="
              bookingStore.packPauseConflicts.length > 0
                ? t('booking.packs.pauseConfirmWithCancellations')
                : t('booking.packs.pauseConfirm')
            "
            :loading="bookingStore.packPauseLoading"
            :disable="hasBlockingConflict"
            @click="submitPause"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'
import { getCoachProfile } from 'src/api/marketplace.api'
import {
  isPauseScheduled,
  packBadgeColor,
  packBadgeLabel,
  packRevokedReasonText,
} from 'src/utils/packStatus'

const route = useRoute()
const { t, locale } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()

const playerId = route.params.playerId
const pauseDialogOpen = ref(false)
const activePack = ref(null)
const pauseForm = ref({ startDate: '', durationDays: 30 })
// New payment-path packs only carry coachId, not a display name (unlike the legacy list
// endpoint) — this page can show packs from multiple coaches, so resolve names client-side.
const coachNames = ref({})

async function resolveCoachNames(packs) {
  // AC 9: a key-presence check (not truthiness) so a negative-cached `null` id is excluded —
  // `!coachNames.value[id]` would keep refetching a failed lookup forever since `!null === true`.
  const unresolvedIds = [...new Set(packs.map((p) => p.coachId))].filter(
    (id) => !(id in coachNames.value),
  )
  const profiles = await Promise.all(
    unresolvedIds.map((id) => getCoachProfile(id).catch(() => null)),
  )
  const resolved = { ...coachNames.value }
  unresolvedIds.forEach((id, i) => {
    // Negative-cache a failed/missing lookup as null so it isn't retried on every watch firing.
    resolved[id] = profiles[i] ? profiles[i].displayName : null
  })
  coachNames.value = resolved
}

watch(
  () => bookingStore.sessionPacks,
  (packs) => {
    if (packs.length > 0) resolveCoachNames(packs)
  },
)

function formatDate(isoString) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleDateString(locale.value, { dateStyle: 'medium' })
}

function formatDateTime(isoString, timezone) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleString(locale.value, {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

// fix/pack-status-badges: the badge and notes follow the server-computed status (PAUSED only inside
// [pausedFrom, pausedUntil); FROZEN while a card dispute is open; REVOKED once withdrawn). A pause scheduled
// for a later date leaves the pack ACTIVE — or FROZEN — with a "pause scheduled" note.
function isUsableOrFrozen(pack) {
  return pack.status === 'ACTIVE' || pack.status === 'FROZEN'
}

// The pause must start in the future (the server refuses today or earlier): tomorrow at the earliest.
const minPauseStartDate = computed(() => {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
})

// A paid session within 24h of its start cannot be cancelled by a pause: the pause is refused.
const hasBlockingConflict = computed(() =>
  bookingStore.packPauseConflicts.some((b) => b.cancellable === false),
)

function expiryClass(pack) {
  const daysUntilExpiry = (new Date(pack.expiresAt) - new Date()) / (1000 * 60 * 60 * 24)
  if (daysUntilExpiry < 0) return 'text-negative'
  if (daysUntilExpiry <= 7) return 'text-negative'
  if (daysUntilExpiry <= 30) return 'text-warning'
  return 'text-grey'
}

function openPauseDialog(pack) {
  activePack.value = pack
  bookingStore.packPauseConflicts = []
  pauseDialogOpen.value = true
}

async function submitPause() {
  const packId = activePack.value?.id
  if (!packId || !pauseForm.value.startDate) return
  const pauseStartDate = new Date(pauseForm.value.startDate).toISOString()
  const pauseDurationDays = pauseForm.value.durationDays
  const conflicts = bookingStore.packPauseConflicts

  if (conflicts.length > 0) {
    try {
      const result = await bookingStore.confirmPausePack(
        playerId,
        packId,
        pauseStartDate,
        pauseDurationDays,
        conflicts.map((b) => b.id),
      )
      if (result.pauseApplied) {
        pauseDialogOpen.value = false
        $q.notify({ message: t('booking.packs.pauseSuccess'), type: 'positive' })
      }
    } catch (err) {
      notifyPauseError(err)
    }
  } else {
    try {
      const result = await bookingStore.initiatePausePack(packId, pauseStartDate, pauseDurationDays)
      if (result.pauseApplied) {
        pauseDialogOpen.value = false
        $q.notify({ message: t('booking.packs.pauseSuccess'), type: 'positive' })
      }
    } catch (err) {
      notifyPauseError(err)
    }
  }
}

function notifyPauseError(err) {
  const errorKey = err?.response?.data?.errorMsg?.errorKey
  if (errorKey === 'booking.pauseStartInPast') {
    $q.notify({ message: t('booking.packs.pauseStartInPast'), type: 'negative' })
  } else {
    $q.notify({ message: t('error.generic'), type: 'negative' })
  }
}

onMounted(async () => {
  if (playerId) {
    await bookingStore.loadPlayerPacks(playerId)
  }
})
</script>

<style lang="scss" scoped>
.pack-card {
  border-color: var(--border-subtle);
}

.empty-state {
  max-width: 400px;
  margin: 0 auto;
}
</style>
