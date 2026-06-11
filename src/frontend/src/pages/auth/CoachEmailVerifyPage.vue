<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
      </div>

      <div class="glass-card--static auth-card">

        <div v-if="isVerifying" class="flex flex-center q-pa-xl">
          <q-spinner-dots size="48px" style="color: var(--accent-primary)" />
        </div>

        <template v-else-if="hasError">
          <div class="text-section-title q-mb-xs">{{ t('error.verificationFailed') }}</div>


          <q-banner class="auth-banner auth-banner--error q-mb-md" rounded>
            {{ errorMessage }}
            <template v-if="helpCode">
              <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <div v-if="canResend" class="text-center q-mt-lg">
            <q-btn
              flat no-caps
              :label="t('auth.coach.resendEmail')"
              style="color: var(--accent-primary)"
              @click="handleResendFromVerify"
            />
          </div>
        </template>

        <template v-else>
          <div class="flex flex-center q-pa-xl">
            <q-spinner-dots size="48px" style="color: var(--accent-success)" />
          </div>
        </template>

      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { coachRegistrationApi } from 'src/api/coachRegistration.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const { setError, hasError, errorMessage, helpCode } = useErrorHandler()

const isVerifying = ref(true)
const canResend = ref(false)

onMounted(async () => {
  const emailFromUrl = route.query.email
  if (emailFromUrl) {
    sessionStorage.setItem('pendingCoachEmail', emailFromUrl)
  }

  const token = route.query.token
  if (!token) {
    isVerifying.value = false
    setError({ response: { data: { message: 'No verification token found. Please check your email link.' } } })
    return
  }
  try {
    const response = await coachRegistrationApi.verifyEmail(token)
    const { userId } = response.data
    isVerifying.value = false
    router.push({ path: '/coach/verify-phone', query: { userId } })
  } catch (err) {
    isVerifying.value = false
    canResend.value = err.response?.data?.canResend === true
    setError(err)
  }
})

async function handleResendFromVerify() {
  router.push({ path: '/coach/email-pending', query: { email: sessionStorage.getItem('pendingCoachEmail') || undefined } })
}
</script>

<style lang="scss" scoped>
.auth-brand { text-align: center; }
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card { padding: 32px; }
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error { background: rgba(255, 95, 122, 0.12) !important; color: var(--accent-danger) !important; }
}
</style>
