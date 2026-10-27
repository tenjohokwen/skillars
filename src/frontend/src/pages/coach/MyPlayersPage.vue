<template>
  <q-page class="q-pa-md">
    <div class="my-players-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('coach.myPlayersTitle') }}</div>

      <div v-if="loading" class="flex flex-center q-py-xl">
        <q-spinner size="48px" />
      </div>

      <!-- skillars-12-1 AC8: a coach with no CoachProfile row yet (mid profile-builder) gets a
           plain 404 from GET /api/development/coaches/me/roster — render a dedicated
           incomplete-profile state, matching CoachReviewsPage.vue's precedent exactly. -->
      <div v-else-if="incompleteProfile" class="text-center q-py-xl q-gutter-md">
        <q-icon name="person_off" size="48px" style="color: var(--text-secondary)" />
        <div class="text-body1" style="color: var(--text-primary)">
          {{ t('coach.myPlayersIncompleteProfile') }}
        </div>
        <q-btn
          no-caps
          color="primary"
          :label="t('coach.myPlayersCompleteProfile')"
          to="/coach/profile-builder"
        />
      </div>

      <div v-else-if="loadError" class="text-center q-py-xl text-body2 text-negative">
        {{ t('common.errorGeneric') }}
      </div>

      <div v-else-if="!players.length" class="text-center q-py-xl text-body2 text-secondary">
        {{ t('coach.myPlayersEmpty') }}
      </div>

      <template v-else>
        <!-- skillars-12-10 (D6): when EVERY player is lapsed there is nothing to open - say so, then the
             lapsed group below still lists them (this is not the "empty roster" state). -->
        <div
          v-if="!openPlayers.length"
          class="text-center q-pb-md text-body2 text-secondary"
          data-testid="all-lapsed-hint"
        >
          {{ t('coach.myPlayersAllLapsed') }}
        </div>
        <div
          v-for="player in openPlayers"
          :key="player.playerId"
          class="player-item clickable q-mb-md q-pb-md row items-center justify-between"
          role="button"
          tabindex="0"
          @click="goToPlayerDetail(player)"
          @keyup.enter="goToPlayerDetail(player)"
          @keyup.space="goToPlayerDetail(player)"
        >
          <div>
            <div class="text-body1" style="color: var(--text-primary)">
              {{ player.playerName }}
            </div>
            <div class="text-caption" style="color: var(--text-secondary)">
              {{ lastSessionLabel(player) }}
            </div>
          </div>
          <div class="row items-center q-gutter-sm">
            <q-badge
              v-if="player.dormant"
              color="warning"
              :label="t('coach.myPlayersDormantBadge')"
            />
            <q-badge v-if="player.openNeglectedSkillCount > 0" color="negative">
              {{
                t('coach.myPlayersNeglectedSkillCount', { count: player.openNeglectedSkillCount })
              }}
            </q-badge>
            <!-- Story 8.1 entry point; .stop so it doesn't also open the player detail. -->
            <q-btn
              flat
              dense
              round
              icon="chat"
              color="primary"
              :data-testid="`roster-message-${player.playerId}`"
              :aria-label="t('messaging.messagePlayer')"
              :loading="isStarting(null, player.playerId)"
              @click.stop="startConversation(null, player.playerId)"
              @keyup.enter.stop
              @keyup.space.stop
            >
              <q-tooltip>{{ t('messaging.messagePlayer') }}</q-tooltip>
            </q-btn>
          </div>
        </div>

        <!-- skillars-12-10 (D6): lapsed = the coach-access window has closed. Findable, but identity and
             last-session facts only - deliberately not a button, no detail link, no badges. -->
        <template v-if="lapsedPlayers.length">
          <div class="text-subtitle2 q-mt-lg q-mb-sm" data-testid="lapsed-heading">
            {{ t('coach.myPlayersLapsedHeading') }}
          </div>
          <div class="text-caption q-mb-md" style="color: var(--text-secondary)">
            {{ t('coach.myPlayersLapsedHint') }}
          </div>
          <div
            v-for="player in lapsedPlayers"
            :key="player.playerId"
            class="player-item lapsed q-mb-md q-pb-md"
            data-testid="lapsed-player"
          >
            <div class="text-body1" style="color: var(--text-primary)">
              {{ player.playerName }}
            </div>
            <div class="text-caption" style="color: var(--text-secondary)">
              {{ lastSessionLabel(player) }}
            </div>
          </div>
        </template>
      </template>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { getCoachRoster } from 'src/api/development.api'
import { useStartConversation } from 'src/composables/useStartConversation'

const { t } = useI18n()
const router = useRouter()
const { isStarting, startConversation } = useStartConversation()

const loading = ref(true)
const loadError = ref(false)
const incompleteProfile = ref(false)
const players = ref([])
// skillars-12-10 (D6): the server flags players whose coach-access window has closed.
const openPlayers = computed(() => players.value.filter((p) => !p.accessExpired))
const lapsedPlayers = computed(() => players.value.filter((p) => p.accessExpired))

onMounted(async () => {
  try {
    const res = await getCoachRoster()
    players.value = res.players
  } catch (err) {
    if (err.response?.status === 404) {
      incompleteProfile.value = true
    } else {
      loadError.value = true
    }
  } finally {
    loading.value = false
  }
})

// skillars-12-1 code review (2026-10-15) Patch, owner decision: the caption always shows the actual
// last-session date (or "never trained together") — the dormant q-badge alone carries the dormancy
// indicator. Previously this returned the same dormant-badge string as the caption, hiding the one
// fact (how overdue the player is) a coach needs most for a dormant row.
//
// skillars-12-6 AC9 (G3): the caption is now derived from the same server inputs the dormant badge is,
// so the two can no longer contradict each other. Rules, in order:
//   1. an attended session exists -> "Last trained N days ago" (server-computed, plural-aware);
//   2. no attended session and no upcoming booking -> the latest booking is in the past, so a booking
//      happened but no attended session was recorded ("Booked, but no attended session yet") - exactly
//      the case where the dormant badge could fire beside a "never trained" caption;
//   3. otherwise (upcoming booking, nothing attended) -> "You haven't trained together yet".
// Long values are serialised as JSON strings project-wide, hence the Number() coercion.
function lastSessionLabel(player) {
  if (player.daysSinceLastSession !== null && player.daysSinceLastSession !== undefined) {
    return t('coach.myPlayersLastTrainedDaysAgo', { count: Number(player.daysSinceLastSession) })
  }
  if (!player.hasUpcomingBooking) return t('coach.myPlayersBookedNoSessionYet')
  return t('coach.myPlayersNeverTrainedTogether')
}

// skillars-deferred-156 AC5: playerName no longer travels in the route's query string — a minor's
// name in the URL reaches browser history/bookmarks/referrers. CoachPlayerDetailPage.vue now reads
// it off the /exposure fetch response instead.
function goToPlayerDetail(player) {
  router.push({
    name: 'coach-player-detail',
    params: { playerId: player.playerId },
  })
}
</script>

<style lang="scss" scoped>
.my-players-page {
  max-width: 800px;
  margin: 0 auto;
}

.player-item {
  border-bottom: 1px solid var(--border-subtle);
}

.player-item.lapsed {
  opacity: 0.8;
}

.player-item.clickable {
  cursor: pointer;
}

.player-item:last-child {
  border-bottom: none;
}
</style>
