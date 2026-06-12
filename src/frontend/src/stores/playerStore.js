import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { playerProfileApi } from 'src/api/playerProfile.api'

export const usePlayerStore = defineStore('player', () => {
  const players = ref([])
  const activePlayerId = ref(null)
  const activePlayer = computed(() => players.value.find(p => p.id === activePlayerId.value) ?? null)

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

  return { players, activePlayerId, activePlayer, fetchPlayers, setActivePlayer }
})
