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
          <span class="text-caption">{{ formatBytes(storageUsedBytes) }} / {{ formatBytes(storageLimitBytes) }}</span>
        </div>
        <q-linear-progress
          :value="storagePercent / 100"
          :color="barColor(storagePercent)"
          rounded
          size="8px"
          aria-label="Storage usage"
        />
        <div v-if="storagePercent >= 95 && showUpgradePrompt" class="text-caption text-negative q-mt-xs">
          <!-- TODO Story 7.x: replace with real upgrade route -->
          <router-link to="/upgrade" class="text-negative">{{ t('video.quota.upgradePrompt') }}</router-link>
        </div>
      </div>

      <!-- Bandwidth row -->
      <div class="quota-row">
        <div class="row items-center justify-between q-mb-xs">
          <span class="text-caption text-secondary">{{ t('video.quota.bandwidth') }}</span>
          <span class="text-caption">{{ formatBytes(bandwidthUsedBytes) }} / {{ formatBytes(bandwidthLimitBytes) }}</span>
        </div>
        <q-linear-progress
          :value="bandwidthPercent / 100"
          :color="barColor(bandwidthPercent)"
          rounded
          size="8px"
          aria-label="Bandwidth usage"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

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
  props.storageLimitBytes > 0 ? Math.min(100, (props.storageUsedBytes / props.storageLimitBytes) * 100) : 0,
)

const bandwidthPercent = computed(() =>
  props.bandwidthLimitBytes > 0 ? Math.min(100, (props.bandwidthUsedBytes / props.bandwidthLimitBytes) * 100) : 0,
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
