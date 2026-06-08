<template>
  <q-page class="flex flex-center">
    <div class="col-12 col-sm-8 col-md-6 col-lg-4 q-pa-md">
      <q-card>
        <q-card-section>
          <div class="text-h5 text-center">{{ t('auth.enterOtp') }}</div>
        </q-card-section>

        <q-card-section>
          <q-banner class="bg-info text-white q-mb-md" rounded>
            <template v-slot:avatar>
              <q-icon name="mail" color="white" />
            </template>
            {{ t('auth.otpSent') }}
          </q-banner>

          <div class="text-caption text-grey-7 text-center q-mb-lg">
            Code expires in 30 minutes
          </div>

          <!-- OTP Input Section -->
          <div class="flex justify-center q-gutter-sm q-mb-lg">
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

          <!-- Error Display -->
          <q-banner v-if="hasError" class="bg-negative text-white q-mb-md" rounded>
            <template v-slot:avatar>
              <q-icon name="error" color="white" />
            </template>
            {{ errorMessage }}
            <template v-if="helpCode">
              <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <!-- Loading Indicator -->
          <div v-if="isSubmitting" class="text-center q-mb-md">
            <q-spinner-dots color="primary" size="40px" />
            <div class="text-caption q-mt-sm">{{ t('common.loading') }}</div>
          </div>

          <!-- Resend Section -->
          <div class="text-center q-mt-lg">
            <div class="text-body2 q-mb-sm">Didn't receive the code?</div>
            <q-btn
              flat
              color="primary"
              :label="resendCooldown > 0 ? `${t('auth.resendOtp')} (${resendCooldown}s)` : t('auth.resendOtp')"
              :disable="resendCooldown > 0"
              @click="handleResend"
            />
          </div>

          <!-- Back to Login Link -->
          <div class="text-center q-mt-lg">
            <router-link to="/login" class="text-primary">
              {{ t('common.back') }} {{ t('auth.login') }}
            </router-link>
          </div>
        </q-card-section>
      </q-card>
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

// State
const digits = ref(['', '', '', '', '', '']);
const otpInputs = ref([]);
const isSubmitting = ref(false);
const resendCooldown = ref(0);
let cooldownTimer = null;

// Computed
const loginInfoId = computed(() => route.query.id || '');
const redirectPath = computed(() => route.query.redirect || '/dashboard');

// Auto-focus first input on mount
onMounted(() => {
  setTimeout(() => otpInputs.value[0]?.focus(), 100);
});

// Cleanup cooldown timer
onUnmounted(() => {
  if (cooldownTimer) clearInterval(cooldownTimer);
});

// Handle digit input - auto-advance to next field
function onDigitInput(index) {
  const value = digits.value[index];
  if (value && !/^\d$/.test(value)) {
    digits.value[index] = '';
    return;
  }
  if (value && index < 5) {
    setTimeout(() => otpInputs.value[index + 1]?.focus(), 10);
  }
  if (digits.value.every(d => /^\d$/.test(d))) {
    handleSubmit();
  }
}

// Handle keyboard navigation
function onKeyDown(index, event) {
  if (event.key === 'Backspace' && !digits.value[index] && index > 0) {
    event.preventDefault();
    otpInputs.value[index - 1]?.focus();
    digits.value[index - 1] = '';
  }
  if (event.key === 'ArrowLeft' && index > 0) {
    event.preventDefault();
    otpInputs.value[index - 1]?.focus();
  }
  if (event.key === 'ArrowRight' && index < 5) {
    event.preventDefault();
    otpInputs.value[index + 1]?.focus();
  }
}

// Handle paste - fill all digits from clipboard
function onPaste(event) {
  event.preventDefault();
  const pastedText = event.clipboardData?.getData('text') || '';
  const digitsOnly = pastedText.replace(/\D/g, '').slice(0, 6);
  for (let i = 0; i < 6; i++) {
    digits.value[i] = digitsOnly[i] || '';
  }
  if (digitsOnly.length >= 6) {
    handleSubmit();
  } else if (digitsOnly.length > 0) {
    otpInputs.value[digitsOnly.length]?.focus();
  }
}

// Submit OTP for verification
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

// Handle resend OTP - redirect to login (credentials needed)
function handleResend() {
  resendCooldown.value = 60;
  cooldownTimer = setInterval(() => {
    resendCooldown.value--;
    if (resendCooldown.value <= 0) {
      clearInterval(cooldownTimer);
      cooldownTimer = null;
    }
  }, 1000);
  router.push({ path: '/login', query: { resend: 'true' } });
}
</script>

<style scoped>
.otp-digit { width: 50px !important; }
.otp-digit :deep(input) { text-align: center; font-size: 24px; font-weight: bold; }
.otp-digit :deep(.q-field__control) { height: 60px; }
</style>
