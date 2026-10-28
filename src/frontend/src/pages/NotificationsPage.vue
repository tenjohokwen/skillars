<template>
  <q-page>
    <div class="app-page fade-in">
      <div class="page-header q-mb-xl row items-center justify-between">
        <div>
          <div class="text-page-title">{{ t('notifications.title') }}</div>
          <div class="text-meta">{{ t('notifications.subtitle') }}</div>
        </div>
        <q-btn
          v-if="hasUnread"
          flat
          no-caps
          icon="done_all"
          :label="t('notifications.markAllRead')"
          data-test="page-mark-all"
          @click="onMarkAllRead"
        />
      </div>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('notifications.loadError') }}
      </q-banner>

      <div class="glass-card--static q-pa-sm">
        <div v-if="loading && !items.length" class="flex flex-center q-py-xl">
          <q-spinner-dots size="40px" style="color: var(--accent-primary)" />
        </div>
        <div v-else-if="!items.length" class="text-meta q-pa-lg text-center" data-test="page-empty">
          {{ t('notifications.empty') }}
        </div>
        <q-list v-else separator>
          <NotificationItem
            v-for="n in items"
            :key="n.id"
            :notification="n"
            @open="openNotification"
          />
        </q-list>
      </div>

      <div v-if="totalPages > 1" class="flex flex-center q-mt-md">
        <q-pagination
          v-model="currentPage"
          :max="totalPages"
          :max-pages="7"
          direction-links
          @update:model-value="load"
        />
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import NotificationItem from 'src/components/notification/NotificationItem.vue'
import { notificationApi } from 'src/api/notification.api'
import { useNotificationStore } from 'src/stores/notification.store'
import { safeDeepLink } from 'src/utils/notificationText'

const PAGE_SIZE = 20

const { t } = useI18n()
const router = useRouter()
const $q = useQuasar()
const store = useNotificationStore()

const items = ref([])
const currentPage = ref(1)
const totalPages = ref(0)
const loading = ref(false)
const loadError = ref(false)

const hasUnread = computed(() => items.value.some((n) => !n.readAt) || store.unreadCount > 0)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    const res = await notificationApi.list(currentPage.value - 1, PAGE_SIZE)
    items.value = res?.content ?? []
    totalPages.value = res?.totalPages ?? 0
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function openNotification(notification) {
  await store.markRead(notification)
  const target = safeDeepLink(notification.deepLink)
  if (target) router.push(target)
}

async function onMarkAllRead() {
  if (!(await store.markAllRead())) {
    $q.notify({ type: 'negative', message: t('notifications.markAllReadError') })
    return
  }
  const now = new Date().toISOString()
  items.value.forEach((n) => {
    if (!n.readAt) n.readAt = now
  })
}

onMounted(load)
</script>
