<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.parent.emailPendingTitle') }}</div>
        <div class="text-meta q-mb-lg">{{ t('auth.parent.emailPendingBody', { email: pendingEmail }) }}</div>

        <q-banner
          v-if="!pendingEmail"
          class="auth-banner auth-banner--error q-mb-md"
          rounded
        >
          {{ t('error.generic') }}
          <router-link to="/parent-register" class="auth-link q-ml-xs">{{ t('auth.parent.registerTitle') }}</router-link>
        </q-banner>

        <q-banner
          v-if="hasError"
          class="auth-banner auth-banner--error q-mb-md"
          rounded
        >
          {{ errorMessage }}
        </q-banner>

        <q-banner
          v-if="resendSuccess"
          class="auth-banner auth-banner--success q-mb-md"
          rounded
        >
          {{ t('auth.parent.resendEmail') }} ✓
        </q-banner>

        <div class="text-center q-mt-lg">
          <q-btn
            flat no-caps
            :label="resendCooldown > 0
              ? t('auth.parent.resendCooldown', { seconds: resendCooldown })
              : t('auth.parent.resendEmail')"
            :disable="resendCooldown > 0 || isResending || !pendingEmail"
            style="color: var(--accent-primary)"
            @click="handleResend"
          />
        </div>

        <div class="text-center q-mt-md">
          <router-link to="/login" class="auth-link">
            &larr; {{ t('auth.parent.signInInstead') }}
          </router-link>
        </div>

      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { parentRegistrationApi } from 'src/api/parentRegistration.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const { t } = useI18n()
const { setError, clearError, hasError, errorMessage } = useErrorHandler()

const pendingEmail = ref(sessionStorage.getItem('pendingParentEmail') || '')
const resendCooldown = ref(0)
const isResending = ref(false)
const resendSuccess = ref(false)
const cooldownTimer = ref(null)

onUnmounted(() => { if (cooldownTimer.value) clearInterval(cooldownTimer.value) })

async function handleResend() {
  if (!pendingEmail.value) return
  clearError()
  isResending.value = true
  resendSuccess.value = false
  try {
    await parentRegistrationApi.resendVerification(pendingEmail.value)
    resendSuccess.value = true
    startCooldown()
  } catch (err) {
    setError(err)
  } finally {
    isResending.value = false
  }
}

function startCooldown() {
  resendCooldown.value = 60
  cooldownTimer.value = setInterval(() => {
    resendCooldown.value--
    if (resendCooldown.value <= 0) {
      clearInterval(cooldownTimer.value)
      cooldownTimer.value = null
    }
  }, 1000)
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
.auth-link {
  color: var(--accent-primary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  &:hover { opacity: 0.8; }
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error { background: rgba(255, 95, 122, 0.12) !important; color: var(--accent-danger) !important; }
  &--success { background: rgba(0, 200, 83, 0.12) !important; color: var(--accent-success) !important; }
}
</style>
