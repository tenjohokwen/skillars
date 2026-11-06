<template>
  <q-page class="flex flex-center q-pa-md">
    <div class="glass-card--static q-pa-xl email-change-card">
      <div class="text-page-title q-mb-md">{{ t('emailChangeLink.confirmTitle') }}</div>

      <div v-if="!token" class="text-meta" data-test="ec-missing">
        {{ t('emailChangeLink.missingToken') }}
      </div>

      <template v-else-if="state === 'done'">
        <div class="text-body q-mb-lg" data-test="ec-done">
          {{ t('emailChangeLink.confirmed') }}
        </div>
        <q-btn
          unelevated
          no-caps
          color="primary"
          :to="{ path: '/login' }"
          :label="t('emailChangeLink.signIn')"
          data-test="ec-sign-in"
        />
      </template>

      <template v-else>
        <div class="text-body q-mb-lg">{{ t('emailChangeLink.confirmPrompt') }}</div>
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
          :label="t('emailChangeLink.confirmButton')"
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

// skillars-deferred-172: landing page for the confirmation link mailed to the NEW address
// (<frontendUrl>/#/email-change/confirm?token=...). Public. One explicit click, never on load: mail link
// scanners render pages too. A confirm revokes every session of the account on the server, so this browser's
// own session is ended locally as well; otherwise the guest-only /login route would bounce the user back in.
const { t } = useI18n()
const route = useRoute()
const authStore = useAuthStore()
const { endSessionLocally } = useSession()

const token = computed(() => (typeof route.query.token === 'string' ? route.query.token : ''))
const state = ref('idle')

async function submit() {
  state.value = 'submitting'
  try {
    await emailChangeApi.confirm(token.value)
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
