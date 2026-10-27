<template>
  <div>
    <div v-if="error" class="text-negative text-body2" data-testid="skill-trends-error">
      {{ t('development.skillTrends.loadError') }}
    </div>
    <div
      v-else-if="trends.length === 0"
      class="text-body2"
      style="color: var(--text-secondary)"
      data-testid="skill-trends-empty"
    >
      {{ t('development.skillTrends.emptyState') }}
    </div>
    <q-list v-else dense data-testid="skill-trends-list">
      <q-item v-for="trend in trends" :key="trend.skillCode" data-testid="skill-trend-row">
        <q-item-section avatar>
          <q-icon :name="directionIcon(trend.direction)" :color="directionColor(trend.direction)" />
        </q-item-section>
        <q-item-section>
          <q-item-label>{{ skillName(trend.skillCode) }}</q-item-label>
          <q-item-label caption>
            {{ directionLabel(trend.direction) }}
            <!-- slopePerWeek is a 0 placeholder for INSUFFICIENT_DATA (SkillTrend's own Javadoc),
                 not a measured flat trend, so no slope is shown for it. -->
            <template v-if="hasSlope(trend.direction)">
              ·
              {{
                t('development.skillTrends.slopeCaption', {
                  slope: formatSlope(trend.slopePerWeek),
                  weeks: trend.weeksObserved,
                })
              }}
            </template>
          </q-item-label>
        </q-item-section>
      </q-item>
    </q-list>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'

const props = defineProps({
  trends: { type: Array, default: () => [] },
  skillDefinitions: { type: Array, default: () => [] },
  // The skill-trends fetch failed: show that, not the "no data yet" empty state.
  error: { type: Boolean, default: false },
})

const { t } = useI18n()

const DIRECTION_ICONS = {
  IMPROVING: 'trending_up',
  FLAT: 'trending_flat',
  DECLINING: 'trending_down',
  INSUFFICIENT_DATA: 'help_outline',
}

const DIRECTION_COLORS = {
  IMPROVING: 'positive',
  FLAT: 'grey-6',
  DECLINING: 'negative',
  INSUFFICIENT_DATA: 'grey-6',
}

function skillName(code) {
  return props.skillDefinitions.find((s) => s.code === code)?.displayName ?? code
}

// Static keys (not a template-literal key), so an unknown future direction falls back to the
// INSUFFICIENT_DATA label instead of rendering a raw i18n key.
const DIRECTION_LABEL_KEYS = {
  IMPROVING: 'development.skillTrends.direction.IMPROVING',
  FLAT: 'development.skillTrends.direction.FLAT',
  DECLINING: 'development.skillTrends.direction.DECLINING',
  INSUFFICIENT_DATA: 'development.skillTrends.direction.INSUFFICIENT_DATA',
}

function directionLabel(direction) {
  return t(DIRECTION_LABEL_KEYS[direction] ?? DIRECTION_LABEL_KEYS.INSUFFICIENT_DATA)
}

function hasSlope(direction) {
  return direction in DIRECTION_LABEL_KEYS && direction !== 'INSUFFICIENT_DATA'
}

function directionIcon(direction) {
  return DIRECTION_ICONS[direction] ?? 'help_outline'
}

function directionColor(direction) {
  return DIRECTION_COLORS[direction] ?? 'grey-6'
}

function formatSlope(slope) {
  return Number(slope ?? 0).toFixed(2)
}
</script>
