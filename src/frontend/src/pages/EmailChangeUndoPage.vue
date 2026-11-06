<template>
  <q-page class="flex flex-center q-pa-md">
    <div class="glass-card--static q-pa-xl email-change-card">
      <div class="text-page-title q-mb-md">{{ t('emailChangeLink.undoTitle') }}</div>

      <div v-if="!token" class="text-meta" data-test="ec-missing">
        {{ t('emailChangeLink.missingToken') }}
      </div>

      <template v-else-if="state === 'done'">
        <div class="text-body q-mb-lg" data-test="ec-done">
          {{
            passwordResetSent ? t('emailChangeLink.undone') : t('emailChangeLink.undoneSuspended')
          }}
        </div>
      </template>

      <template v-else>
        <div class="text-body q-mb-lg">{{ t('emailChangeLink.undoPrompt') }}</div>
        <q-banner
          v-if="state === 'error'"
          class="bg-negative text-white q-mb-md"
          rounded
          data-test="ec-error"
        >
          {{ t('emailChangeLink.invalid') }}
        </q-banner>
        <q-btn
          unelevated
          no-caps
          color="primary"
          :loading="state === 'submitting'"
          :label="t('emailChangeLink.undoButton')"
          data-test="ec-submit"
          @click="submit"
        />
      </template>
    </div>
  </q-page>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { emailChangeApi } from 'src/api/emailChange.api'
import { useAuthStore } from 'src/stores/auth.store'
import { useSession } from 'src/composables/useSession'

// skillars-deferred-172: landing page for the undo link mailed to the OLD address
// (<frontendUrl>/#/email-change/undo?token=...). Public, one explicit click. The undo revokes every session
// and, unless the account is suspended, mails a password-reset link to the restored address; this browser's
// session is ended locally so that the guest-only /reset-password route lets the link through.
const { t } = useI18n()
const route = useRoute()
const authStore = useAuthStore()
const { endSessionLocally } = useSession()

const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const state = ref('idle')
const passwordResetSent = ref(true)

async function submit() {
  state.value = 'submitting'
  try {
    const res = await emailChangeApi.undo(token.value)
    passwordResetSent.value = res?.passwordResetSent !== false
    if (authStore.isAuthenticated) {
      await endSessionLocally()
    }
    state.value = 'done'
  } catch {
    state.value = 'error'
  }
}
</script>

<style lang="scss" scoped>
.email-change-card {
  max-width: 520px;
  width: 100%;
}
</style>
