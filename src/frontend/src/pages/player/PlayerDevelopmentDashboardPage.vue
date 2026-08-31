<template>
  <q-page class="q-pa-md">
    <div class="text-h5 q-mb-md">{{ $t('development.dashboardTitle') }}</div>

    <q-banner v-if="store.error" class="bg-negative text-white q-mb-md" rounded>
      {{ store.error }}
    </q-banner>

    <q-inner-loading :showing="store.loading" />

    <template v-if="!store.loading">
      <SluNarrativeSummary v-if="isParent" :narrative="store.narrative" />

      <!-- Skills Radar card — hero element above exposure chart -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.radar.displayTitle') }}</div>
          <q-toggle
            v-if="hasBaseline"
            v-model="showBaseline"
            :label="$t('development.radar.compareBaselineLabel')"
          />
        </q-card-section>
        <q-card-section>
          <SkillsRadarChart
            :skills="store.radarDisplay?.skills ?? []"
            :selected-skill-codes="localSelectedSkillCodes"
            :show-baseline="showBaseline"
            :readonly="!isCoach"
            @update:selected-skill-codes="onSkillSelectionChange"
          />
        </q-card-section>
      </q-card>

      <!-- Development Correlation Engine — Coach only -->
      <q-card v-if="isCoach" class="q-mt-md q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.radar.correlationTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <DevelopmentCorrelationPanel
            :correlation-data="store.correlationInsights"
            :is-academy-tier="isAcademyTier"
            :correlation-loading="store.correlationLoading"
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

      <!-- Performance Reports -->
      <q-card v-if="tierLoaded" class="q-mt-md">
        <q-card-section>
          <PerformanceReportsPanel
            :player-id="playerId"
            :player-name="playerName"
            :is-coach="isCoach"
          />
        </q-card-section>
      </q-card>

      <!-- Player Timeline -->
      <q-card class="q-mt-md">
        <q-card-section>
          <PlayerTimelinePanel :player-id="playerId" />
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
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import SkillsRadarChart from 'src/components/development/SkillsRadarChart.vue'
import DevelopmentCorrelationPanel from 'src/components/development/DevelopmentCorrelationPanel.vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from 'src/stores/auth.store'
import { useDevelopmentStore } from 'src/stores/development.store'
import SkillExposureBarChart from 'src/components/development/SkillExposureBarChart.vue'
import SkillExposureTrendChart from 'src/components/development/SkillExposureTrendChart.vue'
import SluTargetEditor from 'src/components/development/SluTargetEditor.vue'
import SluNarrativeSummary from 'src/components/development/SluNarrativeSummary.vue'
import SkillsRadarAssessmentPanel from 'src/components/development/SkillsRadarAssessmentPanel.vue'
import RadarAssessmentHistoryList from 'src/components/development/RadarAssessmentHistoryList.vue'
import PerformanceReportsPanel from 'src/components/development/PerformanceReportsPanel.vue'
import PlayerTimelinePanel from 'src/components/development/PlayerTimelinePanel.vue'

// Local inline debounce (this codebase has no shared debounce composable — mirrors
// DrillLibraryPage.vue's own pattern). Extended with flush() so a pending persistence call is
// never silently dropped on player-switch or unmount.
function useDebounce(fn, delay) {
  let timer = null
  let pendingArgs = null
  const debounced = (...args) => {
    pendingArgs = args
    clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      const argsToRun = pendingArgs
      pendingArgs = null
      fn(...argsToRun)
    }, delay)
  }
  debounced.flush = () => {
    if (timer === null) return
    clearTimeout(timer)
    timer = null
    const argsToRun = pendingArgs
    pendingArgs = null
    if (argsToRun) fn(...argsToRun)
  }
  return debounced
}

const route = useRoute()
const authStore = useAuthStore()
const store = useDevelopmentStore()

