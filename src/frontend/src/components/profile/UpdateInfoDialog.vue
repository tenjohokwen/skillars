<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 500px; max-width: 600px;">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updateInfo') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit">
          <div class="row q-col-gutter-md">
            <!-- Title -->
            <div class="col-12 col-md-4">
              <q-select
                v-model="form.title"
                :options="titleOptions"
                :label="$t('auth.title')"
                outlined
                emit-value
                map-options
                clearable
                :error="hasFieldError('title')"
                :error-message="getFieldError('title')"
              />
            </div>

            <!-- First Name -->
            <div class="col-12 col-md-4">
              <q-input
                v-model="form.firstName"
                :label="$t('auth.firstName')"
                outlined
                :rules="[maxLen50]"
                :error="hasFieldError('firstName')"
                :error-message="getFieldError('firstName')"
              />
            </div>

            <!-- Last Name -->
            <div class="col-12 col-md-4">
              <q-input
                v-model="form.lastName"
                :label="$t('auth.lastName')"
                outlined
                :rules="[maxLen50]"
                :error="hasFieldError('lastName')"
                :error-message="getFieldError('lastName')"
              />
            </div>

            <!-- National ID -->
            <div class="col-12 col-md-6">
              <q-input
                v-model="form.nationalId"
                :label="$t('auth.nationalId')"
                outlined
                :rules="[optionalMinLen5, maxLen50]"
                :error="hasFieldError('nationalId')"
                :error-message="getFieldError('nationalId')"
              />
            </div>

            <!-- Gender -->
            <div class="col-12 col-md-6">
              <q-select
                v-model="form.gender"
                :options="genderOptions"
                :label="$t('auth.gender')"
                outlined
                emit-value
                map-options
                clearable
                :error="hasFieldError('gender')"
                :error-message="getFieldError('gender')"
              />
            </div>

            <!-- Language -->
            <div class="col-12 col-md-6">
              <q-select
                v-model="form.langKey"
                :options="languageOptions"
                :label="$t('auth.preferredLanguage')"
                outlined
                emit-value
                map-options
                :error="hasFieldError('langKey')"
                :error-message="getFieldError('langKey')"
              />
            </div>
          </div>

          <!-- Error banner -->
          <q-banner v-if="hasError && !isValidationError" class="bg-negative text-white q-mt-md" rounded>
            {{ errorMessage }}
            <template v-if="helpCode">
              <br />
              <small>{{ $t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>
        </q-form>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn flat :label="$t('common.cancel')" @click="close" />
        <q-btn color="primary" :label="$t('common.save')" :loading="isSubmitting" @click="handleSubmit" />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, watch, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useQuasar } from 'quasar';
import { profileApi } from 'src/api/profile.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  currentInfo: {
    type: Object,
    default: null
  }
});

const emit = defineEmits(['update:modelValue', 'updated']);

const { t } = useI18n();
const $q = useQuasar();

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

// Dialog visibility
const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val)
});

// Form state
const form = ref({
  title: null,
  firstName: '',
  lastName: '',
  nationalId: '',
  gender: null,
  langKey: 'en'
});

const isSubmitting = ref(false);

// Title options (reused from RegisterPage)
const titleOptions = computed(() => [
  { label: t('auth.titleMr'), value: 'Mr.' },
  { label: t('auth.titleMrs'), value: 'Mrs.' },
  { label: t('auth.titleMs'), value: 'Ms.' },
  { label: t('auth.titleDr'), value: 'Dr.' },
  { label: t('auth.titleProf'), value: 'Prof.' }
]);

// Gender options (reused from RegisterPage)
const genderOptions = computed(() => [
  { label: t('auth.male'), value: 'MALE' },
  { label: t('auth.female'), value: 'FEMALE' },
  { label: t('auth.other'), value: 'OTHER' }
]);

// Language options (reused from RegisterPage)
const languageOptions = computed(() => [
  { label: t('auth.languageEnglish'), value: 'en' },
  { label: t('auth.languageFrench'), value: 'fr' }
]);

// Pre-fill from currentInfo prop
watch(() => props.currentInfo, (info) => {
  if (info) {
    form.value = {
      title: info.title || null,
      firstName: info.firstName || '',
      lastName: info.lastName || '',
      nationalId: info.nationalId || '',
      gender: info.gender || null,
      langKey: info.langKey || 'en'
    };
  }
}, { immediate: true });

// Validation rules
const optionalMinLen5 = (val) => !val || val.length >= 5 || t('validation.minLength', { min: 5 });
const maxLen50 = (val) => !val || val.length <= 50 || t('validation.maxLength', { max: 50 });

function close() {
  clearError();
  emit('update:modelValue', false);
}

async function handleSubmit() {
  clearError();
  isSubmitting.value = true;

  try {
    // Build object with only non-empty fields (partial update)
    const infoData = {};
    if (form.value.title) infoData.title = form.value.title;
    if (form.value.firstName) infoData.firstName = form.value.firstName;
    if (form.value.lastName) infoData.lastName = form.value.lastName;
    if (form.value.nationalId) infoData.nationalId = form.value.nationalId;
    if (form.value.gender) infoData.gender = form.value.gender;
    if (form.value.langKey) infoData.langKey = form.value.langKey;

    await profileApi.updateInfo(infoData);

    $q.notify({
      type: 'positive',
      message: t('success.infoChanged')
    });

    emit('updated');
    close();
  } catch (err) {
    setError(err);
  } finally {
    isSubmitting.value = false;
  }
}
</script>
