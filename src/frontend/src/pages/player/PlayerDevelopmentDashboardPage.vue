<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">{{ $t('development.dashboardTitle') }}</div>

    <q-banner v-if="store.error" class="bg-negative text-white q-mb-md" rounded>
      {{ store.error }}
    </q-banner>

    <q-inner-loading :showing="store.loading" />

    <template v-if="!store.loading">
      <!-- Skills Radar card — hero element above exposure chart. Read-only: the player sees the
           coaches' composite scores but cannot change the displayed subset. -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.radar.displayTitle') }}</div>
          <q-toggle
            v-if="hasBaseline"
            v-model="showBaseline"
            :label="$t('development.radar.compareBaselineLabel')"
          />
        </q-card-section>
        <q-card-section class="relative-position">
          <!-- /radar/display is fetched alongside exposure but is not covered by store.loading. -->
          <q-inner-loading :showing="store.radarDisplayLoading" />
          <SkillsRadarChart
            :skills="store.radarDisplay?.skills ?? []"
            :selected-skill-codes="[]"
            :show-baseline="showBaseline"
            readonly
          />
        </q-card-section>
      </q-card>

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

      <!-- Skill Trends — skillars-deferred-165 AC4: per-skill direction/slope, a different shape
           than the weekly-total time series above. -->
      <q-card class="q-mt-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.skillTrends.title') }}</div>
        </q-card-section>
        <q-card-section class="relative-position">
          <q-inner-loading :showing="store.skillTrendsLoading" />
          <SkillTrendsPanel
            :trends="store.skillTrends"
            :skill-definitions="skillDefinitions"
            :error="!!store.skillTrendsError && !store.skillTrendsLoading"
          />
        </q-card-section>
      </q-card>

      <!-- Performance Reports — read-only list for the player (no generate button). -->
      <q-card class="q-mt-md">
        <q-card-section>
          <PerformanceReportsPanel :player-id="playerId" :is-coach="false" />
        </q-card-section>
      </q-card>

      <!-- Player Timeline — skillars-deferred-153 code review: :key="playerId" forces a clean
           remount on playerId change, since PlayerTimelinePanel.vue only fetches in onMounted
           with no playerId prop watcher. playerId is effectively fixed on this page, so this is
           consistency with the coach/parent pages rather than a live bug fix. -->
      <q-card class="q-mt-md">
        <q-card-section>
          <PlayerTimelinePanel :player-id="playerId" :key="playerId" />
        </q-card-section>
      </q-card>
    </template>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import SkillsRadarChart from 'src/components/development/SkillsRadarChart.vue'
import { useRoute } from 'vue-router'
import { useDevelopmentStore } from 'src/stores/development.store'
import SkillExposureBarChart from 'src/components/development/SkillExposureBarChart.vue'
import SkillExposureTrendChart from 'src/components/development/SkillExposureTrendChart.vue'
import SkillTrendsPanel from 'src/components/development/SkillTrendsPanel.vue'
import PerformanceReportsPanel from 'src/components/development/PerformanceReportsPanel.vue'
import PlayerTimelinePanel from 'src/components/development/PlayerTimelinePanel.vue'

const route = useRoute()
const store = useDevelopmentStore()

// Kept as the raw route-param STRING, not Number(...): player ids are backend Tsids (18-19
// digits), well past Number.MAX_SAFE_INTEGER, and the backend quotes Long as a JSON string
// specifically so JS never has to represent one as a number (CommonConfig.longToStringModule) —
// converting it back to a Number here silently corrupts it (e.g. …173564 becomes …173600),
// so every API call below was hitting a player id that does not exist. skillars-deferred-147.
const playerId = computed(() => route.params.playerId)
const showBaseline = ref(false)

// skillars-12-9 AC6: this route is PLAYER-only and authStore.role is single-valued, so the
// coach/parent branches that used to live here were dead. Coach tools now live on
// coach/players/:playerId; what remains is the read-only player view.
const hasBaseline = computed(() => {
  const skills = store.radarDisplay?.skills ?? []
  return skills.filter((s) => s.compositeScore !== null).some((s) => s.baselineScore !== null)
})

const skillDefinitions = computed(() => store.skillDefinitions)

function clearDevelopmentState() {
  store.exposure = null
  store.radarDisplay = null
  store.error = null
}

let loadRequestId = 0

async function loadPlayerData(id) {
  const requestId = ++loadRequestId
  store.fetchSkillTrends(id)
  await Promise.all([
    store.fetchSkillDefinitions(),
    store.fetchExposure(id),
    store.fetchRadarDisplay(id),
  ])
  if (requestId !== loadRequestId) {
    // A newer player load started while this one was in flight — its response may
    // have landed out of order and clobbered fresher data. Reload the current player.
    await loadPlayerData(playerId.value)
  }
}

onMounted(async () => {
  clearDevelopmentState()
  await loadPlayerData(playerId.value)
})

watch(
  () => route.params.playerId,
  async (newPlayerId) => {
    if (!newPlayerId) return
    // Clear stale state before loading new player
    clearDevelopmentState()
    await loadPlayerData(newPlayerId)
  },
  { immediate: false },
)
</script>
