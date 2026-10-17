<template>
  <q-page class="q-pa-md">
    <q-inner-loading :showing="loading" />

    <div class="text-h5 q-mb-md">{{ $t('development.portal.title') }}</div>

    <q-banner v-if="pageError" class="bg-negative text-white q-mb-md" rounded>
      {{ pageError }}
    </q-banner>

    <!-- Skills Radar — hero (above all content per AC1) -->
    <q-card class="q-mb-md">
      <q-card-section>
        <div class="text-subtitle1">{{ $t('development.radar.displayTitle') }}</div>
      </q-card-section>
      <q-card-section>
        <SkillsRadarChart
          :skills="store.radarDisplay?.skills ?? []"
          :selected-skill-codes="[]"
          :show-baseline="false"
          :readonly="true"
        />
      </q-card-section>
    </q-card>

    <!-- Narrative summary — below radar per UX-DR29 -->
    <SluNarrativeSummary :narrative="store.narrative" class="q-mb-md" />

    <!-- Session pack tracker per coach -->
    <q-card class="q-mb-md">
      <q-card-section>
        <div class="text-subtitle1">{{ $t('development.portal.sessionPacksTitle') }}</div>
      </q-card-section>
      <q-card-section>
        <div v-if="!hasActivePacks" class="text-body2" style="color: var(--text-secondary)">
          {{ $t('development.portal.noPacksState') }}
          <q-btn
            flat
            dense
            color="primary"
            :label="$t('development.portal.findCoachCta')"
            :to="{ name: 'marketplace' }"
          />
        </div>
        <q-list v-if="bookingStore.sessionPacks.length > 0" dense>
          <q-item v-for="pack in bookingStore.sessionPacks" :key="pack.id" class="q-py-xs">
            <q-item-section>
              <q-item-label>{{ pack.coachDisplayName }}</q-item-label>
            </q-item-section>
            <q-item-section side>
              <SessionPackTracker
                :credits-remaining="pack.creditsRemaining"
                :session-count="pack.sessionCount"
              />
            </q-item-section>
          </q-item>
        </q-list>
      </q-card-section>
    </q-card>

    <!-- Coach contribution narrative — AC2: CoachContributionSection's own root v-if has no
         v-else, so it renders nothing when its top-per-skill/>=30% filter result is empty.
         hasTopContributions mirrors that same filter at the page level so this page can show
         its own empty state instead of relying on the child's silent collapse. -->
    <CoachContributionSection
      v-if="hasTopContributions"
      :contributions="store.coachContributions"
      class="q-mb-md"
    />
    <!-- skillars-deferred-152 code review (2026-10-17): the empty state renders only once a load
         has actually settled. coachContributions is [] while the fetch is in flight and still []
         after it fails, so unguarded this arm asserted "No coach contribution data for the last
         30 days" alongside the pageError banner on a failed load — a confident false claim where
         the pre-AC2 code silently collapsed. Unlike CoachPlayerDetailPage.vue there is no
         `v-if="!loading && !pageError"` body gate on this page to inherit, and adding one would
         change every card's mount timing here, so the guard is scoped to this section. -->
    <div
      v-else-if="!store.coachContributionsLoading && !store.coachContributionsError"
      class="text-body2 q-mb-md"
      style="color: var(--text-secondary)"
    >
      {{ $t(noContributionsKey) }}
    </div>

    <!-- Skill exposure bar chart -->
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
    </q-card>

    <!-- Neglected skill alert -->
    <q-banner v-if="store.neglectedCodes.length > 0" class="bg-warning text-white q-mb-md" rounded>
      {{ $t('development.portal.neglectedAlert', { skills: neglectedSkillNames }) }}
    </q-banner>

    <!-- Reports -->
    <q-card class="q-mb-md">
      <q-card-section>
        <PerformanceReportsPanel :player-id="playerId" :is-coach="false" />
      </q-card-section>
    </q-card>

    <!-- Timeline — skillars-deferred-153 code review: :key="playerId" forces a clean remount on
         child switch, since PlayerTimelinePanel.vue only fetches in onMounted with no playerId
         prop watcher. Reachable here specifically: this page watches route.params.playerId for
         param-only sibling switches, so without the key the panel keeps the previous child's
         events and its "Load more" appends the new child's page 1 onto them. -->
    <q-card>
      <q-card-section>
        <div class="text-subtitle1">{{ $t('development.timeline.title') }}</div>
      </q-card-section>
      <q-card-section>
        <PlayerTimelinePanel :player-id="playerId" :key="playerId" />
      </q-card-section>
    </q-card>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePlayerStore } from 'src/stores/playerStore'
