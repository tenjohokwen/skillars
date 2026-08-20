<template>
  <q-page class="flex flex-center">
    <q-spinner-dots size="48px" style="color: var(--accent-primary)" />
  </q-page>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'
import { usePlayerStore } from 'src/stores/playerStore'

const router = useRouter()
const playerStore = usePlayerStore()
const $q = useQuasar()
const { t } = useI18n()

onMounted(async () => {
  let id
  try {
    id = await playerStore.fetchSelfPlayerId()
  } catch (err) {
    // A 404 is the expected, silent case: verified but never finished the profile-builder step.
    // Anything else (network/500) is surfaced so the player knows why they landed here.
    if (err.response?.status !== 404) {
      $q.notify({ type: 'negative', message: t('common.errorGeneric') })
    }
    router.replace('/player/profile-builder')
    return
  }
  // No .catch() would leave a rejected navigation (e.g. a stale-chunk load failure) fully
  // unhandled, stranding the player on the spinner indefinitely — fall back to the same safe
  // landing spot the catch branch above uses.
  router.replace(`/player/locker-room/${id}`).catch(() => router.replace('/player/profile-builder'))
})
</script>
