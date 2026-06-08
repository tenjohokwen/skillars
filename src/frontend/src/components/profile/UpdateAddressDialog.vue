<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 500px; max-width: 600px;">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updateAddress') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit">
          <div class="row q-col-gutter-md">
            <!-- Address Name (select) -->
            <div class="col-12 col-md-6">
              <q-select
                v-model="form.name"
                :options="addressNameOptions"
                :label="$t('profile.addressName')"
                :hint="$t('profile.addressNameHint')"
                outlined
                emit-value
                map-options
                :rules="[required]"
                :error="hasFieldError('name')"
                :error-message="getFieldError('name')"
              />
            </div>

            <!-- Company Name (optional) -->
            <div class="col-12 col-md-6">
              <q-input
                v-model="form.companyName"
                :label="$t('profile.companyName')"
                outlined
                :rules="[maxLen250]"
                :error="hasFieldError('companyName')"
                :error-message="getFieldError('companyName')"
              />
            </div>

            <!-- Address lines -->
            <div class="col-12">
              <q-input
                v-model="form.addressLine1"
                :label="$t('profile.addressLine1')"
                outlined
                :rules="[required, maxLen250]"
                :error="hasFieldError('addressLine1')"
                :error-message="getFieldError('addressLine1')"
              />
            </div>
            <div class="col-12">
              <q-input
                v-model="form.addressLine2"
                :label="$t('profile.addressLine2')"
                outlined
                :rules="[maxLen250]"
                :error="hasFieldError('addressLine2')"
                :error-message="getFieldError('addressLine2')"
              />
            </div>
            <div class="col-12">
              <q-input
                v-model="form.addressLine3"
                :label="$t('profile.addressLine3')"
                outlined
                :rules="[maxLen250]"
                :error="hasFieldError('addressLine3')"
                :error-message="getFieldError('addressLine3')"
              />
            </div>

            <!-- City and State -->
            <div class="col-12 col-md-6">
              <q-input
                v-model="form.city"
                :label="$t('profile.city')"
                outlined
                :rules="[required, maxLen50]"
                :error="hasFieldError('city')"
                :error-message="getFieldError('city')"
              />
            </div>
            <div class="col-12 col-md-6">
              <q-input
                v-model="form.stateProvince"
                :label="$t('profile.stateProvince')"
                outlined
                :rules="[required, maxLen50]"
                :error="hasFieldError('stateProvince')"
                :error-message="getFieldError('stateProvince')"
              />
            </div>

            <!-- Postal Code and Country -->
            <div class="col-12 col-md-6">
              <q-input
                v-model="form.postalCode"
                :label="$t('profile.postalCode')"
                outlined
                :rules="[required, maxLen25]"
                :error="hasFieldError('postalCode')"
                :error-message="getFieldError('postalCode')"
              />
            </div>
            <div class="col-12 col-md-6">
              <q-input
                v-model="form.country"
                :label="$t('profile.country')"
                outlined
                :rules="[required, maxLen25]"
                :error="hasFieldError('country')"
                :error-message="getFieldError('country')"
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
  currentAddress: {
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
  name: null,
  companyName: '',
  addressLine1: '',
  addressLine2: '',
  addressLine3: '',
  city: '',
  stateProvince: '',
  postalCode: '',
  country: ''
});

const isSubmitting = ref(false);

// Address name options
const addressNameOptions = computed(() => [
  { label: 'HOME', value: 'HOME' },
  { label: 'WORK', value: 'WORK' },
  { label: 'OTHER', value: 'OTHER' }
]);

// Pre-fill from currentAddress prop
watch(() => props.currentAddress, (addr) => {
  if (addr) {
    form.value = {
      name: addr.name || null,
      companyName: addr.companyName || '',
      addressLine1: addr.addressLine1 || '',
      addressLine2: addr.addressLine2 || '',
      addressLine3: addr.addressLine3 || '',
      city: addr.city || '',
      stateProvince: addr.stateProvince || '',
      postalCode: addr.postalCode || '',
      country: addr.country || ''
    };
  }
}, { immediate: true });

// Validation rules
const required = (val) => !!val || t('validation.required');
const maxLen25 = (val) => !val || val.length <= 25 || t('validation.maxLength', { max: 25 });
const maxLen50 = (val) => !val || val.length <= 50 || t('validation.maxLength', { max: 50 });
const maxLen250 = (val) => !val || val.length <= 250 || t('validation.maxLength', { max: 250 });

function close() {
  clearError();
  emit('update:modelValue', false);
}

async function handleSubmit() {
  clearError();
  isSubmitting.value = true;

  try {
    await profileApi.updateAddress(form.value);

    $q.notify({
      type: 'positive',
      message: t('success.addressChanged')
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
