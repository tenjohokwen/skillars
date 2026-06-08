<template>
  <q-page class="flex flex-center q-pa-md">
    <div class="col-12 col-sm-8 col-md-6 col-lg-4" style="max-width: 400px">
      <q-card>
        <q-card-section>
          <div class="text-h5 text-center">{{ t('auth.resetPassword') }}</div>
        </q-card-section>

        <q-card-section v-if="!resetKey">
          <q-banner class="bg-negative text-white" rounded>
            Invalid or missing reset key
          </q-banner>
        </q-card-section>

        <q-card-section v-else>
          <q-form @submit.prevent="handleSubmit" class="q-gutter-md">
            <q-input
              v-model="form.password"
              :type="isPwd ? 'password' : 'text'"
              :label="t('auth.newPassword')"
              lazy-rules
              :rules="[required, minLen5, maxLen50]"
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

            <q-input
              v-model="form.confirmPassword"
              :type="isPwd2 ? 'password' : 'text'"
              :label="t('auth.confirmPassword')"
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

            <q-btn
              type="submit"
              color="primary"
              class="full-width q-mt-md"
              :loading="isSubmitting"
              :disable="isSubmitting"
              :label="t('auth.resetPassword')"
            />
          </q-form>
        </q-card-section>

        <q-card-section class="text-center q-pt-none">
          <router-link to="/login" class="text-primary">
            {{ t('common.back') }} {{ t('auth.login') }}
          </router-link>
        </q-card-section>
      </q-card>
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

// Get reset key from route query
const resetKey = computed(() => route.query.key);

// Form state
const form = ref({
  password: '',
  confirmPassword: ''
});
const isPwd = ref(true);
const isPwd2 = ref(true);
const isSubmitting = ref(false);

// Validation rules
const required = val => !!val || t('validation.required');
const minLen5 = val => val.length >= 5 || t('validation.minLength', { min: 5 });
const maxLen50 = val => val.length <= 50 || t('validation.maxLength', { max: 50 });
const passwordMatch = val => val === form.value.password || t('validation.passwordMatch');

// Handle form submission
async function handleSubmit() {
  clearError();
  isSubmitting.value = true;

  try {
    await accountApi.resetPassword(resetKey.value, form.value.password);

    $q.notify({
      type: 'positive',
      message: t('success.passwordReset')
    });

    router.push('/login');
  } catch (err) {
    setError(err);
  } finally {
    isSubmitting.value = false;
  }
}
</script>
