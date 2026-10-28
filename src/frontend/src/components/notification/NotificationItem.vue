<template>
  <q-item
    clickable
    class="notification-item"
    :class="{ 'notification-item--unread': !notification.readAt }"
    @click="$emit('open', notification)"
  >
    <q-item-section avatar>
      <q-icon :name="icon" class="notification-item__icon" />
    </q-item-section>
    <q-item-section>
      <q-item-label class="notification-item__text">{{ text }}</q-item-label>
      <q-item-label caption>{{ when }}</q-item-label>
    </q-item-section>
    <q-item-section v-if="!notification.readAt" side>
      <span class="notification-item__dot" :aria-label="t('notifications.unread')" />
    </q-item-section>
  </q-item>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { notificationText, CATEGORY_ICONS } from 'src/utils/notificationText'

const props = defineProps({
  notification: { type: Object, required: true },
})
defineEmits(['open'])

const i18n = useI18n()
const { t, locale } = i18n

const icon = computed(() => CATEGORY_ICONS[props.notification.category] || 'notifications')
const text = computed(() =>
  notificationText({ t, te: i18n.te, locale: locale.value }, props.notification),
)
const when = computed(() => {
  const d = new Date(props.notification.createdAt)
  return Number.isNaN(d.getTime())
    ? ''
    : d.toLocaleString(locale.value, { dateStyle: 'medium', timeStyle: 'short' })
})
</script>

<style lang="scss" scoped>
.notification-item {
  border-radius: 8px;
}

.notification-item--unread .notification-item__text {
  font-weight: 600;
}

.notification-item__icon {
  color: var(--text-secondary);
}

.notification-item__dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--accent-primary);
}
</style>
