<template>
  <div
    v-if="pitchTimezone && pitchTimezone !== authStore.browserTimezone && !authStore.timezoneNoticeDismissed"
    class="timezone-notice"
  >
    <span class="timezone-notice__message">
      {{ t('booking.timezone.noticeDiffers', { browser: authStore.browserTimezone, pitch: pitchTimezone }) }}
    </span>
    <q-btn flat dense round icon="close" class="timezone-notice__close" @click="authStore.dismissTimezoneNotice()" />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from 'src/stores/auth.store'

defineProps({
  pitchTimezone: { type: String, required: true },
})

const { t } = useI18n()
const authStore = useAuthStore()

let timer = null

onMounted(() => {
  timer = setTimeout(() => authStore.dismissTimezoneNotice(), 8000)
})

onUnmounted(() => {
  clearTimeout(timer)
})
</script>

<style lang="scss" scoped>
.timezone-notice {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: var(--accent-primary);
  color: #fff;
  font-size: 14px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);

  &__message {
    flex: 1;
  }

  &__close {
    color: #fff;
    margin-left: 12px;
  }
}
</style>
