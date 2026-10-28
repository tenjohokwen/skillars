<template>
  <div class="glass-card profile-section" data-test="notification-preferences">
    <div class="text-label q-mb-sm">{{ t('notifications.preferences.title') }}</div>
    <div class="text-meta q-mb-lg">{{ t('notifications.preferences.description') }}</div>

    <div v-if="loadError" class="text-meta" style="color: var(--accent-danger)">
      {{ t('notifications.preferences.loadError') }}
    </div>

    <div v-for="pref in preferences" :key="pref.category" class="profile-row">
      <div>
        <div class="text-label">{{ labelFor(pref.category).label }}</div>
        <div class="text-meta q-mt-xs">{{ labelFor(pref.category).description }}</div>
      </div>
      <q-toggle
        :model-value="pref.emailEnabled"
        :disable="saving === pref.category"
        :aria-label="labelFor(pref.category).label"
        :data-test="`pref-toggle-${pref.category}`"
        @update:model-value="(v) => toggle(pref, v)"
      />
    </div>

    <div class="text-meta q-mt-md">{{ t('notifications.preferences.transactionalNote') }}</div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { notificationApi } from 'src/api/notification.api'

// Notification centre (UI-gap chunk C9): per-category email opt-out on the Profile page. The
// backend only returns opt-out-able categories; transactional / security email is always sent.
const { t } = useI18n()
const $q = useQuasar()

const preferences = ref([])
const loadError = ref(false)
const saving = ref(null)

const LABELS = {
  REMINDER: () => ({
    label: t('notifications.preferences.reminder.label'),
    description: t('notifications.preferences.reminder.description'),
  }),
  DEVELOPMENT: () => ({
    label: t('notifications.preferences.development.label'),
    description: t('notifications.preferences.development.description'),
  }),
}

function labelFor(category) {
  return LABELS[category]?.() ?? { label: category, description: '' }
}

async function load() {
  try {
    preferences.value = (await notificationApi.getPreferences()) ?? []
  } catch {
    loadError.value = true
  }
}

async function toggle(pref, emailEnabled) {
  const previous = pref.emailEnabled
  pref.emailEnabled = emailEnabled
  saving.value = pref.category
  try {
    await notificationApi.updatePreference(pref.category, emailEnabled)
    $q.notify({ type: 'positive', message: t('notifications.preferences.saved') })
  } catch {
    pref.emailEnabled = previous
    $q.notify({ type: 'negative', message: t('notifications.preferences.saveError') })
  } finally {
    saving.value = null
  }
}

onMounted(load)
</script>

<style lang="scss" scoped>
// Mirrors ProfilePage's own (scoped, so not inherited) section/row styles.
.profile-section {
  padding: 28px;
}

.profile-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 16px 0;
  border-bottom: 1px solid var(--border-soft);
}
</style>
