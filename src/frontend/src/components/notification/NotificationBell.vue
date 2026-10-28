<template>
  <q-btn
    flat
    round
    dense
    icon="notifications"
    class="header-btn"
    :aria-label="t('notifications.bellLabel')"
    data-test="notification-bell"
  >
    <q-badge
      v-if="store.unreadCount > 0"
      floating
      rounded
      color="red"
      data-test="notification-badge"
    >
      {{ badgeLabel }}
    </q-badge>
    <q-menu anchor="bottom right" self="top right" class="notification-menu" @before-show="onOpen">
      <div class="notification-menu__header">
        <span class="text-label">{{ t('notifications.title') }}</span>
        <q-btn
          v-if="store.unreadCount > 0"
          flat
          dense
          no-caps
          size="sm"
          :label="t('notifications.markAllRead')"
          data-test="bell-mark-all"
          @click="store.markAllRead()"
        />
      </div>
      <q-list class="notification-menu__list">
        <q-item v-if="store.loadingRecent && !store.recent.length">
          <q-item-section class="text-meta">{{ t('common.loading') }}</q-item-section>
        </q-item>
        <q-item v-else-if="!store.recent.length">
          <q-item-section class="text-meta" data-test="bell-empty">
            {{ t('notifications.empty') }}
          </q-item-section>
        </q-item>
        <NotificationItem
          v-for="n in store.recent"
          :key="n.id"
          v-close-popup
          :notification="n"
          @open="openNotification"
        />
      </q-list>
      <q-separator />
      <q-item v-close-popup clickable to="/notifications" data-test="bell-view-all">
        <q-item-section class="text-center">{{ t('notifications.viewAll') }}</q-item-section>
      </q-item>
    </q-menu>
  </q-btn>
</template>

<script setup>
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import NotificationItem from 'src/components/notification/NotificationItem.vue'
import { useNotificationStore } from 'src/stores/notification.store'
import { safeDeepLink } from 'src/utils/notificationText'

// Notification centre (UI-gap chunk C9): header bell, rendered by MainLayout for every
// authenticated role. The unread count is refreshed on mount, on every route change and whenever
// the window regains focus. Messaging's SSE stream is per-conversation (opened only on a chat
// page), so there is no user-level push channel to reuse, and none is built just for this.
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useNotificationStore()

const badgeLabel = computed(() => (store.unreadCount > 99 ? '99+' : String(store.unreadCount)))

function onOpen() {
  store.loadRecent()
}

async function openNotification(notification) {
  await store.markRead(notification)
  const target = safeDeepLink(notification.deepLink)
  if (target) router.push(target)
}

function onFocus() {
  store.refreshUnreadCount()
}

// path, not fullPath: query-only changes (paging, filters) must not each cost a request.
watch(
  () => route.path,
  () => store.refreshUnreadCount(),
)

onMounted(() => {
  store.refreshUnreadCount()
  window.addEventListener('focus', onFocus)
})

onUnmounted(() => {
  window.removeEventListener('focus', onFocus)
  // Logout unmounts the bell (v-if on isAuthenticated); never leak one user's count to the next.
  store.reset()
})
</script>

<style lang="scss" scoped>
.notification-menu__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  gap: 12px;
}

.notification-menu__list {
  min-width: 300px;
  max-width: 380px;
  max-height: 60vh;
  overflow-y: auto;
}
</style>
