<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 400px;">
      <q-card-section>
        <div class="text-h6">{{ t('profile.updateEmail') }}</div>
      </q-card-section>

      <q-card-section class="q-pt-none">
        <q-banner class="bg-warning text-dark" rounded dense>
          <template #avatar>
            <q-icon name="warning" color="dark" />
          </template>
          {{ t('profile.emailChangeWarning') }}
        </q-banner>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit" class="q-gutter-md">
          <!-- Old email (readonly) -->
          <q-input
            v-model="form.oldEmail"
            :label="t('auth.email')"
            readonly
            outlined
          />

          <!-- New email -->
          <q-input
            v-model="form.newEmail"
            type="email"
            :label="t('profile.email') + ' (' + t('common.new') + ')'"
            outlined
            lazy-rules
            :rules="[required, validEmail, notSameEmail]"
            :error="hasFieldError('newEmail')"
            :error-message="getFieldError('newEmail')"
          />

          <!-- Password with toggle -->
          <q-input
            v-model="form.password"
            :type="isPwd ? 'password' : 'text'"
            :label="t('auth.currentPassword')"
            outlined
            lazy-rules
            :rules="[required, minLen5]"
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
import { useSession } from 'src/composables/useSession';

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  currentEmail: {
    type: String,
    default: ''
  }
});

const emit = defineEmits(['update:modelValue', 'updated']);

const $q = useQuasar();
const { t } = useI18n();
const { handleLogout } = useSession();
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
  oldEmail: props.currentEmail,
  newEmail: '',
  password: ''
});
const isPwd = ref(true);
const isSubmitting = ref(false);

// Validation rules
const required = val => !!val || t('validation.required');
const validEmail = val => /.+@.+\..+/.test(val) || t('validation.email');
const minLen5 = val => val.length >= 5 || t('validation.minLength', { min: 5 });
const notSameEmail = val => val !== form.value.oldEmail || t('validation.sameEmail');

// Watch for external changes to modelValue
watch(() => props.modelValue, (newVal) => {
  dialogVisible.value = newVal;
  if (newVal) {
    // Reset form when dialog opens
    form.value.oldEmail = props.currentEmail;
    form.value.newEmail = '';
    form.value.password = '';
    isPwd.value = true;
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
    await profileApi.updateEmail(form.value.oldEmail, form.value.newEmail, form.value.password);
    $q.notify({
      type: 'positive',
      message: t('success.emailChangeInitiated')
    });
    close();
    await handleLogout();
  } catch (err) {
    setError(err);
  } finally {
    isSubmitting.value = false;
  }
}
</script>
