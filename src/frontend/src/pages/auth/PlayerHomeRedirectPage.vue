<template>
  <q-page class="flex flex-center">
    <q-spinner-dots size="48px" style="color: var(--accent-primary)" />
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { playerRegistrationApi } from 'src/api/playerRegistration.api'

const router = useRouter()

onMounted(async () => {
  try {
    const profile = await playerRegistrationApi.getMyProfile()
    router.replace(`/player/locker-room/${profile.id}`)
  } catch {
    // No profile yet (e.g. verified but never finished the profile-builder step) — send them there.
    router.replace('/player/profile-builder')
  }
})
</script>
