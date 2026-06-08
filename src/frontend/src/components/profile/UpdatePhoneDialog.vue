<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 400px;">
      <q-card-section>
        <div class="text-h6">{{ t('profile.updatePhone') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit" class="q-gutter-md">
          <!-- Phone number -->
          <q-input
            v-model="form.phone"
            type="tel"
            :label="t('profile.phone')"
            outlined
            lazy-rules
            :rules="[validCamPhone, notSamePhone]"
            :error="hasFieldError('phone')"
            :error-message="getFieldError('phone')"
            mask="#########"
            hint="9 digits starting with 6 (e.g., 670123456)"
          />

          <!-- Error banner -->
          <q-banner
            v-if="hasError && !isValidationError"
            class="bg-negative text-white"
            rounded
          >
            {{ errorMessage }}
            <template v-if="helpCode">
              <br />
              <small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>
        </q-form>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn flat :label="t('common.cancel')" @click="close" />
        <q-btn
          color="primary"
          :label="t('common.save')"
          :loading="isSubmitting"
          @click="handleSubmit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, watch } from 'vue';
import { useQuasar } from 'quasar';
import { useI18n } from 'vue-i18n';
import { profileApi } from 'src/api/profile.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  currentPhone: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['update:modelValue', 'updated']);

const $q = useQuasar();
const { t } = useI18n();
const {
  setError,
  clearError,
  hasError,
  errorMessage,
  helpCode,
  isValidationError,
  hasFieldError,
  getFieldError
} = useErrorHandler();

// Local dialog visibility for v-model sync
const dialogVisible = ref(props.modelValue);

// Form state
const form = ref({
  phone: props.currentPhone || ''
});
const originalPhone = ref(props.currentPhone || '');
const isSubmitting = ref(false);

// Cameroon phone validation
const validCamPhone = (val) => {
  if (!val) return t('validation.required');
  const cleaned = val.replace(/\D/g, '');
  if (cleaned.length !== 9) return t('validation.phone.digitCount');
  if (cleaned[0] !== '6') return t('validation.phone.firstDigit');
  return true;
};
const notSamePhone = (val) => {
  const cleanedNew = (val || '').replace(/\D/g, '');
  const cleanedOld = (originalPhone.value || '').replace(/\D/g, '');
  return cleanedNew !== cleanedOld || t('validation.samePhone');
};

// Watch for external changes to modelValue
watch(() => props.modelValue, (newVal) => {
  dialogVisible.value = newVal;
  if (newVal) {
    // Reset form when dialog opens
    form.value.phone = props.currentPhone || '';
    originalPhone.value = props.currentPhone || '';
    clearError();
  }
});

// Watch for internal changes to sync back to parent
watch(dialogVisible, (newVal) => {
  emit('update:modelValue', newVal);
});

function close() {
  dialogVisible.value = false;
}

async function handleSubmit() {
  clearError();
  isSubmitting.value = true;
  try {
    await profileApi.updatePhone(form.value.phone);
    $q.notify({
      type: 'positive',
      message: t('success.phoneChanged')
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
