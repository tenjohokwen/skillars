<template>
  <q-page class="flex flex-center q-pa-md">
    <div class="glass-card--static q-pa-xl unsubscribe-card">
      <div class="text-page-title q-mb-md">{{ t('notifications.unsubscribe.title') }}</div>

      <div v-if="!token" class="text-meta" data-test="unsub-missing">
        {{ t('notifications.unsubscribe.missingToken') }}
      </div>

      <template v-else-if="state === 'done'">
        <div class="text-body q-mb-md" data-test="unsub-done">
          {{ t('notifications.unsubscribe.success', { category: categoryLabel }) }}
        </div>
        <div class="text-meta">{{ t('notifications.unsubscribe.manageHint') }}</div>
      </template>

      <template v-else>
        <div class="text-body q-mb-lg">{{ t('notifications.unsubscribe.prompt') }}</div>
        <q-banner
          v-if="state === 'error'"
          class="bg-negative text-white q-mb-md"
          rounded
          data-test="unsub-error"
        >
          {{ t('notifications.unsubscribe.invalid') }}
        </q-banner>
        <q-btn
          unelevated
          no-caps
          color="primary"
          :loading="state === 'submitting'"
          :label="t('notifications.unsubscribe.confirm')"
          data-test="unsub-confirm"
          @click="confirm"
        />
      </template>
    </div>
  </q-page>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { notificationApi } from 'src/api/notification.api'

// Notification centre (UI-gap chunk C9): landing page for the unsubscribe link in opt-out-able
// emails (<frontendUrl>/#/unsubscribe?token=...). Public — works logged in or out. It asks for one
// explicit click rather than unsubscribing on load: mail link scanners render pages too, and an
// auto-submit would opt users out who never clicked. Mailbox providers' native one-click button
// does not come here; it POSTs the List-Unsubscribe header URL directly.
const { t } = useI18n()
const route = useRoute()

const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const state = ref('idle')
const category = ref(null)

const CATEGORY_LABELS = {
  REMINDER: () => t('notifications.preferences.reminder.label'),
  DEVELOPMENT: () => t('notifications.preferences.development.label'),
}
const categoryLabel = computed(() => CATEGORY_LABELS[category.value]?.() ?? '')

async function confirm() {
  state.value = 'submitting'
  try {
    const res = await notificationApi.unsubscribe(token.value)
    category.value = res?.category ?? null
    state.value = 'done'
  } catch {
    state.value = 'error'
  }
}
</script>

<style lang="scss" scoped>
.unsubscribe-card {
  max-width: 520px;
  width: 100%;
}
</style>
