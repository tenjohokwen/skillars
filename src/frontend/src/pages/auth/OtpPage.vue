<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">Two-factor authentication</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.enterOtp') }}</div>
        <div class="text-meta q-mb-lg">{{ t('auth.otpSent') }} — code expires in 30 minutes.</div>

        <!-- OTP digit inputs -->
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

        <!-- Loading -->
        <div v-if="isSubmitting" class="flex flex-center q-mb-md">
          <q-spinner-dots size="32px" style="color: var(--accent-primary)" />
        </div>

        <!-- Error -->
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

        <!-- Resend -->
        <div class="text-center q-mt-lg">
          <div class="text-meta q-mb-sm">Didn't receive the code?</div>
          <q-btn
            flat no-caps
            :label="resendCooldown > 0 ? `${t('auth.resendOtp')} (${resendCooldown}s)` : t('auth.resendOtp')"
            :disable="resendCooldown > 0"
            style="color: var(--accent-primary)"
            @click="handleResend"
          />
        </div>

        <div class="text-center q-mt-md">
          <router-link to="/login" class="auth-link">
            &larr; {{ t('common.back') }} {{ t('auth.login') }}
          </router-link>
        </div>

      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { authApi } from 'src/api/auth.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';
import { useSession } from 'src/composables/useSession';

const router = useRouter();
const route = useRoute();
const { t } = useI18n();
const { setError, clearError, hasError, errorMessage, helpCode } = useErrorHandler();
const { initSession } = useSession();

const digits = ref(['', '', '', '', '', '']);
const otpInputs = ref([]);
const isSubmitting = ref(false);
const resendCooldown = ref(0);
let cooldownTimer = null;

const loginInfoId = computed(() => route.query.id || '');
const redirectPath = computed(() => route.query.redirect || '/dashboard');

onMounted(() => { setTimeout(() => otpInputs.value[0]?.focus(), 100); });
onUnmounted(() => { if (cooldownTimer) clearInterval(cooldownTimer); });

function onDigitInput(index) {
  const value = digits.value[index];
  if (value && !/^\d$/.test(value)) { digits.value[index] = ''; return; }
  if (value && index < 5) setTimeout(() => otpInputs.value[index + 1]?.focus(), 10);
  if (digits.value.every(d => /^\d$/.test(d))) handleSubmit();
}

function onKeyDown(index, event) {
  if (event.key === 'Backspace' && !digits.value[index] && index > 0) {
    event.preventDefault();
    otpInputs.value[index - 1]?.focus();
    digits.value[index - 1] = '';
  }
  if (event.key === 'ArrowLeft' && index > 0) { event.preventDefault(); otpInputs.value[index - 1]?.focus(); }
  if (event.key === 'ArrowRight' && index < 5) { event.preventDefault(); otpInputs.value[index + 1]?.focus(); }
}

function onPaste(event) {
  event.preventDefault();
  const pastedText = event.clipboardData?.getData('text') || '';
  const digitsOnly = pastedText.replace(/\D/g, '').slice(0, 6);
  for (let i = 0; i < 6; i++) digits.value[i] = digitsOnly[i] || '';
  if (digitsOnly.length >= 6) handleSubmit();
  else if (digitsOnly.length > 0) otpInputs.value[digitsOnly.length]?.focus();
}

async function handleSubmit() {
  const otp = digits.value.join('');
  if (otp.length !== 6) return;
  clearError();
  isSubmitting.value = true;
  try {
    await authApi.verifyOtp(loginInfoId.value, otp);
    initSession();
    router.push(redirectPath.value);
  } catch (err) {
    setError(err);
    digits.value = ['', '', '', '', '', ''];
    setTimeout(() => otpInputs.value[0]?.focus(), 100);
  } finally {
    isSubmitting.value = false;
  }
}

function handleResend() {
  resendCooldown.value = 60;
  cooldownTimer = setInterval(() => {
    resendCooldown.value--;
    if (resendCooldown.value <= 0) { clearInterval(cooldownTimer); cooldownTimer = null; }
  }, 1000);
  router.push({ path: '/login', query: { resend: 'true' } });
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
