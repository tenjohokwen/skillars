import { defineStore } from 'pinia'
import { ref } from 'vue'
import { notificationApi } from 'src/api/notification.api'

// Notification centre (UI-gap chunk C9): the header bell's state. The inbox page pages through
// the API directly; this store only holds the badge count and the bell's short "recent" list.
export const RECENT_SIZE = 5

export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)
  const recent = ref([])
  const loadingRecent = ref(false)

  async function refreshUnreadCount() {
    try {
      const res = await notificationApi.unreadCount()
      unreadCount.value = Number(res?.count) || 0
    } catch {
      // Non-fatal: the badge keeps its last value; a 401 is handled by the axios interceptor.
    }
  }

  async function loadRecent() {
    loadingRecent.value = true
    try {
      const res = await notificationApi.list(0, RECENT_SIZE)
      recent.value = res?.content ?? []
    } catch {
      recent.value = []
    } finally {
      loadingRecent.value = false
    }
  }

  // Optimistic: the backend UPDATE is idempotent and recipient-scoped, so re-marking is harmless.
  // Never throws — a failed mark-read must not stop the caller from navigating to the deep link;
  // the item is reverted to unread and the badge re-synced from the server.
  async function markRead(notification) {
    if (!notification || notification.readAt) return
    notification.readAt = new Date().toISOString()
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    try {
      await notificationApi.markRead(notification.id)
    } catch {
      notification.readAt = null
    } finally {
      await refreshUnreadCount()
    }
  }

  /** @returns {Promise<boolean>} whether the server accepted it; never throws. */
  async function markAllRead() {
    try {
      await notificationApi.markAllRead()
    } catch {
      await refreshUnreadCount()
      return false
    }
    const now = new Date().toISOString()
    recent.value.forEach((n) => {
      if (!n.readAt) n.readAt = now
    })
    unreadCount.value = 0
    return true
  }

  function reset() {
    unreadCount.value = 0
    recent.value = []
  }

  return {
    unreadCount,
    recent,
    loadingRecent,
    refreshUnreadCount,
    loadRecent,
    markRead,
    markAllRead,
    reset,
  }
})