import { useBookingStore } from 'src/stores/booking.store'
import { useDevelopmentStore } from 'src/stores/development.store'
import { hasTopAttributions, contributionEmptyStateKind } from 'src/utils/coachContributions'
import SluNarrativeSummary from 'src/components/development/SluNarrativeSummary.vue'
import SkillsRadarChart from 'src/components/development/SkillsRadarChart.vue'
import SkillExposureBarChart from 'src/components/development/SkillExposureBarChart.vue'
import CoachContributionSection from 'src/components/development/CoachContributionSection.vue'
import PerformanceReportsPanel from 'src/components/development/PerformanceReportsPanel.vue'
import PlayerTimelinePanel from 'src/components/development/PlayerTimelinePanel.vue'
import SessionPackTracker from 'src/components/booking/SessionPackTracker.vue'

const route = useRoute()
const router = useRouter()
const playerStore = usePlayerStore()
const bookingStore = useBookingStore()
const store = useDevelopmentStore()

const playerId = computed(() => route.params.playerId)
const loading = ref(false)

// True only when at least one pack is ACTIVE — exhausted/expired packs still appear in
// the list, but the "Find a coach" CTA must show whenever no active credits exist (AC4).
const hasActivePacks = computed(() => bookingStore.sessionPacks.some((p) => p.status === 'ACTIVE'))

const hasTopContributions = computed(() => hasTopAttributions(store.coachContributions))

// AC2: "no data at all" and "data exists but none cleared the 30% threshold" are meaningfully
// different situations for a parent -- conflating them can read as "no coach has worked with
// this player," which is false in the second case.
// skillars-deferred-153 AC10: the length===0 check itself now lives in the shared
// contributionEmptyStateKind helper, not re-derived here.
const noContributionsKey = computed(() =>
  contributionEmptyStateKind(store.coachContributions) === 'none'
    ? 'development.portal.noContributionsState'
    : 'development.portal.contributionsBelowThresholdState',
)

// Map raw skill codes to human-readable names so the neglected alert is parent-friendly.
const neglectedSkillNames = computed(() =>
  store.neglectedCodes
    .map((c) => store.skillDefinitions.find((s) => s.code === c)?.displayName ?? c)
    .join(', '),
)

// Collect all non-null error strings so no failure is silently hidden by the ?? chain.
const pageError = computed(() => {
  const errors = [store.error, bookingStore.packsError, store.coachContributionsError].filter(
    Boolean,
  )
  return errors.length > 0 ? errors[0] : null
})

// Generation counter prevents a slower stale load from clearing the loading flag
// after a faster newer load has already finished (rapid player-switch race).
let loadGeneration = 0

async function loadPortal(id) {
  if (!id) return
  const gen = ++loadGeneration
  loading.value = true
  try {
    await Promise.all([
      store.fetchSkillDefinitions(),
      store.fetchRadarDisplay(id),
      store.fetchExposure(id),
      store.fetchNarrative(id),
      store.fetchCoachContributions(id),
      bookingStore.loadPlayerPacks(id),
    ])
  } finally {
    if (gen === loadGeneration) loading.value = false
  }
}

onMounted(() => loadPortal(playerId.value))

// Reloads the portal when Vue Router reuses this component with a different playerId
// (param-only navigation does not trigger onMounted again).
watch(
  () => route.params.playerId,
  (newRaw, oldRaw) => {
    if (newRaw !== oldRaw) loadPortal(playerId.value)
  },
)

// Handles bookmark/direct-URL re-entry where the store's activePlayerId differs from
// the route param. ParentChildSwitcher already calls router.push directly — this watch
// does NOT fire in the normal switch flow (the page isn't mounted when the switcher
// navigates away). Kept narrow: only fires when newId !== current route param.
watch(
  () => playerStore.activePlayerId,
  (newId) => {
    if (newId && newId !== playerId.value) {
      router.push({ name: 'parent-development', params: { playerId: newId } })
    }
  },
)
</script>
