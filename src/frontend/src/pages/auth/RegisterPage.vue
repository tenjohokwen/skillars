<template>
  <q-page class="flex flex-center">
    <div class="col-12 col-sm-10 col-md-8 col-lg-6 q-pa-md">
      <q-card style="max-width: 600px; width: 100%;">
        <q-card-section>
          <div class="text-h5 text-center">{{ t('auth.register') }}</div>
        </q-card-section>

        <q-card-section>
          <q-banner
            v-if="hasError && (!isValidationError || !hasFieldErrors)"
            class="q-mb-md bg-negative text-white"
            rounded
          >
            {{ errorMessage }}
            <template v-if="helpCode">
              <br />
              <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <q-form @submit.prevent="handleRegister">
            <div class="row q-col-gutter-md">
              <!-- Row 1: Email and National Id -->
              <div class="col-12 col-md-6">
                <q-input
                  v-model="form.email"
                  type="email"
                  :label="t('auth.email')"
                  outlined
                  lazy-rules
                  :rules="[required, validEmail, minLen5, maxLen100]"
                  :error="hasFieldError('email')"
                  :error-message="getFieldError('email')"
                />
              </div>
              <div class="col-12 col-md-6">
                <q-input
                  v-model="form.nationalId"
                  :label="t('auth.nationalId')"
                  outlined
                  lazy-rules
                  :rules="[optionalMinLen5, maxLen50]"
                  :error="hasFieldError('nationalId')"
                  :error-message="getFieldError('nationalId')"
                />
              </div>

              <!-- Row 2: Title, First Name and Last Name -->
              <div class="col-12 col-md-2">
                <q-select
                  v-model="form.title"
                  :options="titleOptions"
                  :label="t('auth.title')"
                  outlined
                  emit-value
                  map-options
                  clearable
                  :error="hasFieldError('title')"
                  :error-message="getFieldError('title')"
                />
              </div>
              <div class="col-12 col-md-5">
                <q-input
                  v-model="form.firstName"
                  :label="t('auth.firstName')"
                  outlined
                  lazy-rules
                  :rules="[required, maxLen50]"
                  :error="hasFieldError('firstName')"
                  :error-message="getFieldError('firstName')"
                />
              </div>
              <div class="col-12 col-md-5">
                <q-input
                  v-model="form.lastName"
                  :label="t('auth.lastName')"
                  outlined
                  lazy-rules
                  :rules="[required, maxLen50]"
                  :error="hasFieldError('lastName')"
                  :error-message="getFieldError('lastName')"
                />
              </div>

              <!-- Row 3: Password and Confirm Password -->
              <div class="col-12 col-md-6">
                <q-input
                  v-model="form.password"
                  :type="isPwd ? 'password' : 'text'"
                  :label="t('auth.password')"
                  outlined
                  lazy-rules
                  :rules="[required, minLen5, maxLen100]"
                  :error="hasFieldError('password')"
                  :error-message="getFieldError('password')"
                >
                  <template #append>
                    <q-icon
                      :name="isPwd ? 'visibility_off' : 'visibility'"
                      class="cursor-pointer"
                      @click="isPwd = !isPwd"
                    />
                  </template>
                </q-input>
              </div>
              <div class="col-12 col-md-6">
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
                    <q-icon
                      :name="isPwd2 ? 'visibility_off' : 'visibility'"
                      class="cursor-pointer"
                      @click="isPwd2 = !isPwd2"
                    />
                  </template>
                </q-input>
              </div>

              <!-- Row 4: Phone and Date of Birth (Optional) -->
              <div class="col-12 col-md-6">
                <q-input
                  v-model="form.phone"
                  type="tel"
                  :label="t('auth.phone')"
                  outlined
                  :error="hasFieldError('phone')"
                  :error-message="getFieldError('phone')"
                />
              </div>
              <div class="col-12 col-md-6">
                <q-input
                  v-model="form.dob"
                  type="date"
                  :label="t('auth.dateOfBirth')"
                  outlined
                  lazy-rules
                  :rules="[required, pastDate]"
                  :error="hasFieldError('dob')"
                  :error-message="getFieldError('dob')"
                />
              </div>

              <!-- Row 5: Gender and Language Preference -->
              <div class="col-12 col-md-6">
                <q-select
                  v-model="form.gender"
                  :options="genderOptions"
                  :label="t('auth.gender')"
                  outlined
                  emit-value
                  map-options
                  clearable
                  :error="hasFieldError('gender')"
                  :error-message="getFieldError('gender')"
                />
              </div>
              <div class="col-12 col-md-6">
                <q-select
                  v-model="form.langKey"
                  :options="languageOptions"
                  :label="t('auth.preferredLanguage')"
                  outlined
                  emit-value
                  map-options
                  :rules="[required]"
                  :error="hasFieldError('langKey')"
                  :error-message="getFieldError('langKey')"
                />
              </div>

              <!-- Row 6: OTP (Optional) -->
              <div class="col-12 col-md-6 flex items-center">
                <q-checkbox
                  v-model="form.otpEnabled"
                  :label="t('auth.enableTwoFactor')"
                />
              </div>
            </div>

            <div class="q-mt-lg">
              <q-btn
                type="submit"
                color="primary"
                :label="t('auth.createAccount')"
                :loading="isSubmitting"
                :disable="isSubmitting"
                class="full-width"
              />
            </div>

            <div class="text-center q-mt-md">
              {{ t('auth.haveAccount') }}
              <router-link to="/login" class="text-primary">
                {{ t('auth.login') }}
              </router-link>
            </div>
          </q-form>
        </q-card-section>
      </q-card>
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
  setError,
  clearError,
  hasError,
  hasFieldErrors,
  errorMessage,
  isValidationError,
  helpCode,
  hasFieldError,
  getFieldError,
} = useErrorHandler();

