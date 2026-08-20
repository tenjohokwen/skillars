<template>
  <q-page class="flex flex-center">
    <q-spinner-dots size="48px" style="color: var(--accent-primary)" />
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { usePlayerStore } from 'src/stores/playerStore'

const router = useRouter()
const playerStore = usePlayerStore()

onMounted(async () => {
  try {
    const id = await playerStore.fetchSelfPlayerId()
    router.replace(`/player/locker-room/${id}`)
  } catch {
    // No profile yet (e.g. verified but never finished the profile-builder step) — send them there.
    router.replace('/player/profile-builder')
  }
})
</script>
