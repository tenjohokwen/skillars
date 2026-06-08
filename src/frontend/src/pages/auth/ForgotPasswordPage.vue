<template>
  <q-page class="flex flex-center q-pa-md">
    <div class="col-12 col-sm-8 col-md-6 col-lg-4" style="max-width: 400px; width: 100%">
      <q-card style="min-height: 450px">
        <q-card-section>
          <div class="text-h5 text-center">{{ t('auth.forgotPassword').replace('?', '') }}</div>
        </q-card-section>

        <q-card-section v-if="isSuccess">
          <q-banner class="bg-positive text-white" rounded>
            {{ t('success.emailSent') }}
          </q-banner>
          <div class="text-center q-mt-md">
            <router-link to="/login" class="text-primary">
              {{ t('common.back') }} {{ t('auth.login') }}
            </router-link>
          </div>
        </q-card-section>

        <q-card-section v-else>
          <q-form @submit.prevent="handleSubmit" class="q-gutter-md">
            <q-input
              v-model="form.email"
              type="email"
              :label="t('auth.email')"
              lazy-rules
              :rules="[required, validEmail]"
              :error="hasFieldError('email')"
              :error-message="getFieldError('email')"
            />

            <q-input
              v-model="form.dob"
              type="date"
              :label="t('auth.dateOfBirth')"
              lazy-rules
              :rules="[required]"
              :error="hasFieldError('dob')"
              :error-message="getFieldError('dob')"
            />

            <q-banner
              v-if="hasError && !isValidationError"
              class="bg-negative text-white q-mt-md"
              rounded
            >
              {{ errorMessage }}
              <template v-if="helpCode">
                <br />
                <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
              </template>
            </q-banner>

            <div class="q-mt-md">
              <q-btn
                type="submit"
                color="primary"
                class="full-width"
                :loading="isSubmitting"
                :disable="isSubmitting"
                :label="t('common.submit')"
              />
            </div>
          </q-form>
        </q-card-section>

        <q-card-section v-if="!isSuccess" class="text-center q-pt-none">
          <router-link to="/login" class="text-primary">
            {{ t('common.back') }} {{ t('auth.login') }}
          </router-link>
        </q-card-section>
      </q-card>
    </div>
  </q-page>
</template>

<script setup>
import { ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { accountApi } from 'src/api/account.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const { t } = useI18n();

const {
  setError,
  clearError,
  hasError,
  errorMessage,
  isValidationError,
  helpCode,
  hasFieldError,
  getFieldError
} = useErrorHandler();

// Form state
const form = ref({
  email: '',
  dob: ''
});
const isSubmitting = ref(false);
const isSuccess = ref(false);

// Validation rules
const required = val => !!val || t('validation.required');
const validEmail = val => /.+@.+\..+/.test(val) || t('validation.email');

// Handle form submission
async function handleSubmit() {
  clearError();
  isSubmitting.value = true;

  try {
    // loginId and currentEmail are both the email address
    await accountApi.requestPasswordReset(form.value.email, form.value.dob, form.value.email);
    isSuccess.value = true;
  } catch (err) {
    setError(err);
  } finally {
    isSubmitting.value = false;
  }
}
</script>
