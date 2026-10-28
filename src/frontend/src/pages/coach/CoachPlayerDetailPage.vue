<template>
  <q-page class="q-pa-md">
    <q-inner-loading :showing="loading" />

    <q-btn
      flat
      dense
      no-caps
      icon="arrow_back"
      color="primary"
      class="q-mb-md"
      :label="$t('coach.playerDetailBackToRoster')"
      :to="{ name: 'coach-players' }"
    />

    <div class="row items-center q-mb-md q-gutter-sm">
      <div class="text-h5 col">{{ $t('coach.playerDetailTitle', { playerName }) }}</div>
      <!-- Story 8.1 entry point. coachId is omitted: the backend defaults it to the caller's own
           coach profile. For an under-13 player the thread is with the parent (story 8.2). -->
      <q-btn
        outline
        no-caps
        color="primary"
        icon="chat"
        data-testid="coach-message-player"
        :label="$t('messaging.messagePlayer')"
        :loading="isStarting(null, playerId)"
        @click="startConversation(null, playerId)"
      />
    </div>

    <q-banner v-if="pageError" class="bg-negative text-white q-mb-md" rounded>
      {{ pageError }}
    </q-banner>

    <template v-if="!loading && !pageError">
      <!-- skillars-12-9 (G16/G17): every secondary section below has its OWN loading/error/empty
           state (store per-section refs), so a failure of one never blanks the page — only the
           skill-definitions / exposure / contributions trio trips the page-level gate above. -->

      <!-- Skills radar — hero card; owns its fetches and the preference persistence. -->
      <CoachRadarCard id="dev-section-radar" :player-id="playerId" />

      <!-- Narrative summary -->
      <q-card class="q-mb-md" data-testid="coach-narrative-card">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('coach.playerDetailNarrativeTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-inner-loading :showing="store.narrativeLoading" />
          <div
            v-if="store.narrativeError && !store.narrativeLoading"
            class="text-negative"
            data-testid="coach-narrative-error"
          >
            {{ $t('coach.playerDetailSectionError') }}
          </div>
          <template v-else-if="!store.narrativeLoading">
            <SluNarrativeSummary v-if="store.narrative.length" :narrative="store.narrative" />
            <div v-else class="text-body2" style="color: var(--text-secondary)">
              {{ $t('coach.playerDetailNoNarrative') }}
            </div>
          </template>
        </q-card-section>
      </q-card>

      <!-- Neglected skills — D12.9-A: fed by /neglected-skills so each skill can show how long its
           current neglect streak has run (the flag is computed against the MAX target across all
           coaches, so the copy says "neglected since", never "below your target"). -->
      <q-card class="q-mb-md" data-testid="coach-neglected-card">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('coach.playerDetailNeglectedSkillsTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-inner-loading :showing="store.neglectedSkillsLoading" />
          <div
            v-if="store.neglectedSkillsError && !store.neglectedSkillsLoading"
            class="text-negative"
            data-testid="coach-neglected-error"
          >
            {{ $t('coach.playerDetailSectionError') }}
          </div>
          <template v-else-if="!store.neglectedSkillsLoading">
            <div
              v-if="neglectedSkillRows.length === 0"
              class="text-body2"
              style="color: var(--text-secondary)"
            >
              {{ $t('coach.playerDetailNoNeglectedSkills') }}
            </div>
            <q-list v-else dense>
              <q-item v-for="row in neglectedSkillRows" :key="row.skillCode">
                <q-item-section>{{ row.name }}</q-item-section>
                <q-item-section side>
                  {{ $t('coach.playerDetailNeglectedSince', { date: row.since }) }}
                </q-item-section>
              </q-item>
            </q-list>
          </template>
        </q-card-section>
      </q-card>

      <!-- Skill exposure + coach actions -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.skillExposureTitle') }}</div>
          <div class="text-caption">{{ $t('development.currentWeekLabel') }}</div>
        </q-card-section>
        <q-card-section>
          <SkillExposureBarChart
            :current-week="store.exposure?.currentWeek ?? {}"
            :neglected-codes="store.neglectedCodes"
            :skill-definitions="store.skillDefinitions"
          />
        </q-card-section>
        <q-card-actions>
          <!-- Data-loss guard: SluTargetEditor sends null for every blank skill and the server
               deletes the row for each null, so it must never be opened from an editor seeded with
               the cleared [] (GET in flight or failed). -->
          <q-btn
            flat
            color="primary"
            :label="$t('development.setTargetsLabel')"
            :disable="!targetsReady"
            data-testid="coach-set-targets"
            @click="showTargetEditor = true"
          />
          <q-btn
            v-if="canAssess"
            flat
            color="secondary"
            :label="$t('development.radar.addAssessmentLabel')"
            data-testid="coach-add-assessment"
            @click="showRadarPanel = true"
          />
        </q-card-actions>
        <q-card-section v-if="store.targetsError && !store.targetsLoading" class="q-pt-none">
          <div class="text-negative text-body2" data-testid="coach-targets-error">
            {{ $t('coach.playerDetailTargetsError') }}
          </div>
        </q-card-section>
        <q-card-section v-if="tierUnknown" class="q-pt-none">
          <div class="text-negative text-body2" data-testid="coach-tier-error">
            {{ $t('coach.playerDetailTierError') }}
          </div>
        </q-card-section>
      </q-card>

      <!-- Skill trend -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.trendChartTitle', { weeks: 8 }) }}</div>
        </q-card-section>
        <q-card-section>
          <!-- SkillExposureTrendChart is a bare v-chart with no empty branch of its own. -->
          <div
            v-if="!store.exposure?.trend?.length"
            class="text-body2"
            style="color: var(--text-secondary)"
            data-testid="coach-trend-empty"
          >
            {{ $t('coach.playerDetailNoTrend') }}
          </div>
          <SkillExposureTrendChart
            v-else
            :trend="store.exposure.trend"
            :skill-definitions="store.skillDefinitions"
          />
        </q-card-section>
      </q-card>

      <!-- Skill Trends — skillars-deferred-165 AC4: per-skill direction/slope, a different shape
           than the weekly-total time series above. -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.skillTrends.title') }}</div>
        </q-card-section>
        <q-card-section class="relative-position">
          <q-inner-loading :showing="store.skillTrendsLoading" />
          <SkillTrendsPanel
            :trends="store.skillTrends"
            :skill-definitions="store.skillDefinitions"
            :error="!!store.skillTrendsError && !store.skillTrendsLoading"
          />
        </q-card-section>
      </q-card>

      <!-- Coach contribution breakdown — AC9: CoachContributionSection.vue's own root v-if has
           no v-else, so it renders literally nothing when its top-per-skill/>=30% filter result
           is empty. hasTopContributions mirrors that same filter at the page level so this page
           can show its own empty state instead of relying on the child's silent collapse. -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('coach.playerDetailContributionsTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <CoachContributionSection
            v-if="hasTopContributions"
            :contributions="store.coachContributions"
          />
          <div v-else class="text-body2" style="color: var(--text-secondary)">
            {{ $t(noContributionsKey) }}
          </div>
        </q-card-section>
      </q-card>

      <!-- Correlation — real data for ACADEMY, the blurred teaser for a known lower tier. Never
           shown while the tier is unsettled or unknown (a failed tier load is not "SCOUT"). -->
      <q-card v-if="showCorrelation" class="q-mb-md" data-testid="coach-correlation-card">
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

      <!-- Radar assessment history — INSTRUCTOR / ACADEMY only (server: 403 otherwise). -->
      <q-card v-if="canAssess" class="q-mb-md" data-testid="coach-radar-history-card">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.radar.historyTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-inner-loading :showing="store.radarLoading" />
          <div
            v-if="store.radarEntriesError && !store.radarLoading"
            class="text-negative"
            data-testid="coach-radar-history-error"
          >
            {{ $t('coach.playerDetailSectionError') }}
          </div>
          <RadarAssessmentHistoryList
            v-else-if="!store.radarLoading"
            :radar-entries="store.radarEntries"
          />
        </q-card-section>
      </q-card>

      <!-- Radar assessment history teaser for a known SCOUT coach (UX-DR22 soft teaser: blurred
           sample + upgrade CTA). The "Add assessment" entry stays absent for SCOUT (Story 5.x: absent,
           not disabled); a failed/unknown tier load is never treated as SCOUT here. -->
      <q-card v-else-if="isScoutTier" class="q-mb-md" data-testid="coach-radar-history-gate">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('development.radar.historyTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <FeatureGateOverlay
            :required-tier="$t('featureGate.tier.INSTRUCTOR')"
            :feature-label="$t('development.radar.historyTitle')"
            upgrade-to="/coach/subscription"
          >
            <q-list dense style="min-height: 240px">
              <q-item v-for="n in 3" :key="n">
                <q-item-section>
                  <q-item-label>{{ $t('featureGate.sampleRow') }}</q-item-label>
                  <q-item-label caption>— · —</q-item-label>
                </q-item-section>
              </q-item>
            </q-list>
          </FeatureGateOverlay>
        </q-card-section>
      </q-card>

      <!-- Homework this coach assigned the player, with completion (D2). -->
      <CoachHomeworkCard id="dev-section-homework" :key="`hw-${playerId}`" :player-id="playerId" />

      <!-- Reports — the backend filters a coach to the reports THEY generated. -->
      <q-card id="dev-section-reports" class="q-mb-md" data-testid="coach-reports-card">
        <q-card-section>
          <PerformanceReportsPanel
            :key="playerId"
            :player-id="playerId"
            :player-name="playerName"
            :is-coach="tierSettled"
          />
          <div class="text-caption" style="color: var(--text-secondary)">
            {{ $t('coach.playerDetailReportsHint') }}
          </div>
        </q-card-section>
      </q-card>

      <!-- Timeline — AC7: :key="playerId" forces a clean remount on player switch, since
           PlayerTimelinePanel.vue only fetches in onMounted with no playerId prop watcher. -->
      <q-card>
        <q-card-section>
          <PlayerTimelinePanel
            :key="playerId"
            :player-id="playerId"
            viewer="COACH"
            :link-anchors="timelineAnchors"
          />
        </q-card-section>
      </q-card>

      <SluTargetEditor
        v-model="showTargetEditor"
        :skill-definitions="store.skillDefinitions"
        :current-targets="store.targets"
        @save="onSaveTargets"
      />

      <SkillsRadarAssessmentPanel
        v-if="canAssess"
        v-model="showRadarPanel"
        :player-id="playerId"
        :skill-definitions="store.skillDefinitions"
        @submitted="onAssessmentSubmitted"
      />
    </template>
  </q-page>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useAuthStore } from 'src/stores/auth.store'
import { useDevelopmentStore } from 'src/stores/development.store'
import { getErrorMessage } from 'src/utils/errorHandler'
import { hasTopAttributions, contributionEmptyStateKind } from 'src/utils/coachContributions'
import CoachContributionSection from 'src/components/development/CoachContributionSection.vue'
import PlayerTimelinePanel from 'src/components/development/PlayerTimelinePanel.vue'
import CoachHomeworkCard from 'src/components/development/CoachHomeworkCard.vue'
import FeatureGateOverlay from 'src/components/common/FeatureGateOverlay.vue'
import { TIMELINE_ANCHORS } from 'src/utils/timelineLinks'
import CoachRadarCard from 'src/components/development/CoachRadarCard.vue'
import SluNarrativeSummary from 'src/components/development/SluNarrativeSummary.vue'
import SkillExposureBarChart from 'src/components/development/SkillExposureBarChart.vue'
import SkillExposureTrendChart from 'src/components/development/SkillExposureTrendChart.vue'
import SkillTrendsPanel from 'src/components/development/SkillTrendsPanel.vue'
import SluTargetEditor from 'src/components/development/SluTargetEditor.vue'
import SkillsRadarAssessmentPanel from 'src/components/development/SkillsRadarAssessmentPanel.vue'
import RadarAssessmentHistoryList from 'src/components/development/RadarAssessmentHistoryList.vue'
import DevelopmentCorrelationPanel from 'src/components/development/DevelopmentCorrelationPanel.vue'
import PerformanceReportsPanel from 'src/components/development/PerformanceReportsPanel.vue'
import { useStartConversation } from 'src/composables/useStartConversation'

const route = useRoute()
const store = useDevelopmentStore()
const authStore = useAuthStore()
const $q = useQuasar()
const { t, locale } = useI18n()

// AC5: playerId must be read and forwarded as the raw route-param string — Tsids exceed
// Number.MAX_SAFE_INTEGER, and Number(route.params.playerId) has already shipped and been
// fixed twice on this page's own model pages (skillars-deferred-147/-148).
const playerId = computed(() => route.params.playerId)
const { isStarting, startConversation } = useStartConversation()

// skillars-deferred-156 AC5: playerName now comes from the exposure fetch response, not the
// route's own query string (which put a minor's name in browser history/bookmarks/referrers).
// development.store.js's fetchExposure nulls exposure.value at the START of every fetch and
// never repopulates it on error — without this cache, the always-visible heading above (it
// renders outside both the pageError and loading content guards) would fall back to the
// translated placeholder for the full duration of every load AND permanently on any fetch
// error, where the route-query version used to keep showing the real name throughout. Keyed by
// playerId (not a single stale slot) so switching to a player whose name isn't cached yet shows
// the honest placeholder rather than the PREVIOUS player's name.
const lastKnownPlayerNames = ref({})
watch(
  () => store.exposure?.playerName,
  (name) => {
    if (name && playerId.value) lastKnownPlayerNames.value[playerId.value] = name
  },
)
const playerName = computed(
  () =>
    store.exposure?.playerName ??
    lastKnownPlayerNames.value[playerId.value] ??
    t('coach.playerDetailUnknownPlayerName'),
)

// skillars-12-9 AC7: oldest streak first, sorted on parsed dates (the endpoint returns flags
// unordered). Month names come from toLocaleDateString(locale) rather than Quasar's date.formatDate
// because no Quasar lang pack is configured, so de/fr would show English month names.
const neglectedSkillRows = computed(() =>
  [...store.neglectedSkills]
    .map((flag) => ({ ...flag, detected: new Date(flag.detectedAt) }))
    .sort((a, b) => a.detected - b.detected)
    .map((flag) => ({
      skillCode: flag.skillCode,
      name:
        store.skillDefinitions.find((s) => s.code === flag.skillCode)?.displayName ??
        flag.skillCode,
      since: flag.detected.toLocaleDateString(locale.value),
    })),
)

// Coach tier. fetchCoachTier sets coachTier = null on ANY error, so once the fetch has settled a
// null tier means "unknown", not SCOUT: every tier-gated tool is hidden and one message is shown.
const RADAR_ALLOWED_TIERS = ['INSTRUCTOR', 'ACADEMY']
const tierSettled = ref(false)
const tierUnknown = computed(() => tierSettled.value && authStore.coachTier == null)
const canAssess = computed(
  () => tierSettled.value && RADAR_ALLOWED_TIERS.includes(authStore.coachTier),
)
const isAcademyTier = computed(() => tierSettled.value && authStore.coachTier === 'ACADEMY')
const isScoutTier = computed(() => tierSettled.value && authStore.coachTier === 'SCOUT')
const timelineAnchors = [
  TIMELINE_ANCHORS.radar,
  TIMELINE_ANCHORS.reports,
  TIMELINE_ANCHORS.homework,
]
const showCorrelation = computed(() => tierSettled.value && authStore.coachTier != null)

const showTargetEditor = ref(false)
const showRadarPanel = ref(false)
// Never offer the editor until GET /targets for the CURRENT player succeeded (data-loss guard).
const targetsReady = computed(() => !store.targetsLoading && !store.targetsError)

const hasTopContributions = computed(() => hasTopAttributions(store.coachContributions))

// AC2: "no data at all" and "data exists but none cleared the 30% threshold" are meaningfully
// different situations for a coach -- conflating them (as the single pre-existing string did)
// can read as "no coach has worked with this player," which is false in the second case.
// skillars-deferred-153 AC10: the length===0 check itself now lives in the shared
// contributionEmptyStateKind helper, not re-derived here.
const noContributionsKey = computed(() =>
  contributionEmptyStateKind(store.coachContributions) === 'none'
    ? 'coach.playerDetailNoContributions'
    : 'coach.playerDetailContributionsBelowThreshold',
)

// Renders a single generic translated message rather than surfacing store.error/
// store.coachContributionsError's own raw text directly: those two refs can legitimately disagree
// about which of the page's concurrent fetches actually failed (store.error is shared between
// fetchSkillDefinitions and fetchExposure), so a specific per-call message can read as describing
// the wrong failure. coach.playerDetailError also only earns its keep in all three locales this way.
//
// skillars-12-9: the gate reads the per-section refs, not store.error, and trips only on the core
// trio (skill definitions, exposure, contributions); secondary sections degrade inside their card.
// skillars-12-10 AC7: a 403 on a player-scoped core fetch (no relationship, or the coach-access window has
// closed - the server uses one exception for both, so the copy must not claim which) gets its own neutral
// message instead of the generic load error.
const pageError = computed(() => {
  if (!(store.skillDefinitionsError || store.exposureError || store.coachContributionsError)) {
    return null
  }
  return store.coreForbidden ? t('coach.playerDetailAccessEnded') : t('coach.playerDetailError')
})

// Starts true (not false): the very first render happens before this component's own onMounted
// runs, so a `false` initial value let `v-if="!loading"` mount PlayerTimelinePanel immediately,
// firing its fetchTimeline, only for the page's own onMounted (below) to flip loading to true a
// moment later and tear that same panel back down — then loadPlayerDetail's `finally` flips it
// false again, remounting PlayerTimelinePanel a second time and firing a second /timeline request.
const loading = ref(true)
let loadGeneration = 0

// One tier fetch for the page's lifetime (auth.store has no dedupe); every load awaits it, so a
// player switch while it is in flight still gets ITS tier-gated fetches and the superseded
// player's never land.
let tierPromise = null

async function loadTierGatedSections(id, gen) {
  await tierPromise
  // Settled in the same synchronous block that starts the gated fetches, so a gated card never
  // renders its "no data" branch between the tier settling and its fetch starting.
  tierSettled.value = true
  if (gen !== loadGeneration) return
  if (RADAR_ALLOWED_TIERS.includes(authStore.coachTier)) store.fetchRadarEntries(id)
  if (authStore.coachTier === 'ACADEMY') store.fetchCorrelationInsights(id)
}

async function loadPlayerDetail(id) {
  if (!id) {
    // Supersede any in-flight load, so its tier-gated fetches never start for a page we have left.
    loadGeneration++
    loading.value = false
    return
  }
  const gen = ++loadGeneration
  loading.value = true
  // Secondary sections: started in parallel, deliberately NOT awaited by the core loading flag.
  // Each store action clears its own data first and drops out-of-order responses.
  // Exception, accepted at code review (2026-10-23): CoachRadarCard and PerformanceReportsPanel
  // fetch from their own mount, and both sit inside the core `!loading && !pageError` gate, so
  // /radar/display, /radar/preferences and /reports start after the core trio and the two
  // components remount on every player switch (that remount, plus CoachRadarCard's unmount flush,
  // is what makes a switch safe for them).
  store.fetchNarrative(id)
  store.fetchNeglectedSkills(id)
  store.fetchSkillTrends(id)
  store.fetchTargets(id)
  loadTierGatedSections(id, gen)
  try {
    await Promise.all([
      store.fetchSkillDefinitions(),
      store.fetchExposure(id),
      store.fetchCoachContributions(id),
    ])
  } finally {
    if (gen === loadGeneration) loading.value = false
  }
}

onBeforeUnmount(() => {
  loadGeneration++
})

onMounted(() => {
  tierPromise = authStore.fetchCoachTier()
  return loadPlayerDetail(playerId.value)
})

async function onSaveTargets(payload) {
  const id = playerId.value
  try {
    await store.saveTargets(id, payload)
  } catch (err) {
    // Includes the relationship-status write mismatch (reads use the wide status list, writes the
    // narrow one); the targets are unchanged, so no state to roll back.
    $q.notify({ type: 'negative', message: getErrorMessage(err) })
    return
  }
  // No exposure refresh (code review 2026-10-23, deliberate deviation from AC4's wording): exposure
  // does not depend on targets (SLU snapshots + open neglect flags + name), and neglect flags are
  // only recomputed by the weekly NeglectedSkillDetectionService job. A refresh therefore showed
  // nothing new, briefly blanked the heading/charts, and on failure tripped the core pageError gate.
  $q.notify({ type: 'positive', message: t('coach.playerDetailTargetsSaved') })
}

function onAssessmentSubmitted() {
  // The submit already re-fetched the radar history; refresh the chart too.
  store.fetchRadarDisplay(playerId.value)
}

// AC7: re-triggers the page's own store-backed fetches on a playerId-only route change,
// mirroring ParentDevelopmentPortalPage.vue's existing watcher. Vue only invokes this callback
// when the watched value has actually changed, so newId is always distinct from oldId already —
// no extra guard needed.
watch(
  () => route.params.playerId,
  () => loadPlayerDetail(playerId.value),
)
</script>
