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
            </div>
            <q-badge
              :color="pack.status === 'ACTIVE' ? 'positive' : 'grey'"
              :label="pack.status === 'ACTIVE' ? t('booking.packs.activeStatus') : t('booking.packs.exhaustedLabel')"
            />
          </div>
        </q-card-section>
      </q-card>
    </template>
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useBookingStore } from 'src/stores/booking.store'

const route = useRoute()
const { t } = useI18n()
const bookingStore = useBookingStore()

const playerId = route.params.playerId

function formatDate(isoString) {
  if (!isoString) return ''
  return new Date(isoString).toLocaleDateString(undefined, { dateStyle: 'medium' })
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