const playerId = computed(() => Number(route.params.playerId))
const playerName = computed(() => route.query.playerName ?? '')
const isCoach = computed(() => authStore.isCoach)
const isParent = computed(() => authStore.isParent)
const RADAR_ALLOWED_TIERS = ['INSTRUCTOR', 'ACADEMY']
const isScoutTier = computed(() => !RADAR_ALLOWED_TIERS.includes(authStore.coachTier))
const isAcademyTier = computed(() => authStore.coachTier === 'ACADEMY')
const tierLoaded = ref(false)
const showTargetEditor = ref(false)
const showRadarPanel = ref(false)
const showBaseline = ref(false)

// Visual selection state, decoupled from persistence. Reads/writes here are synchronous so the
// radar chart updates instantly per click; the PUT to persist is debounced separately.
// Kept in sync with the store explicitly in loadPlayerData() rather than via a watch, so a
// late-resolving (possibly stale-player) save cannot drive the chart.
const localSelectedSkillCodes = ref([])

const debouncedSaveRadarPreferences = useDebounce((targetPlayerId, codes) => {
  store.saveRadarPreferences(targetPlayerId, codes)
}, 300)

function flushPendingRadarSave() {
  debouncedSaveRadarPreferences.flush()
}

const hasBaseline = computed(() => {
  const skills = store.radarDisplay?.skills ?? []
  const selected = localSelectedSkillCodes.value
  const active =
    selected.length > 0
      ? skills.filter((s) => selected.includes(s.skillCode))
      : skills.filter((s) => s.compositeScore !== null)
  return active.some((s) => s.baselineScore !== null)
})

const skillDefinitions = computed(() => store.skillDefinitions)

function clearDevelopmentState() {
  store.exposure = null
  store.targets = []
  store.narrative = []
  store.error = null
  store.radarEntries = null
  store.radarDisplay = null
  store.radarPreferences = null
  store.correlationInsights = null
  localSelectedSkillCodes.value = []
}

let loadRequestId = 0

async function loadPlayerData(id) {
  const requestId = ++loadRequestId
  await Promise.all([store.fetchSkillDefinitions(), store.fetchExposure(id)])
  if (isCoach.value) {
    await Promise.all([
      store.fetchTargets(id),
      store.fetchRadarEntries(id),
      store.fetchRadarDisplay(id),
      store.fetchRadarPreferences(id),
      store.fetchCorrelationInsights(id),
      authStore.fetchCoachTier().finally(() => {
        tierLoaded.value = true
      }),
    ])
  }
  if (isParent.value) {
    await Promise.all([store.fetchNarrative(id), store.fetchRadarDisplay(id)])
  }
  if (requestId !== loadRequestId) {
    // A newer player load started while this one was in flight — its response may
    // have landed out of order and clobbered fresher data. Reload the current player.
    await loadPlayerData(playerId.value)
    return
  }
  // Sync visual selection from the freshly-loaded preferences for this (still-current) player.
  localSelectedSkillCodes.value = store.radarPreferences?.selectedSkillCodes ?? []
}

onMounted(async () => {
  window.addEventListener('pagehide', flushPendingRadarSave)
  clearDevelopmentState()
  await loadPlayerData(playerId.value)
})

watch(
  () => route.params.playerId,
  async (newPlayerId) => {
    const id = Number(newPlayerId)
    if (!Number.isFinite(id)) return
    // Persist any pending selection against the player it was made for, before switching away.
    debouncedSaveRadarPreferences.flush()
    // Clear stale state before loading new player
    clearDevelopmentState()
    await loadPlayerData(id)
  },
  { immediate: false },
)

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', flushPendingRadarSave)
  debouncedSaveRadarPreferences.flush()
})

async function onSaveTargets(targets) {
  await store.saveTargets(playerId.value, targets)
  await store.fetchExposure(playerId.value)
}

function onSkillSelectionChange(codes) {
  if (!isCoach.value) return
  // Instant visual feedback — no network round-trip in this path.
  localSelectedSkillCodes.value = codes
  // Capture playerId now, not when the debounce fires — a mid-window navigation must not
  // persist this selection against a different player's id.
  debouncedSaveRadarPreferences(playerId.value, codes)
}
</script>
