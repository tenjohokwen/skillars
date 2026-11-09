<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 400px">
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
          <q-input v-model="form.oldEmail" :label="t('auth.email')" readonly outlined />

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

          <!-- skillars-deferred-172: refusals the dialog words itself (they carry no translatable key) -->
          <q-banner
            v-if="ownError"
            class="bg-negative text-white"
            rounded
            data-test="email-own-error"
          >
            {{ ownError }}
          </q-banner>

          <!-- Error banner -->
          <q-banner
            v-else-if="hasError && !isValidationError"
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
import { ref, watch } from 'vue'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'
import { profileApi } from 'src/api/profile.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false,
  },
  currentEmail: {
    type: String,
    default: '',
  },
})

const emit = defineEmits(['update:modelValue', 'updated'])

const $q = useQuasar()
const { t } = useI18n()
const {
  setError,
  clearError,
  hasError,
  errorMessage,
  helpCode,
  isValidationError,
  hasFieldError,
  getFieldError,
} = useErrorHandler()

// Local dialog visibility for v-model sync
const dialogVisible = ref(props.modelValue)

// Form state
const form = ref({
  oldEmail: props.currentEmail,
  newEmail: '',
  password: '',
})
const isPwd = ref(true)
const isSubmitting = ref(false)
const ownError = ref('')

// Validation rules
const required = (val) => !!val || t('validation.required')
const validEmail = (val) => /.+@.+\..+/.test(val) || t('validation.email')
const minLen5 = (val) => val.length >= 5 || t('validation.minLength', { min: 5 })
const notSameEmail = (val) =>
  val.trim().toLowerCase() !== form.value.oldEmail.trim().toLowerCase() || t('validation.sameEmail')

// Watch for external changes to modelValue
watch(
  () => props.modelValue,
  (newVal) => {
    dialogVisible.value = newVal
    if (newVal) {
      // Reset form when dialog opens
      form.value.oldEmail = props.currentEmail
      form.value.newEmail = ''
      form.value.password = ''
      isPwd.value = true
      ownError.value = ''
      clearError()
    }
  },
)

// Watch for internal changes to sync back to parent
watch(dialogVisible, (newVal) => {
  emit('update:modelValue', newVal)
})

function close() {
  dialogVisible.value = false
}

// skillars-deferred-172: nothing changes until the link mailed to the new address is opened, so the user stays
// signed in here. The answer is the same whether or not the address can be used (no enumeration).
async function handleSubmit() {
  clearError()
  ownError.value = ''
  isSubmitting.value = true
  try {
    await profileApi.updateEmail(form.value.oldEmail, form.value.newEmail, form.value.password)
    $q.notify({
      type: 'positive',
      message: t('success.emailChangeInitiated'),
      timeout: 8000,
    })
    close()
  } catch (err) {
    ownError.value = ownErrorText(err)
    if (!ownError.value) setError(err)
  } finally {
    isSubmitting.value = false
  }
}

/** The 409 inside the undo window (its message is the ISO end) and the per-account 429; '' for anything else. */
function ownErrorText(err) {
  const msg = err?.response?.data?.errorMsg
  if (msg?.errorKey === 'email.change.recentChange') {
    const until = new Date(msg.message)
    const date = Number.isNaN(until.getTime())
      ? ''
      : new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(
          until,
        )
    return t('profile.emailChange.recentChange', { date })
  }
  if (err?.response?.status === 429) {
    return t('profile.emailChange.tooManyRequests')
  }
  return ''
}
</script>
