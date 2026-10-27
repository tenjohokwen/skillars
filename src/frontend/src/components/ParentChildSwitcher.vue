<template>
  <div v-if="playerStore.players.length > 0">
    <q-btn flat no-caps class="switcher-btn" @click="drawerOpen = true">
      <div class="switcher-avatar">{{ activeInitials }}</div>
      <span class="switcher-name q-ml-xs">{{
        playerStore.activePlayer?.name || t('player.switcher.title')
      }}</span>
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
import { useRoute, useRouter } from 'vue-router'
import { usePlayerStore } from 'src/stores/playerStore'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const playerStore = usePlayerStore()
const drawerOpen = ref(false)

const activeInitials = computed(() => initials(playerStore.activePlayer?.name || ''))

function initials(name) {
  if (!name) return '?'
  return name
    .split(' ')
    .map((p) => p[0])
    .join('')
    .toUpperCase()
    .slice(0, 2)
}

// Routes that already re-load when only :playerId changes (they watch route.params.playerId), so
// switching child can keep the parent on the same page. Every other page — including per-child
// pages that read the param once at setup — lands on the child hub instead, which links onward.
const CONTEXT_PRESERVING_ROUTES = ['parent-development', 'parent-player-hub']

async function selectPlayer(id) {
  drawerOpen.value = false
  const name = CONTEXT_PRESERVING_ROUTES.includes(route.name) ? route.name : 'parent-player-hub'
  // Navigate FIRST, then mark the child active: ParentDevelopmentPortalPage watches
  // activePlayerId and pushes to it whenever it differs from the route param, so setting it
  // before the route had changed made that watch fire a second, competing push that cancelled
  // this one. Once the route already names the child, the watch sees no difference.
  try {
    await router.push({ name, params: { playerId: id } })
  } finally {
    playerStore.setActivePlayer(id)
  }
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
.switcher-avatar,
.player-avatar {
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