// Form state
const form = ref({
  email: '',
  nationalId: '',
  password: '',
  confirmPassword: '',
  title: null,
  firstName: '',
  lastName: '',
  phone: '',
  dob: '',
  gender: null,
  langKey: 'en',
  otpEnabled: false,
});

const isPwd = ref(true);
const isPwd2 = ref(true);
const isSubmitting = ref(false);

// Title options
const titleOptions = computed(() => [
  { label: t('auth.titleMr'), value: 'Mr.' },
  { label: t('auth.titleMrs'), value: 'Mrs.' },
  { label: t('auth.titleMs'), value: 'Ms.' },
  { label: t('auth.titleDr'), value: 'Dr.' },
  { label: t('auth.titleProf'), value: 'Prof.' },
]);

// Gender options
const genderOptions = computed(() => [
  { label: t('auth.male'), value: 'MALE' },
  { label: t('auth.female'), value: 'FEMALE' },
  { label: t('auth.other'), value: 'OTHER' },
]);

// Language options
const languageOptions = computed(() => [
  { label: t('auth.languageEnglish'), value: 'en' },
  { label: t('auth.languageFrench'), value: 'fr' },
]);

// Validation rules
const required = (val) => !!val || t('validation.required');
const validEmail = (val) => /.+@.+\..+/.test(val) || t('validation.email');
const minLen5 = (val) => val.length >= 5 || t('validation.minLength', { min: 5 });
const optionalMinLen5 = (val) => !val || val.length >= 5 || t('validation.minLength', { min: 5 });
const maxLen50 = (val) => !val || val.length <= 50 || t('validation.maxLength', { max: 50 });
const maxLen100 = (val) => val.length <= 100 || t('validation.maxLength', { max: 100 });
const passwordMatch = (val) => val === form.value.password || t('validation.passwordMatch');
const pastDate = (val) => !val || new Date(val) < new Date() || t('validation.pastDate');

// Handle registration
async function handleRegister() {
  clearError();
  isSubmitting.value = true;

  try {
    // Build userData, only including non-empty optional fields
    const userData = {
      email: form.value.email,
      password: form.value.password,
      firstName: form.value.firstName,
      lastName: form.value.lastName,
      langKey: form.value.langKey,
    };

    // dob is required
    userData.dob = form.value.dob;

    // Add optional fields only if they have values
    if (form.value.nationalId) {
      userData.nationalId = form.value.nationalId;
    }
    if (form.value.title != null && form.value.title !== '') {
      // Handle both string value and object (in case emit-value doesn't work)
      userData.title = typeof form.value.title === 'object' ? form.value.title.value : form.value.title;
    }
    if (form.value.phone) {
      userData.phone = form.value.phone;
    }
    if (form.value.gender) {
      userData.gender = form.value.gender;
    }
    if (form.value.otpEnabled) {
      userData.otpEnabled = form.value.otpEnabled;
    }

    await accountApi.register(userData);

    $q.notify({
      type: 'positive',
      message: t('success.registered'),
    });

    router.push('/login');
  } catch (err) {
    setError(err);
  } finally {
    isSubmitting.value = false;
  }
}
</script>
