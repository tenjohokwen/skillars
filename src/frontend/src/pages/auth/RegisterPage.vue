<template>
  <q-page class="auth-page">
    <div class="auth-card-container--wide fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
        <div class="text-meta">Create your account</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('auth.register') }}</div>
        <div class="text-meta q-mb-lg">Fill in your details to get started.</div>

        <q-banner
          v-if="hasError && (!isValidationError || !hasFieldErrors)"
          class="q-mb-md auth-banner auth-banner--error"
          rounded
        >
          {{ errorMessage }}
          <template v-if="helpCode">
            <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </q-banner>

        <q-form @submit.prevent="handleRegister">
          <div class="row q-col-gutter-md">

            <div class="col-12 col-md-6">
              <q-input v-model="form.email" type="email" :label="t('auth.email')" outlined lazy-rules
                :rules="[required, validEmail, minLen5, maxLen100]"
                :error="hasFieldError('email')" :error-message="getFieldError('email')" />
            </div>
            <div class="col-12 col-md-6">
              <q-input v-model="form.nationalId" :label="t('auth.nationalId')" outlined lazy-rules
                :rules="[optionalMinLen5, maxLen50]"
                :error="hasFieldError('nationalId')" :error-message="getFieldError('nationalId')" />
            </div>

            <div class="col-12 col-md-2">
              <q-select v-model="form.title" :options="titleOptions" :label="t('auth.title')"
                outlined emit-value map-options clearable
                :error="hasFieldError('title')" :error-message="getFieldError('title')" />
            </div>
            <div class="col-12 col-md-5">
              <q-input v-model="form.firstName" :label="t('auth.firstName')" outlined lazy-rules
                :rules="[required, maxLen50]"
                :error="hasFieldError('firstName')" :error-message="getFieldError('firstName')" />
            </div>
            <div class="col-12 col-md-5">
              <q-input v-model="form.lastName" :label="t('auth.lastName')" outlined lazy-rules
                :rules="[required, maxLen50]"
                :error="hasFieldError('lastName')" :error-message="getFieldError('lastName')" />
            </div>

            <div class="col-12 col-md-6">
              <q-input v-model="form.password" :type="isPwd ? 'password' : 'text'" :label="t('auth.password')"
                outlined lazy-rules :rules="[required, minLen5, maxLen100]"
                :error="hasFieldError('password')" :error-message="getFieldError('password')">
                <template #append>
                  <q-icon :name="isPwd ? 'visibility_off' : 'visibility'" class="cursor-pointer"
                    style="color: var(--text-muted)" @click="isPwd = !isPwd" />
                </template>
              </q-input>
            </div>
            <div class="col-12 col-md-6">
              <q-input v-model="form.confirmPassword" :type="isPwd2 ? 'password' : 'text'"
                :label="t('auth.confirmPassword')" outlined lazy-rules
                :rules="[required, passwordMatch]"
                :error="hasFieldError('confirmPassword')" :error-message="getFieldError('confirmPassword')">
                <template #append>
                  <q-icon :name="isPwd2 ? 'visibility_off' : 'visibility'" class="cursor-pointer"
                    style="color: var(--text-muted)" @click="isPwd2 = !isPwd2" />
                </template>
              </q-input>
            </div>

            <div class="col-12 col-md-6">
              <q-input v-model="form.phone" type="tel" :label="t('auth.phone')" outlined
                :error="hasFieldError('phone')" :error-message="getFieldError('phone')" />
            </div>
            <div class="col-12 col-md-6">
              <q-input v-model="form.dob" type="date" :label="t('auth.dateOfBirth')" outlined
                lazy-rules :rules="[required, pastDate]"
                :error="hasFieldError('dob')" :error-message="getFieldError('dob')" />
            </div>

            <div class="col-12 col-md-6">
              <q-select v-model="form.gender" :options="genderOptions" :label="t('auth.gender')"
                outlined emit-value map-options clearable
                :error="hasFieldError('gender')" :error-message="getFieldError('gender')" />
            </div>
            <div class="col-12 col-md-6">
              <q-select v-model="form.langKey" :options="languageOptions" :label="t('auth.preferredLanguage')"
                outlined emit-value map-options :rules="[required]"
                :error="hasFieldError('langKey')" :error-message="getFieldError('langKey')" />
            </div>

            <div class="col-12 col-md-6 flex items-center">
              <q-checkbox v-model="form.otpEnabled" :label="t('auth.enableTwoFactor')"
                color="primary" />
            </div>
          </div>

          <q-btn
            type="submit"
            class="full-width btn-accent q-mt-lg"
            :label="t('auth.createAccount')"
            :loading="isSubmitting"
            :disable="isSubmitting"
            unelevated size="md"
          />

          <div class="text-center q-mt-md">
            <span class="text-meta">{{ t('auth.haveAccount') }}</span>
            <router-link to="/login" class="auth-link q-ml-xs">
              {{ t('auth.login') }}
            </router-link>
          </div>
        </q-form>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useRouter } from 'vue-router';
