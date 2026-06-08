<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">Password reset</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.resetPassword') }}</div>
        <div class="text-meta q-mb-lg">Choose a strong new password.</div>

        <!-- Invalid key -->
        <div v-if="!resetKey" class="auth-banner auth-banner--error q-pa-md q-mb-md">
          Invalid or missing reset key. Please request a new password reset.
        </div>

        <q-form v-else @submit.prevent="handleSubmit" class="q-gutter-md">
          <q-input
            v-model="form.password"
            :type="isPwd ? 'password' : 'text'"
            :label="t('auth.newPassword')"
            outlined
            lazy-rules
            :rules="[required, minLen5, maxLen50]"
            :error="hasFieldError('password')"
            :error-message="getFieldError('password')"
          >
            <template #append>
              <q-icon :name="isPwd ? 'visibility_off' : 'visibility'" class="cursor-pointer"
                style="color: var(--text-muted)" @click="isPwd = !isPwd" />
            </template>
          </q-input>

          <q-input
            v-model="form.confirmPassword"
            :type="isPwd2 ? 'password' : 'text'"
            :label="t('auth.confirmPassword')"
            outlined
            lazy-rules
            :rules="[required, passwordMatch]"
            :error="hasFieldError('confirmPassword')"
            :error-message="getFieldError('confirmPassword')"
          >
            <template #append>
              <q-icon :name="isPwd2 ? 'visibility_off' : 'visibility'" class="cursor-pointer"
                style="color: var(--text-muted)" @click="isPwd2 = !isPwd2" />
            </template>
          </q-input>

          <q-banner
            v-if="hasError && !isValidationError"
            class="auth-banner auth-banner--error"
            rounded
          >
            {{ errorMessage }}
            <template v-if="helpCode">
              <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <q-btn
            type="submit"
            class="full-width btn-accent q-mt-sm"
            :loading="isSubmitting"
            :disable="isSubmitting"
            :label="t('auth.resetPassword')"
            unelevated size="md"
          />
        </q-form>

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
import { ref, computed } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useQuasar } from 'quasar';
import { useI18n } from 'vue-i18n';
import { accountApi } from 'src/api/account.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const router = useRouter();
const route = useRoute();
const $q = useQuasar();
const { t } = useI18n();

const { setError, clearError, hasError, errorMessage, isValidationError, helpCode, hasFieldError, getFieldError } = useErrorHandler();

const resetKey = computed(() => route.query.key);
const form = ref({ password: '', confirmPassword: '' });
const isPwd = ref(true);
const isPwd2 = ref(true);
const isSubmitting = ref(false);

const required = val => !!val || t('validation.required');
const minLen5 = val => val.length >= 5 || t('validation.minLength', { min: 5 });
const maxLen50 = val => val.length <= 50 || t('validation.maxLength', { max: 50 });
const passwordMatch = val => val === form.value.password || t('validation.passwordMatch');

async function handleSubmit() {
  clearError();
  isSubmitting.value = true;
  try {
    await accountApi.resetPassword(resetKey.value, form.value.password);
    $q.notify({ type: 'positive', message: t('success.passwordReset') });
    router.push('/login');
  } catch (err) {
    setError(err);
  } finally {
    isSubmitting.value = false;
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
</style>
