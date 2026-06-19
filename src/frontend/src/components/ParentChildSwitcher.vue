<template>
  <div v-if="playerStore.players.length > 0">
    <q-btn flat no-caps class="switcher-btn" @click="drawerOpen = true">
      <div class="switcher-avatar">{{ activeInitials }}</div>
      <span class="switcher-name q-ml-xs">{{ playerStore.activePlayer?.name || t('player.switcher.title') }}</span>
      <q-icon name="expand_more" size="16px" class="q-ml-xs" />
    </q-btn>

    <q-dialog v-model="drawerOpen" position="bottom">
      <q-card class="switcher-drawer">
        <q-card-section>
          <div class="text-section-title q-mb-md">{{ t('player.switcher.title') }}</div>

          <q-list>
            <q-item
              v-for="player in playerStore.players"
              :key="player.id"
              clickable
              :active="player.id === playerStore.activePlayerId"
              @click="selectPlayer(player.id)"
              class="player-row"
            >
              <q-item-section avatar>
                <div class="player-avatar">{{ initials(player.name) }}</div>
              </q-item-section>
              <q-item-section>
                <q-item-label>{{ player.name }}</q-item-label>
                <q-item-label caption>
                  <q-badge color="primary" :label="player.ageTierLabel" />
                </q-item-label>
              </q-item-section>
              <q-item-section side v-if="player.id === playerStore.activePlayerId">
                <q-icon name="check" style="color: var(--accent-primary)" />
              </q-item-section>
            </q-item>
          </q-list>
        </q-card-section>
      </q-card>
    </q-dialog>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { usePlayerStore } from 'src/stores/playerStore'

const { t } = useI18n()
const router = useRouter()
const playerStore = usePlayerStore()
const drawerOpen = ref(false)

const activeInitials = computed(() => initials(playerStore.activePlayer?.name || ''))

function initials(name) {
  if (!name) return '?'
  return name.split(' ').map(p => p[0]).join('').toUpperCase().slice(0, 2)
}

function selectPlayer(id) {
  playerStore.setActivePlayer(id)
  drawerOpen.value = false
  router.push({ name: 'parent-development', params: { playerId: id } })
}
</script>

<style lang="scss" scoped>
.switcher-btn {
  color: var(--text-secondary) !important;
  border-radius: 10px !important;
  padding: 4px 8px;
  &:hover {
    background: var(--surface-glass-hover) !important;
    color: var(--text-primary) !important;
  }
}
.switcher-avatar, .player-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--accent-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  font-family: 'Inter', sans-serif;
}
.switcher-name {
  font-family: 'Inter', sans-serif;
  font-size: 14px;
  font-weight: 500;
}
.switcher-drawer {
  width: 100%;
  max-width: 480px;
  margin: 0 auto;
  border-radius: 20px 20px 0 0;
  background: var(--bg-secondary);
  padding: 8px 0 16px;
}
.player-row {
  border-radius: 12px;
  margin: 2px 8px;
  &.q-item--active {
    background: var(--nav-active-bg) !important;
  }
}
</style>
