<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.parent.phoneVerifyTitle') }}</div>
        <div class="text-meta q-mb-lg">{{ t('auth.parent.phoneVerifySubtitle') }}</div>

        <div class="otp-row q-mb-lg">
          <q-input
            v-for="(_, index) in digits"
            :key="index"
            :ref="el => { if (el) otpInputs[index] = el }"
            v-model="digits[index]"
            maxlength="1"
            class="otp-digit"
            input-class="text-center"
            outlined
            :autofocus="index === 0"
            @update:model-value="onDigitInput(index)"
            @keydown="onKeyDown(index, $event)"
            @paste="onPaste"
          />
        </div>

        <div v-if="isSubmitting" class="flex flex-center q-mb-md">
          <q-spinner-dots size="32px" style="color: var(--accent-primary)" />
        </div>

        <q-banner
          v-if="hasError"
          class="auth-banner auth-banner--error q-mb-md"
          rounded
        >
          {{ errorMessage }}
          <template v-if="helpCode">
            <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </q-banner>

        <div class="text-center q-mt-md">
          <q-btn
            flat no-caps dense
            class="auth-link q-mr-md"
            :label="resendCooldown > 0 ? t('auth.parent.resendCooldown', { seconds: resendCooldown }) : t('auth.parent.resendOtp')"
            :disable="resendCooldown > 0 || isResending"
            :loading="isResending"
            @click="handleResendOtp"
          />
          <router-link to="/login" class="auth-link">
            &larr; {{ t('auth.parent.signInInstead') }}
          </router-link>
        </div>

      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { parentRegistrationApi } from 'src/api/parentRegistration.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const { setError, clearError, hasError, errorMessage, helpCode } = useErrorHandler()

const digits = ref(['', '', '', '', '', ''])
const otpInputs = ref([])
const isSubmitting = ref(false)
const isResending = ref(false)
const resendCooldown = ref(0)
let cooldownTimer = null

function startCooldown() {
  resendCooldown.value = 60
  cooldownTimer = setInterval(() => {
    resendCooldown.value--
    if (resendCooldown.value <= 0) { clearInterval(cooldownTimer); cooldownTimer = null }
  }, 1000)
}

onUnmounted(() => { if (cooldownTimer) clearInterval(cooldownTimer) })

async function handleResendOtp() {
  if (!userId.value) return
  isResending.value = true
  try {
    await parentRegistrationApi.resendOtp(userId.value)
    startCooldown()
  } catch {
    // silent — no account enumeration
  } finally {
    isResending.value = false
  }
}

const userId = computed(() => route.query.userId ? Number(route.query.userId) : null)

onMounted(() => {
  if (userId.value === null) {
    router.push('/parent-register')
    return
  }
  setTimeout(() => otpInputs.value[0]?.focus(), 100)
})

function onDigitInput(index) {
  const value = digits.value[index]
  if (value && !/^\d$/.test(value)) { digits.value[index] = ''; return }
  if (value && index < 5) setTimeout(() => otpInputs.value[index + 1]?.focus(), 10)
  if (digits.value.every(d => /^\d$/.test(d))) handleSubmit()
}

function onKeyDown(index, event) {
  if (event.key === 'Backspace' && !digits.value[index] && index > 0) {
    event.preventDefault()
    otpInputs.value[index - 1]?.focus()
    digits.value[index - 1] = ''
  }
  if (event.key === 'ArrowLeft' && index > 0) { event.preventDefault(); otpInputs.value[index - 1]?.focus() }
  if (event.key === 'ArrowRight' && index < 5) { event.preventDefault(); otpInputs.value[index + 1]?.focus() }
}

function onPaste(event) {
  event.preventDefault()
  const pastedText = event.clipboardData?.getData('text') || ''
  const digitsOnly = pastedText.replace(/\D/g, '').slice(0, 6)
  for (let i = 0; i < 6; i++) digits.value[i] = digitsOnly[i] || ''
  if (digitsOnly.length >= 6) handleSubmit()
  else if (digitsOnly.length > 0) otpInputs.value[digitsOnly.length]?.focus()
}

async function handleSubmit() {
  if (isSubmitting.value) return
  const otp = digits.value.join('')
  if (otp.length !== 6) return
  clearError()
  isSubmitting.value = true
  try {
    await parentRegistrationApi.verifyPhone({ userId: userId.value, otp })
    router.push('/parent/create-player')
  } catch (err) {
    setError(err)
    digits.value = ['', '', '', '', '', '']
    setTimeout(() => otpInputs.value[0]?.focus(), 100)
  } finally {
    isSubmitting.value = false
  }
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
}
.otp-row {
  display: flex;
  justify-content: center;
  gap: 10px;
}
.otp-digit {
  width: 52px !important;
  :deep(input) {
    text-align: center;
    font-size: 24px;
    font-weight: 700;
    font-family: 'Inter', sans-serif;
  }
  :deep(.q-field__control) {
    height: 60px;
  }
}
</style>
