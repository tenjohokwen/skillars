<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">{{ $t('development.dashboardTitle') }}</div>

    <q-banner v-if="store.error" class="bg-negative text-white q-mb-md" rounded>
      {{ store.error }}
    </q-banner>

    <q-inner-loading :showing="store.loading" />

    <template v-if="!store.loading">
      <SluNarrativeSummary v-if="isParent" :narrative="store.narrative" />

      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.skillExposureTitle') }}</div>
          <div class="text-caption">{{ $t('development.currentWeekLabel') }}</div>
        </q-card-section>
        <q-card-section>
          <SkillExposureBarChart
            :current-week="store.exposure?.currentWeek ?? {}"
            :neglected-codes="store.neglectedCodes"
            :skill-definitions="skillDefinitions"
          />
        </q-card-section>
        <q-card-actions v-if="isCoach">
          <q-btn
            flat
            color="primary"
            :label="$t('development.setTargetsLabel')"
            @click="showTargetEditor = true"
          />
          <q-btn
            v-if="isCoach && tierLoaded && !isScoutTier"
            flat
            color="secondary"
            :label="$t('development.radar.addAssessmentLabel')"
            @click="showRadarPanel = true"
          />
        </q-card-actions>
      </q-card>

      <q-card>
        <q-card-section>
          <div class="text-subtitle1">
            {{ $t('development.trendChartTitle', { weeks: 8 }) }}
          </div>
        </q-card-section>
        <q-card-section>
          <SkillExposureTrendChart
            :trend="store.exposure?.trend ?? []"
            :skill-definitions="skillDefinitions"
          />
        </q-card-section>
      </q-card>

      <q-card v-if="isCoach && tierLoaded && !isScoutTier" class="q-mt-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.radar.historyTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <RadarAssessmentHistoryList :radar-entries="store.radarEntries" />
        </q-card-section>
      </q-card>
    </template>

    <SluTargetEditor
      v-model="showTargetEditor"
      :skill-definitions="skillDefinitions"
      :current-targets="store.targets"
      @save="onSaveTargets"
    />

    <SkillsRadarAssessmentPanel
      v-if="isCoach && tierLoaded && !isScoutTier"
      v-model="showRadarPanel"
      :player-id="playerId"
      :skill-definitions="skillDefinitions"
    />
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from 'src/stores/auth.store'
import { useDevelopmentStore } from 'src/stores/development.store'
import SkillExposureBarChart from 'src/components/development/SkillExposureBarChart.vue'
import SkillExposureTrendChart from 'src/components/development/SkillExposureTrendChart.vue'
import SluTargetEditor from 'src/components/development/SluTargetEditor.vue'
import SluNarrativeSummary from 'src/components/development/SluNarrativeSummary.vue'
import SkillsRadarAssessmentPanel from 'src/components/development/SkillsRadarAssessmentPanel.vue'
import RadarAssessmentHistoryList from 'src/components/development/RadarAssessmentHistoryList.vue'

const route = useRoute()
const authStore = useAuthStore()
const store = useDevelopmentStore()

const playerId = computed(() => Number(route.params.playerId))
const isCoach = computed(() => authStore.isCoach)
const isParent = computed(() => authStore.isParent)
const RADAR_ALLOWED_TIERS = ['INSTRUCTOR', 'ACADEMY']
const isScoutTier = computed(() => !RADAR_ALLOWED_TIERS.includes(authStore.coachTier))
const tierLoaded = ref(false)
const showTargetEditor = ref(false)
const showRadarPanel = ref(false)

const skillDefinitions = computed(() => store.skillDefinitions)

onMounted(async () => {
  await Promise.all([
    store.fetchSkillDefinitions(),
    store.fetchExposure(playerId.value),
  ])
  if (isCoach.value) {
    await Promise.all([
      store.fetchTargets(playerId.value),
      store.fetchRadarEntries(playerId.value),
      authStore.fetchCoachTier().finally(() => { tierLoaded.value = true }),
    ])
  }
  if (isParent.value) {
    await store.fetchNarrative(playerId.value)
  }
})

async function onSaveTargets(targets) {
  await store.saveTargets(playerId.value, targets)
  await store.fetchExposure(playerId.value)
}
</script>
