<template>
  <div class="quota-usage-bar">
    <template v-if="loading">
      <q-skeleton type="text" class="q-mb-sm" />
      <q-skeleton type="QRange" height="8px" class="q-mb-md" />
      <q-skeleton type="text" class="q-mb-sm" />
      <q-skeleton type="QRange" height="8px" />
    </template>
    <template v-else>
      <!-- Storage row -->
      <div class="quota-row q-mb-md">
        <div class="row items-center justify-between q-mb-xs">
          <span class="text-caption text-secondary">{{ t('video.quota.storage') }}</span>
          <span class="text-caption"
            >{{ formatBytes(storageUsedBytes) }} / {{ formatBytes(storageLimitBytes) }}</span
          >
        </div>
        <q-linear-progress
          :value="storagePercent / 100"
          :color="barColor(storagePercent)"
          rounded
          size="8px"
          :aria-label="$t('video.quota.storage')"
        />
        <div
          v-if="needsUpgradePrompt && upgradeRoute"
          class="text-caption text-negative q-mt-xs quota-upgrade-prompt"
        >
          <router-link :to="upgradeRoute" class="text-negative">{{
            t('video.quota.upgradePrompt')
          }}</router-link>
        </div>
      </div>

      <!-- Bandwidth row -->
      <div class="quota-row">
        <div class="row items-center justify-between q-mb-xs">
          <span class="text-caption text-secondary">{{ t('video.quota.bandwidth') }}</span>
          <span class="text-caption"
            >{{ formatBytes(bandwidthUsedBytes) }} / {{ formatBytes(bandwidthLimitBytes) }}</span
          >
        </div>
        <q-linear-progress
          :value="bandwidthPercent / 100"
          :color="barColor(bandwidthPercent)"
          rounded
          size="8px"
          :aria-label="$t('video.quota.bandwidth')"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'

const props = defineProps({
  storageUsedBytes: { type: Number, default: 0 },
  storageLimitBytes: { type: Number, default: 0 },
  bandwidthUsedBytes: { type: Number, default: 0 },
  bandwidthLimitBytes: { type: Number, default: 0 },
  loading: { type: Boolean, default: false },
  // platform.ui.quota.upgradePromptEnabled — allows ops to disable the upgrade prompt
  // independently of the quota display (e.g. during a payment system outage).
  // Pass false from the parent when the runtime config key is false.
  showUpgradePrompt: { type: Boolean, default: true },
})

const { t } = useI18n()

const storagePercent = computed(() =>
  props.storageLimitBytes > 0
    ? Math.min(100, (props.storageUsedBytes / props.storageLimitBytes) * 100)
    : 0,
)

const bandwidthPercent = computed(() =>
  props.bandwidthLimitBytes > 0
    ? Math.min(100, (props.bandwidthUsedBytes / props.bandwidthLimitBytes) * 100)
    : 0,
)

const needsUpgradePrompt = computed(() => storagePercent.value >= 95 && props.showUpgradePrompt)

// The upgrade target is the caller's own subscription page: a coach's coach-subscription, or a
// self-registered player's player-subscription (keyed by their own PlayerProfile id). Any other
// role has no subscription of its own here, so no link is shown (there is no generic /upgrade route).
const authStore = useAuthStore()
const playerStore = usePlayerStore()
const upgradeRoute = computed(() => {
  if (authStore.isCoach) return { name: 'coach-subscription' }
  if (authStore.isPlayer && playerStore.selfPlayerId != null) {
    return { name: 'player-subscription', params: { playerId: String(playerStore.selfPlayerId) } }
  }
  return null
})
watch(
  () => needsUpgradePrompt.value && authStore.isPlayer,
  (needed) => {
    // Resolution failure just leaves the prompt hidden — nothing to surface on a quota bar.
    if (needed) playerStore.fetchSelfPlayerId().catch(() => {})
  },
  { immediate: true },
)

function barColor(percent) {
  if (percent >= 95) return 'negative'
  if (percent >= 80) return 'warning'
  return 'primary'
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(1)} ${units[i]}`
}
</script>

<style scoped>
.quota-usage-bar {
  padding: var(--space-sm, 0.5rem) 0;
}
</style>
