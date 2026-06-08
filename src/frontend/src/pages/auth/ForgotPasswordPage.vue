<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">Account recovery</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.forgotPassword').replace('?', '') }}</div>
        <div class="text-meta q-mb-lg">Enter your email and date of birth to reset your password.</div>

        <!-- Success state -->
        <template v-if="isSuccess">
          <div class="success-block">
            <q-icon name="mark_email_read" size="40px" style="color: var(--accent-primary)" class="q-mb-md" />
            <div class="text-card-title q-mb-sm">Email sent</div>
            <div class="text-meta">{{ t('success.emailSent') }}</div>
          </div>
          <router-link to="/login" class="auth-link block-link">
            &larr; {{ t('common.back') }} {{ t('auth.login') }}
          </router-link>
        </template>

        <!-- Form state -->
        <template v-else>
          <q-form @submit.prevent="handleSubmit" class="q-gutter-md">
            <q-input
              v-model="form.email"
              type="email"
              :label="t('auth.email')"
              outlined
              lazy-rules
              :rules="[required, validEmail]"
              :error="hasFieldError('email')"
              :error-message="getFieldError('email')"
            />

            <q-input
              v-model="form.dob"
              type="date"
              :label="t('auth.dateOfBirth')"
              outlined
              lazy-rules
              :rules="[required]"
              :error="hasFieldError('dob')"
              :error-message="getFieldError('dob')"
            />

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
              :label="t('common.submit')"
              unelevated size="md"
            />
          </q-form>

          <div class="text-center q-mt-md">
            <router-link to="/login" class="auth-link">
              &larr; {{ t('common.back') }} {{ t('auth.login') }}
            </router-link>
          </div>
        </template>

      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { accountApi } from 'src/api/account.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const { t } = useI18n();
const { setError, clearError, hasError, errorMessage, isValidationError, helpCode, hasFieldError, getFieldError } = useErrorHandler();

const form = ref({ email: '', dob: '' });
const isSubmitting = ref(false);
const isSuccess = ref(false);

const required = val => !!val || t('validation.required');
const validEmail = val => /.+@.+\..+/.test(val) || t('validation.email');

async function handleSubmit() {
  clearError();
  isSubmitting.value = true;
  try {
    await accountApi.requestPasswordReset(form.value.email, form.value.dob, form.value.email);
    isSuccess.value = true;
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
.block-link {
  display: block;
  text-align: center;
  margin-top: 20px;
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error { background: rgba(255, 95, 122, 0.12) !important; color: var(--accent-danger) !important; }
}
.success-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 16px 0 8px;
}
</style>
