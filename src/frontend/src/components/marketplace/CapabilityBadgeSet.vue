<template>
  <div v-if="badges.length > 0" class="capability-badge-set">
    <q-chip
      v-for="badge in visibleBadges"
      :key="badge.key"
      dense
      :icon="badge.icon"
      :label="t(badge.labelKey)"
      class="capability-badge"
      size="sm"
    >
      <q-tooltip>{{ t(badge.tooltipKey) }}</q-tooltip>
    </q-chip>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  badges: { type: Array, default: () => [] },
})

const { t } = useI18n()

const BADGE_DEFS = {
  VIDEO_FEEDBACK: {
    icon: 'videocam',
    labelKey: 'marketplace.capabilityBadge.VIDEO_FEEDBACK',
    tooltipKey: 'marketplace.capabilityBadgeTooltip.VIDEO_FEEDBACK',
  },
  PERFORMANCE_REPORTS: {
    icon: 'assessment',
    labelKey: 'marketplace.capabilityBadge.PERFORMANCE_REPORTS',
    tooltipKey: 'marketplace.capabilityBadgeTooltip.PERFORMANCE_REPORTS',
  },
  HOMEWORK: {
    icon: 'assignment',
    labelKey: 'marketplace.capabilityBadge.HOMEWORK',
    tooltipKey: 'marketplace.capabilityBadgeTooltip.HOMEWORK',
  },
  SKILLS_RADAR: {
    icon: 'radar',
    labelKey: 'marketplace.capabilityBadge.SKILLS_RADAR',
    tooltipKey: 'marketplace.capabilityBadgeTooltip.SKILLS_RADAR',
  },
  VERIFIED_IDENTITY: {
    icon: 'fingerprint',
    labelKey: 'marketplace.capabilityBadge.VERIFIED_IDENTITY',
    tooltipKey: 'marketplace.capabilityBadgeTooltip.VERIFIED_IDENTITY',
  },
}

const visibleBadges = computed(() =>
  props.badges
    .filter((name) => BADGE_DEFS[name])
    .map((name) => ({ key: name, ...BADGE_DEFS[name] })),
)
</script>

<style lang="scss" scoped>
.capability-badge-set {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.capability-badge {
  min-height: 44px;
  color: var(--text-primary);
  background: var(--surface-raised);
  border: 1px solid var(--border-subtle);
}
</style>