import { useQuasar } from 'quasar';
import { useI18n } from 'vue-i18n';
import { accountApi } from 'src/api/account.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const router = useRouter();
const $q = useQuasar();
const { t } = useI18n();

const {
  setError, clearError, hasError, hasFieldErrors, errorMessage,
  isValidationError, helpCode, hasFieldError, getFieldError,
} = useErrorHandler();

const form = ref({
  email: '', nationalId: '', password: '', confirmPassword: '',
  title: null, firstName: '', lastName: '', phone: '',
  dob: '', gender: null, langKey: 'en', otpEnabled: false,
});

const isPwd = ref(true);
const isPwd2 = ref(true);
const isSubmitting = ref(false);

const titleOptions = computed(() => [
  { label: t('auth.titleMr'), value: 'Mr.' },
  { label: t('auth.titleMrs'), value: 'Mrs.' },
  { label: t('auth.titleMs'), value: 'Ms.' },
  { label: t('auth.titleDr'), value: 'Dr.' },
  { label: t('auth.titleProf'), value: 'Prof.' },
]);

const genderOptions = computed(() => [
  { label: t('auth.male'), value: 'MALE' },
  { label: t('auth.female'), value: 'FEMALE' },
  { label: t('auth.other'), value: 'OTHER' },
]);

const languageOptions = computed(() => [
  { label: t('auth.languageEnglish'), value: 'en' },
  { label: t('auth.languageFrench'), value: 'fr' },
]);

const required = val => !!val || t('validation.required');
const validEmail = val => /.+@.+\..+/.test(val) || t('validation.email');
const minLen5 = val => val.length >= 5 || t('validation.minLength', { min: 5 });
const optionalMinLen5 = val => !val || val.length >= 5 || t('validation.minLength', { min: 5 });
const maxLen50 = val => !val || val.length <= 50 || t('validation.maxLength', { max: 50 });
const maxLen100 = val => val.length <= 100 || t('validation.maxLength', { max: 100 });
const passwordMatch = val => val === form.value.password || t('validation.passwordMatch');
const pastDate = val => !val || new Date(val) < new Date() || t('validation.pastDate');

async function handleRegister() {
  clearError();
  isSubmitting.value = true;
  try {
    const userData = {
      email: form.value.email,
      password: form.value.password,
      firstName: form.value.firstName,
      lastName: form.value.lastName,
      langKey: form.value.langKey,
      dob: form.value.dob,
    };
    if (form.value.nationalId) userData.nationalId = form.value.nationalId;
    if (form.value.title != null && form.value.title !== '') {
      userData.title = typeof form.value.title === 'object' ? form.value.title.value : form.value.title;
    }
    if (form.value.phone) userData.phone = form.value.phone;
    if (form.value.gender) userData.gender = form.value.gender;
    if (form.value.otpEnabled) userData.otpEnabled = form.value.otpEnabled;

    await accountApi.register(userData);
    $q.notify({ type: 'positive', message: t('success.registered') });
    router.push('/login');
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
  &:hover { opacity: 0.8; }
}
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error { background: rgba(255, 95, 122, 0.12) !important; color: var(--accent-danger) !important; }
}
</style>
