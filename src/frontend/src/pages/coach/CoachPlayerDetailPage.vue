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

    <div class="text-h5 q-mb-md">{{ $t('coach.playerDetailTitle', { playerName }) }}</div>

    <q-banner v-if="pageError" class="bg-negative text-white q-mb-md" rounded>
      {{ pageError }}
    </q-banner>

    <template v-if="!loading && !pageError">
      <!-- Neglected skills — AC4(c), built from the already-fetched exposure/neglectedCodes
           store state rather than a call to /neglected-skills. -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-subtitle1">{{ $t('coach.playerDetailNeglectedSkillsTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <div
            v-if="neglectedSkillNames.length === 0"
            class="text-body2"
            style="color: var(--text-secondary)"
          >
            {{ $t('coach.playerDetailNoNeglectedSkills') }}
          </div>
          <q-list v-else dense>
            <q-item v-for="name in neglectedSkillNames" :key="name">
              <q-item-section>{{ name }}</q-item-section>
            </q-item>
          </q-list>
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

      <!-- Timeline — AC7: :key="playerId" forces a clean remount on player switch, since
           PlayerTimelinePanel.vue only fetches in onMounted with no playerId prop watcher. -->
      <q-card>
        <q-card-section>
          <PlayerTimelinePanel :player-id="playerId" :key="playerId" />
        </q-card-section>
      </q-card>
    </template>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useDevelopmentStore } from 'src/stores/development.store'
import { hasTopAttributions, contributionEmptyStateKind } from 'src/utils/coachContributions'
import CoachContributionSection from 'src/components/development/CoachContributionSection.vue'
import PlayerTimelinePanel from 'src/components/development/PlayerTimelinePanel.vue'

const route = useRoute()
const store = useDevelopmentStore()
const { t } = useI18n()

// AC5: playerId must be read and forwarded as the raw route-param string — Tsids exceed
// Number.MAX_SAFE_INTEGER, and Number(route.params.playerId) has already shipped and been
// fixed twice on this page's own model pages (skillars-deferred-147/-148).
const playerId = computed(() => route.params.playerId)

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

const neglectedSkillNames = computed(() =>
  store.neglectedCodes.map(
    (code) => store.skillDefinitions.find((s) => s.code === code)?.displayName ?? code,
  ),
)

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
const pageError = computed(() =>
  store.error || store.coachContributionsError ? t('coach.playerDetailError') : null,
)

// Starts true (not false): the very first render happens before this component's own onMounted
// runs, so a `false` initial value let `v-if="!loading"` mount PlayerTimelinePanel immediately,
// firing its fetchTimeline, only for the page's own onMounted (below) to flip loading to true a
// moment later and tear that same panel back down — then loadPlayerDetail's `finally` flips it
// false again, remounting PlayerTimelinePanel a second time and firing a second /timeline request.
const loading = ref(true)
let loadGeneration = 0

async function loadPlayerDetail(id) {
  if (!id) {
    loading.value = false
    return
  }
  const gen = ++loadGeneration
  loading.value = true
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

onMounted(() => loadPlayerDetail(playerId.value))

// AC7: re-triggers the page's own store-backed fetches on a playerId-only route change,
// mirroring ParentDevelopmentPortalPage.vue's existing watcher. Vue only invokes this callback
// when the watched value has actually changed, so newId is always distinct from oldId already —
// no extra guard needed.
watch(
  () => route.params.playerId,
  () => loadPlayerDetail(playerId.value),
)
</script>
