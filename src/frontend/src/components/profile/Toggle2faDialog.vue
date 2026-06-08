<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 400px;">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.toggle2fa') }}</div>
      </q-card-section>

      <q-card-section>
        <p>{{ props.currentEnabled ? $t('profile.confirm2faDisable') : $t('profile.confirm2faEnable') }}</p>

        <q-form @submit.prevent="handleSubmit" class="q-gutter-md q-mt-md">
          <q-input
            v-model="form.password"
            :type="isPwd ? 'password' : 'text'"
            :label="$t('auth.password')"
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

          <q-banner v-if="hasError && !isValidationError" class="bg-negative text-white" rounded>
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
        <q-btn
          color="primary"
          :label="props.currentEnabled ? $t('profile.disable2fa') : $t('profile.enable2fa')"
          :loading="isSubmitting"
          @click="handleSubmit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { useQuasar } from 'quasar';
import { profileApi } from 'src/api/profile.api';
import { useErrorHandler } from 'src/composables/useErrorHandler';

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  },
  currentEnabled: {
    type: Boolean,
    default: false
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
  password: ''
});

const isPwd = ref(true);
const isSubmitting = ref(false);

// Validation rules
const required = (val) => !!val || t('validation.required');
const minLen5 = (val) => val.length >= 5 || t('validation.minLength', { min: 5 });

function close() {
  clearError();
  form.value.password = '';
  isPwd.value = true;
  emit('update:modelValue', false);
}

async function handleSubmit() {
  clearError();
  isSubmitting.value = true;

  try {
    const newEnabled = !props.currentEnabled;
    await profileApi.toggle2fa(newEnabled, form.value.password);

    $q.notify({
      type: 'positive',
      message: newEnabled ? t('success.twoFactorEnabled') : t('success.twoFactorDisabled')
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
