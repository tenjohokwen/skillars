import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { playerProfileApi } from 'src/api/playerProfile.api'
import { playerRegistrationApi } from 'src/api/playerRegistration.api'

export const usePlayerStore = defineStore('player', () => {
  const players = ref([])
  const activePlayerId = ref(null)
  const activePlayer = computed(() => players.value.find(p => p.id === activePlayerId.value) ?? null)
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
      selfPlayerIdRequest = playerRegistrationApi.getMyProfile()
        .then((profile) => {
          // Only apply the write if resetSelfPlayerId() hasn't fired since this
          // request started — otherwise a slow pre-logout fetch could resolve after
          // a different player has since logged in and repopulate the cache with
          // the wrong player's id.
          if (requestGeneration === selfPlayerIdGeneration && profile?.id != null) {
            selfPlayerId.value = profile.id
          }
          return profile?.id
        })
        .finally(() => {
          selfPlayerIdRequest = null
        })
    }
    return selfPlayerIdRequest
  }

  function resetSelfPlayerId() {
    selfPlayerId.value = null
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
