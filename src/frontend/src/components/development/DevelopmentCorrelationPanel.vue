<template>
  <div class="correlation-panel">
    <!-- Non-Academy teaser — blurred content with upgrade CTA overlay -->
    <div v-if="!isAcademyTier" class="teaser-wrapper">
      <div class="correlation-teaser" aria-hidden="true">
        <q-list>
          <q-item v-for="n in 3" :key="n">
            <q-item-section avatar>
              <q-badge color="primary">{{ ['PAC', 'SHO', 'DRI'][n - 1] }}</q-badge>
            </q-item-section>
            <q-item-section>
              <q-item-label>{{ $t('development.radar.correlation.insight.highSluImprovement') }}</q-item-label>
              <q-item-label caption>SLU: 120.00 | Score: 75.00</q-item-label>
            </q-item-section>
          </q-item>
        </q-list>
      </div>
      <div class="teaser-overlay">
        <q-card class="glass-card teaser-cta">
          <q-card-section class="text-center">
            <q-icon name="lock" size="2rem" color="primary" />
            <div class="text-subtitle1 q-mt-sm">
              {{ $t('development.radar.correlation.academyFeatureTeaser') }}
            </div>
            <q-btn
              color="primary"
              :label="$t('development.radar.correlation.upgradeButton')"
              to="/coach/subscription"
              unelevated
              class="q-mt-sm"
            />
          </q-card-section>
        </q-card>
      </div>
    </div>

    <!-- Loading state -->
    <q-inner-loading v-else-if="correlationLoading" :showing="true" />

    <!-- Error state: Academy coach, loading done, but data is null -->
    <div v-else-if="correlationData === null">
      <q-banner class="bg-negative text-white" rounded>
        {{ $t('development.radar.correlationError') }}
      </q-banner>
    </div>

    <!-- Insufficient data state -->
    <div v-else-if="correlationData.insufficientData" class="q-pa-md text-center text-secondary">
      {{ $t('development.radar.correlation.insufficientData', { count: correlationData.minimumSessionCount }) }}
    </div>

    <!-- Insights list -->
    <div v-else-if="correlationData.insights && correlationData.insights.length > 0">
      <q-list separator>
        <q-item v-for="insight in correlationData.insights" :key="insight.skillCode">
          <q-item-section avatar>
            <q-badge color="primary" :label="insight.skillCode" />
          </q-item-section>
          <q-item-section>
            <q-item-label>
              {{ $t('development.radar.correlation.insight.' + camelInsightKey(insight.insightType)) }}
            </q-item-label>
          </q-item-section>
          <q-item-section side>
            <div class="text-caption text-secondary">
              SLU: {{ insight.cumulativeSlu?.toFixed(1) }}
            </div>
            <div class="text-caption text-secondary">
              {{ $t('development.radar.accessibleTable.currentScore') }}: {{ insight.compositeScore?.toFixed(1) }}
            </div>
          </q-item-section>
        </q-item>
      </q-list>

      <!-- Excluded skills footnote -->
      <div
        v-if="correlationData.excludedSkillCount > 0"
        class="q-pa-sm text-caption text-secondary"
      >
        {{ $t('development.radar.correlation.excludedSkills', { count: correlationData.excludedSkillCount }) }}
      </div>
    </div>

    <!-- Zero insights: sessions sufficient but all skills excluded (no radar composites yet) -->
    <div v-else class="q-pa-md text-center text-secondary">
      {{ $t('development.radar.correlation.noInsightsYet') }}
      <div
        v-if="correlationData && correlationData.excludedSkillCount > 0"
        class="text-caption q-mt-xs"
      >
        {{ $t('development.radar.correlation.excludedSkills', { count: correlationData.excludedSkillCount }) }}
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  correlationData: { type: Object, default: null },
  isAcademyTier: { type: Boolean, required: true },
  correlationLoading: { type: Boolean, default: false },
})

function camelInsightKey(insightType) {
  // Converts e.g. "HIGH_SLU_IMPROVEMENT" → "highSluImprovement"
  return insightType
    .toLowerCase()
    .replace(/_([a-z])/g, (_, c) => c.toUpperCase())
}
</script>

<style scoped>
.teaser-wrapper {
  position: relative;
}

.correlation-teaser {
  filter: blur(4px);
  pointer-events: none;
  user-select: none;
}

.teaser-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(var(--glass-bg-rgb, 0, 0, 0), 0.3);
  border-radius: 8px;
}

.teaser-cta {
  max-width: 320px;
  width: 90%;
}
</style>
