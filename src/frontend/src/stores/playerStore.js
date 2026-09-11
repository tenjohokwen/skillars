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
  let selfPlayerIdRequest = null
  let selfPlayerIdGeneration = 0

  async function fetchPlayers() {
    const data = await playerProfileApi.listProfiles()
    players.value = data
    if (data.length > 0 && !activePlayerId.value) {
      activePlayerId.value = data[0].id
    }
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
  }
})
