<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <!-- Brand mark -->
      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">Analytics Platform</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.login') }}</div>
        <div class="text-meta q-mb-lg">Welcome back. Sign in to continue.</div>

        <!-- Session expired banner -->
        <q-banner
          v-if="route.query.expired === 'true'"
          class="q-mb-md auth-banner auth-banner--warning"
          rounded
        >
          {{ t('session.expired') }}
        </q-banner>

        <q-form @submit.prevent="handleLogin" class="q-gutter-md">
          <q-input
            v-model="form.email"
            type="email"
            :label="t('auth.email')"
            outlined
            lazy-rules
            :rules="[required, validEmail]"
            :error="hasFieldError('id')"
            :error-message="getFieldError('id')"
          />

          <q-input
            v-model="form.password"
            :type="isPwd ? 'password' : 'text'"
            :label="t('auth.password')"
            outlined
            lazy-rules
            :rules="[required]"
            :error="hasFieldError('password')"
            :error-message="getFieldError('password')"
          >
            <template #append>
              <q-icon
                :name="isPwd ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                style="color: var(--text-muted)"
                @click="isPwd = !isPwd"
              />
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
            :label="t('auth.login')"
            unelevated
            size="md"
          />
        </q-form>

        <div class="text-center q-mt-md">
          <router-link to="/forgot-password" class="auth-link">
            {{ t('auth.forgotPassword') }}
          </router-link>
        </div>

        <div class="auth-divider"><span>or</span></div>

        <div class="text-center">
          <span class="text-meta">{{ t('auth.noAccount') }}</span>
          <router-link to="/register" class="auth-link q-ml-xs">
            {{ t('auth.createAccount') }}
          </router-link>
        </div>

      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { authApi } from 'src/api/auth.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';
import { useSession } from 'src/composables/useSession';

const router = useRouter();
const route = useRoute();
const { t } = useI18n();
const { initSession } = useSession();

const {
  setError, clearError, hasError, errorMessage,
  isValidationError, helpCode, hasFieldError, getFieldError
} = useErrorHandler();

const form = ref({ email: '', password: '' });
const isPwd = ref(true);
const isSubmitting = ref(false);

const required = val => !!val || t('validation.required');
const validEmail = val => /.+@.+\..+/.test(val) || t('validation.email');

async function handleLogin() {
  clearError();
  isSubmitting.value = true;
  try {
    const response = await authApi.login(form.value.email, form.value.password, 1);
    if (response.msgKey === 'check.otp') {
      router.push({ path: '/otp', query: { id: response.payload.loginInfoId, redirect: route.query.redirect } });
    } else {
      initSession();
      router.push(route.query.redirect || '/dashboard');
    }
  } catch (err) {
    setError(err);
  } finally {
    isSubmitting.value = false;
  }
}
</script>

<style lang="scss" scoped>
.auth-brand {
  text-align: center;
}
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card {
  padding: 32px;
}
.auth-link {
  color: var(--accent-primary);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: opacity 0.15s ease;
  &:hover { opacity: 0.8; }
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--warning { background: rgba(255, 184, 77, 0.12) !important; color: var(--accent-warning) !important; }
  &--error   { background: rgba(255, 95, 122, 0.12) !important; color: var(--accent-danger) !important; }
}
.auth-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0;
  &::before, &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--border-soft);
  }
  span {
    font-size: 12px;
    color: var(--text-muted);
    font-weight: 500;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
}
</style>
