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
              <div class="text-weight-medium">{{ pack.coachDisplayName }}</div>
              <div class="text-caption text-grey q-mt-xs">
                {{ t('booking.packs.sessionsBundle', { count: pack.sessionCount }) }}
                · {{ formatDate(pack.purchasedAt) }}
              </div>
              <div class="text-caption q-mt-xs">
                {{ t('booking.packs.creditsRemainingLabel', { remaining: pack.creditsRemaining, total: pack.sessionCount }) }}
              </div>
              <div v-if="pack.expiresAt" class="text-caption q-mt-xs" :class="expiryClass(pack)">
                {{ t('booking.packs.expiresLabel', { date: formatDate(pack.expiresAt) }) }}
              </div>
              <div v-if="isPaused(pack)" class="text-caption q-mt-xs text-warning">
                {{ t('booking.packs.pausedUntilLabel', { date: formatDate(pack.pausedUntil) }) }}
              </div>
              <q-btn
                v-if="pack.status === 'ACTIVE' && pack.creditsRemaining > 0 && !pack.pausedUntil"
                flat dense size="sm" color="primary" class="q-mt-xs q-px-none"
                :label="t('booking.packs.pauseCta')"
                @click="openPauseDialog(pack)"
              />
              <div
                v-else-if="pack.status === 'ACTIVE' && pack.creditsRemaining > 0 && pack.pausedUntil"
                class="text-caption q-mt-xs text-grey"
              >
                {{ t('booking.packs.alreadyPausedLabel') }}
              </div>
            </div>
            <q-badge
              :color="packBadgeColor(pack)"
              :label="packBadgeLabel(pack)"
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
          <div class="text-subtitle2 text-negative q-mb-sm">{{ t('booking.packs.pauseConflictsTitle') }}</div>
          <q-list dense bordered separator>
            <q-item v-for="b in bookingStore.packPauseConflicts" :key="b.id">
              <q-item-section>
                <q-item-label>{{ formatDateTime(b.requestedStartTime, b.canonicalTimezone) }}</q-item-label>
                <q-item-label caption>{{ b.status }}</q-item-label>
              </q-item-section>
            </q-item>
          </q-list>
          <div class="text-caption text-negative q-mt-sm">
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
            :label="bookingStore.packPauseConflicts.length > 0
              ? t('booking.packs.pauseConfirmWithCancellations')
              : t('booking.packs.pauseConfirm')"
            :loading="bookingStore.packPauseLoading"
            @click="submitPause"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useBookingStore } from 'src/stores/booking.store'

const route = useRoute()
const { t } = useI18n()
const $q = useQuasar()
const bookingStore = useBookingStore()

const playerId = route.params.playerId
const pauseDialogOpen = ref(false)
const activePack = ref(null)
const pauseForm = ref({ startDate: '', durationDays: 30 })

function formatDate(isoString) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleDateString(undefined, { dateStyle: 'medium' })
}

function formatDateTime(isoString, timezone) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleString(undefined, {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

function isPaused(pack) {
  return pack.pausedUntil && new Date(pack.pausedUntil) > new Date()
}

function expiryClass(pack) {
  const daysUntilExpiry = (new Date(pack.expiresAt) - new Date()) / (1000 * 60 * 60 * 24)
  if (daysUntilExpiry < 0) return 'text-negative'
  if (daysUntilExpiry <= 7) return 'text-negative'
  if (daysUntilExpiry <= 30) return 'text-warning'
  return 'text-grey'
}

function packBadgeColor(pack) {
  if (pack.status === 'ACTIVE' && isPaused(pack)) return 'warning'
  if (pack.status === 'EXPIRED') return 'negative'
  if (pack.status === 'EXHAUSTED') return 'grey'
  return 'positive'
}

function packBadgeLabel(pack) {
  if (pack.status === 'ACTIVE' && isPaused(pack)) return t('booking.packs.pausedStatus')
  if (pack.status === 'EXPIRED') return t('booking.packs.expiredLabel')
  if (pack.status === 'EXHAUSTED') return t('booking.packs.exhaustedLabel')
  return t('booking.packs.activeStatus')
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
        playerId, packId, pauseStartDate, pauseDurationDays,
        conflicts.map((b) => b.id),
      )
      if (result.pauseApplied) {
        pauseDialogOpen.value = false
        $q.notify({ message: t('booking.packs.pauseSuccess'), type: 'positive' })
      }
    } catch {
      $q.notify({ message: t('error.generic'), type: 'negative' })
    }
  } else {
    try {
      const result = await bookingStore.initiatePausePack(
        playerId, packId, pauseStartDate, pauseDurationDays,
      )
      if (result.pauseApplied) {
        pauseDialogOpen.value = false
        $q.notify({ message: t('booking.packs.pauseSuccess'), type: 'positive' })
      }
    } catch {
      $q.notify({ message: t('error.generic'), type: 'negative' })
    }
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
