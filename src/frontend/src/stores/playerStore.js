import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { playerProfileApi } from 'src/api/playerProfile.api'
import { playerRegistrationApi } from 'src/api/playerRegistration.api'

export const usePlayerStore = defineStore('player', () => {
  const players = ref([])
  const activePlayerId = ref(null)
  const activePlayer = computed(
    () => players.value.find((p) => p.id === activePlayerId.value) ?? null,
  )
  const selfPlayerId = ref(null)
  // In-flight listProfiles() shared by concurrent fetchPlayers() callers (MainLayout's background
  // drawer load and ParentDashboardPage's onMounted both fire on a dashboard load), plus a
  // generation bumped by resetPlayers() so a request still in flight at logout cannot write the
  // logged-out parent's children back afterwards — same pattern as selfPlayerIdGeneration below.
  let playersRequest = null
  let playersGeneration = 0
  let selfPlayerIdRequest = null
  let selfPlayerIdGeneration = 0

  // selectFirst: false is for background loads (MainLayout's parent drawer) that must NOT pick an
  // active child — ParentDevelopmentPortalPage watches activePlayerId and navigates to it, so an
  // implicit "first child" selection racing a bookmarked child-2 URL would bounce the parent onto
  // child 1.
  async function fetchPlayers({ selectFirst = true } = {}) {
    const generation = playersGeneration
    if (!playersRequest) {
      const request = playerProfileApi.listProfiles().finally(() => {
        if (playersRequest === request) playersRequest = null
      })
      playersRequest = request
    }
    const data = await playersRequest
    if (generation !== playersGeneration) return
    players.value = data
    if (selectFirst && data.length > 0 && !activePlayerId.value) {
      activePlayerId.value = data[0].id
    }
  }

  // Navigation reachability: MainLayout's parent "My players" drawer section and the header
  // ParentChildSwitcher both render from `players`, which otherwise survived logout — a different
  // parent signing in on the same tab would briefly see the previous account's children.
  function resetPlayers() {
    playersGeneration++
    playersRequest = null
    players.value = []
    activePlayerId.value = null
  }

  function setActivePlayer(id) {
    activePlayerId.value = id
  }

  async function fetchSelfPlayerId() {
    if (selfPlayerId.value !== null) return selfPlayerId.value
    if (!selfPlayerIdRequest) {
      const requestGeneration = selfPlayerIdGeneration
      const request = playerRegistrationApi
        .getMyProfile()
        .then((profile) => {
          // Only apply the write if resetSelfPlayerId() hasn't fired since this
          // request started — otherwise a slow pre-logout fetch could resolve after
          // a different player has since logged in and repopulate the cache with
          // the wrong player's id.
          if (requestGeneration === selfPlayerIdGeneration && profile?.id != null) {
            selfPlayerId.value = profile.id
          }
          if (profile?.id == null) {
            throw new Error('Player profile response has no id')
          }
          // skillars-deferred-109 AC4.1: gate the RETURN on the generation too, not just the ref
          // write above. Returning profile.id unconditionally lets a fetchSelfPlayerId() call that
          // resetSelfPlayerId() superseded mid-flight still resolve with the prior account's id —
          // BookingRequestPage.vue feeds that straight into the booking submit payload
          // (cross-account misattribution). Return literal null (NOT selfPlayerId.value: a newer
          // generation's call may already have written the ref, which would hand this superseded
          // caller a DIFFERENT account's id). null is safe downstream — BookingRequestPage.vue
          // gates canSubmit on !!playerId.value and re-checks before submit; MainLayout guards the
          // nav link with `selfPlayerId.value ? … : null`.
          return requestGeneration === selfPlayerIdGeneration ? profile.id : null
        })
        .finally(() => {
          // Only clear the module-scoped reference if it still points at this
          // request — resetSelfPlayerId() may already have cleared it out-of-band
          // (and let a newer generation start its own request), in which case a
          // stale generation's late settlement must not clobber that newer
          // request's reference.
          if (selfPlayerIdRequest === request) selfPlayerIdRequest = null
        })
      selfPlayerIdRequest = request
    }
    return selfPlayerIdRequest
  }

  function resetSelfPlayerId() {
    selfPlayerId.value = null
    selfPlayerIdRequest = null
    selfPlayerIdGeneration++
  }

  return {
    players,
    activePlayerId,
    activePlayer,
    selfPlayerId,
    fetchPlayers,
    setActivePlayer,
    fetchSelfPlayerId,
    resetSelfPlayerId,
    resetPlayers,
  }
})
