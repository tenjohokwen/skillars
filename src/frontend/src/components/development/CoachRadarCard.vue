<template>
  <q-card class="q-mb-md" data-testid="coach-radar-card">
    <q-card-section>
      <div class="text-subtitle1">{{ $t('development.radar.displayTitle') }}</div>
      <q-toggle
        v-if="hasBaseline"
        v-model="showBaseline"
        :label="$t('development.radar.compareBaselineLabel')"
      />
    </q-card-section>
    <q-card-section>
      <q-inner-loading :showing="store.radarDisplayLoading" />
      <q-banner
        v-if="store.radarDisplayError && !store.radarDisplayLoading"
        class="bg-negative text-white"
        rounded
        data-testid="coach-radar-error"
      >
        {{ $t('coach.playerDetailSectionError') }}
      </q-banner>
      <template v-else-if="!store.radarDisplayLoading">
        <q-banner
          v-if="store.radarPreferencesError"
          dense
          rounded
          class="q-mb-sm"
          data-testid="coach-radar-prefs-error"
        >
          {{ $t('coach.playerDetailRadarPrefsError') }}
        </q-banner>
        <SkillsRadarChart
          :skills="store.radarDisplay?.skills ?? []"
          :selected-skill-codes="localSelectedSkillCodes"
          :show-baseline="showBaseline"
          @update:selected-skill-codes="onSkillSelectionChange"
        />
      </template>
    </q-card-section>
  </q-card>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useQuasar } from 'quasar'
import { useDevelopmentStore } from 'src/stores/development.store'
import { getErrorMessage } from 'src/utils/errorHandler'
import SkillsRadarChart from 'src/components/development/SkillsRadarChart.vue'

// skillars-12-9 AC9: the radar card plus its per-(coach, player) skill-subset persistence, moved
// here from PlayerDevelopmentDashboardPage.vue (which no longer needs it) so the coach page stays
// readable. The card owns both radar fetches, keyed by playerId.

const props = defineProps({
  // Raw route-param string: Tsids exceed Number.MAX_SAFE_INTEGER.
  playerId: { type: String, required: true },
})

const store = useDevelopmentStore()
const $q = useQuasar()

// Local inline debounce (this codebase has no shared debounce composable). Extended with flush()
// so a pending persistence call is never silently dropped on player-switch, unmount or pagehide.
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

// Visual selection state, decoupled from persistence: the chart updates instantly per click, the
// PUT is debounced separately. Local state is the source of truth for the selection; the store's
// radarPreferences is only the last LOADED value.
const localSelectedSkillCodes = ref([])
const showBaseline = ref(false)
// True once the coach has clicked since the current player's preferences fetch started, so a late
// response never overwrites a fresher click (AC12a).
let selectionTouched = false

const debouncedSave = useDebounce(async (targetPlayerId, codes) => {
  try {
    await store.saveRadarPreferences(targetPlayerId, codes)
  } catch (err) {
    $q.notify({ type: 'negative', message: getErrorMessage(err) })
  }
}, 300)

function flushPendingSave() {
  debouncedSave.flush()
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

async function loadFor(id) {
  localSelectedSkillCodes.value = []
  showBaseline.value = false
  selectionTouched = false
  store.fetchRadarDisplay(id)
  const prefs = await store.fetchRadarPreferences(id)
  // Apply only if this response is for the still-current player and nothing was clicked since.
  if (props.playerId !== id || selectionTouched || !prefs) return
  localSelectedSkillCodes.value = prefs.selectedSkillCodes ?? []
}

window.addEventListener('pagehide', flushPendingSave)

// In practice this runs once, on mount (immediate): CoachPlayerDetailPage renders this card inside
// its core loading gate, so a player switch unmounts it (onBeforeUnmount flushes) and mounts a new
// instance, rather than changing the prop on a live one. The switch branch is kept so the card
// stays correct if it is ever mounted outside that gate.
watch(
  () => props.playerId,
  (newId) => {
    // Persist any pending selection against the player it was made for (the id was captured at
    // click time), before switching away.
    debouncedSave.flush()
    loadFor(newId)
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('pagehide', flushPendingSave)
  debouncedSave.flush()
})

function onSkillSelectionChange(codes) {
  localSelectedSkillCodes.value = codes
  selectionTouched = true
  // Capture playerId now, not when the debounce fires — a mid-window navigation must not persist
  // this selection against a different player's id.
  debouncedSave(props.playerId, codes)
}
</script>
