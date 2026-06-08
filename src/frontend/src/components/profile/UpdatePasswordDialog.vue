<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 400px;">
      <q-card-section>
        <div class="text-h6">{{ t('profile.updatePassword') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit" class="q-gutter-md">
          <!-- Current password -->
          <q-input
            v-model="form.currentPassword"
            :type="isPwdCurrent ? 'password' : 'text'"
            :label="t('auth.currentPassword')"
            outlined
            lazy-rules
            :rules="[required, minLen5]"
            :error="hasFieldError('currentPassword')"
            :error-message="getFieldError('currentPassword')"
          >
            <template #append>
              <q-icon
                :name="isPwdCurrent ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                @click="isPwdCurrent = !isPwdCurrent"
              />
            </template>
          </q-input>

          <!-- New password -->
          <q-input
            v-model="form.newPassword"
            :type="isPwdNew ? 'password' : 'text'"
            :label="t('auth.newPassword')"
            outlined
            lazy-rules
            :rules="[required, minLen5, notSamePassword]"
            :error="hasFieldError('newPassword')"
            :error-message="getFieldError('newPassword')"
          >
            <template #append>
              <q-icon
                :name="isPwdNew ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                @click="isPwdNew = !isPwdNew"
              />
            </template>
          </q-input>

          <!-- Confirm new password -->
          <q-input
            v-model="form.confirmPassword"
            :type="isPwdConfirm ? 'password' : 'text'"
            :label="t('auth.confirmPassword')"
            outlined
            lazy-rules
            :rules="[required, passwordMatch]"
            :error="hasFieldError('confirmPassword')"
            :error-message="getFieldError('confirmPassword')"
          >
            <template #append>
              <q-icon
                :name="isPwdConfirm ? 'visibility_off' : 'visibility'"
                class="cursor-pointer"
                @click="isPwdConfirm = !isPwdConfirm"
              />
            </template>
          </q-input>

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
  currentPassword: '',
  newPassword: '',
  confirmPassword: ''
});
const isPwdCurrent = ref(true);
const isPwdNew = ref(true);
const isPwdConfirm = ref(true);
const isSubmitting = ref(false);

// Validation rules
const required = val => !!val || t('validation.required');
const minLen5 = val => val.length >= 5 || t('validation.minLength', { min: 5 });
const passwordMatch = val => val === form.value.newPassword || t('validation.passwordMatch');
const notSamePassword = val => val !== form.value.currentPassword || t('validation.samePassword');

// Watch for external changes to modelValue
watch(() => props.modelValue, (newVal) => {
  dialogVisible.value = newVal;
  if (newVal) {
    // Reset form when dialog opens
    form.value.currentPassword = '';
    form.value.newPassword = '';
    form.value.confirmPassword = '';
    isPwdCurrent.value = true;
    isPwdNew.value = true;
    isPwdConfirm.value = true;
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
    await profileApi.updatePassword(form.value.currentPassword, form.value.newPassword);
    $q.notify({
      type: 'positive',
      message: t('success.passwordChanged')
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
