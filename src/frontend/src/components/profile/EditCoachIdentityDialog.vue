<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 500px; max-width: 600px">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updateCoachIdentity') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit">
          <q-input
            v-model="form.displayName"
            :label="t('auth.coach.step1DisplayName')"
            outlined
            lazy-rules
            :rules="[
              (v) => !!v || t('validation.required'),
              (v) => v.length <= 120 || t('validation.maxLength', { max: 120 }),
            ]"
            :error="hasFieldError('displayName')"
            :error-message="getFieldError('displayName')"
            class="q-mb-sm"
          />
          <q-input
            v-model="form.bio"
            :label="t('auth.coach.step1Bio')"
            type="textarea"
            outlined
            autogrow
            :rules="[(v) => !v || v.length <= 2000 || t('validation.maxLength', { max: 2000 })]"
            :error="hasFieldError('bio')"
            :error-message="getFieldError('bio')"
            class="q-mb-sm"
          />
          <q-banner v-if="showContactWarning" class="contact-warning q-mb-md" rounded dense>
            <template #avatar>
              <q-icon name="warning" />
            </template>
            {{ t('auth.coach.contactDetailWarning') }}
          </q-banner>

          <div class="row q-col-gutter-md q-mb-sm">
            <div class="col-12 col-sm-6">
              <q-input
                v-model="form.city"
                :label="t('auth.coach.step1City')"
                outlined
                :error="hasFieldError('city')"
                :error-message="getFieldError('city')"
              />
            </div>
            <div class="col-12 col-sm-6">
              <q-input
                v-model="form.district"
                :label="t('auth.coach.step1District')"
                outlined
                :error="hasFieldError('district')"
                :error-message="getFieldError('district')"
              />
            </div>
          </div>

          <q-select
            v-model="form.languages"
            :label="t('auth.coach.step1Languages')"
            :options="languageOptions"
            outlined
            multiple
            use-chips
            :rules="[(v) => (v && v.length > 0) || t('validation.required')]"
            :error="hasFieldError('languages')"
            :error-message="getFieldError('languages')"
            class="q-mb-md"
          />

          <q-select
            v-model="form.canonicalTimezone"
            :options="filteredTimezones"
            :label="t('auth.coach.timezoneLabel')"
            :hint="t('auth.coach.timezoneHint')"
            :loading="timezonesLoading"
            use-input
            input-debounce="200"
            @filter="filterZones"
            outlined
            dense
            emit-value
            map-options
            :rules="[(v) => !!v || t('validation.required')]"
            :error="hasFieldError('canonicalTimezone')"
            :error-message="getFieldError('canonicalTimezone')"
            class="q-mb-sm"
          />

          <q-banner
            v-if="hasError && !isValidationError"
            class="bg-negative text-white q-mt-md"
            rounded
          >
            {{ errorMessage }}
            <template v-if="helpCode">
              <br />
              <small>{{ $t('error.helpCode') }}: {{ helpCode }}</small>
            </template>
          </q-banner>

          <q-card-actions align="right" class="q-px-none q-pb-none">
            <q-btn type="button" flat :label="$t('common.cancel')" @click="close" />
            <q-btn
              type="submit"
              color="primary"
              :label="$t('common.save')"
              :loading="isSubmitting"
            />
          </q-card-actions>
        </q-form>
      </q-card-section>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, reactive, watch, computed, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import {
  saveProfileBuilderStep,
  getSupportedTimezones,
  sanitizePreview,
} from 'src/api/marketplace.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  current: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue', 'updated'])

const { t } = useI18n()
const $q = useQuasar()
const {
  setError,
  clearError,
  hasError,
  errorMessage,
  isValidationError,
  helpCode,
  hasFieldError,
  getFieldError,
} = useErrorHandler()

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const languageOptions = ['English', 'German', 'French', 'Spanish', 'Arabic', 'Portuguese']

const form = reactive({
  displayName: '',
  bio: '',
  city: '',
  district: '',
  languages: [],
  canonicalTimezone: null,
})

const isSubmitting = ref(false)
const allTimezones = ref([])
const timezonesLoading = ref(false)
const timezoneFilterTerm = ref('')

const filteredTimezones = computed(() => {
  const needle = timezoneFilterTerm.value.toLowerCase()
  if (!needle) return allTimezones.value
  return allTimezones.value.filter((z) => z.toLowerCase().includes(needle))
})

function filterZones(val, update) {
  update(() => {
    timezoneFilterTerm.value = val
  })
}

watch(
  () => props.current,
  (profile) => {
    if (profile) {
      form.displayName = profile.displayName || ''
      form.bio = profile.bio || ''
      form.city = profile.city || ''
      form.district = profile.district || ''
      form.languages = profile.languages || []
      form.canonicalTimezone = profile.canonicalTimezone || null
    }
  },
  { immediate: true },
)

const showContactWarning = ref(false)
let debounceTimer = null
let abortController = null

watch(
  () => form.bio,
  async (newVal) => {
    clearTimeout(debounceTimer)
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    if (!newVal) {
      showContactWarning.value = false
      return
    }
    debounceTimer = setTimeout(async () => {
      abortController = new AbortController()
      try {
        const res = await sanitizePreview(newVal, abortController.signal)
        showContactWarning.value = res.detectionFound === true
      } catch {
        showContactWarning.value = false
      } finally {
        abortController = null
      }
    }, 400)
  },
)

onUnmounted(() => {
  clearTimeout(debounceTimer)
  if (abortController) abortController.abort()
})

async function loadTimezones() {
  if (allTimezones.value.length > 0) return
  timezonesLoading.value = true
  try {
    allTimezones.value = await getSupportedTimezones()
  } catch {
    allTimezones.value = []
  } finally {
    timezonesLoading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) loadTimezones()
  },
)

function close() {
  clearError()
  emit('update:modelValue', false)
}

async function handleSubmit() {
  clearError()
  if (!form.displayName || form.languages.length === 0 || !form.canonicalTimezone) return
  isSubmitting.value = true
  try {
    await saveProfileBuilderStep(1, {
      displayName: form.displayName,
      bio: form.bio || null,
      city: form.city || null,
      district: form.district || null,
      languages: form.languages,
      canonicalTimezone: form.canonicalTimezone,
    })

    $q.notify({ type: 'positive', message: t('success.infoChanged') })
    emit('updated')
    close()
  } catch (err) {
    setError(err)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.contact-warning {
  background: var(--surface-warning) !important;
  color: var(--accent-warning) !important;
  border-radius: 8px !important;
  font-size: 13px;
}
</style>
