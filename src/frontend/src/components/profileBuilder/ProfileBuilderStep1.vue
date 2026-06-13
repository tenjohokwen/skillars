<template>
  <div>
    <q-input
      v-model="form.displayName"
      :label="t('auth.coach.step1DisplayName')"
      outlined
      lazy-rules
      :rules="[v => !!v || t('validation.required'), v => v.length <= 120 || t('validation.maxLength', { max: 120 })]"
      class="q-mb-sm"
    />
    <q-input
      v-model="form.bio"
      :label="t('auth.coach.step1Bio')"
      type="textarea"
      outlined
      autogrow
      :rules="[v => !v || v.length <= 2000 || t('validation.maxLength', { max: 2000 })]"
      class="q-mb-sm"
    />
    <q-banner
      v-if="showContactWarning"
      class="q-mb-sm"
      style="background: var(--color-warning-surface, #fff3cd); color: var(--color-warning-text, #856404); border-radius: 8px;"
      rounded
      dense
    >
      <template #avatar>
        <q-icon name="warning" />
      </template>
      {{ t('auth.coach.contactDetailWarning') }}
    </q-banner>
    <q-input
      v-model="form.city"
      :label="t('auth.coach.step1City')"
      outlined
      class="q-mb-sm"
    />
    <q-input
      v-model="form.district"
      :label="t('auth.coach.step1District')"
      outlined
      class="q-mb-sm"
    />
    <q-select
      v-model="form.languages"
      :label="t('auth.coach.step1Languages')"
      :options="languageOptions"
      outlined
      multiple
      use-chips
      :rules="[v => (v && v.length > 0) || t('validation.required')]"
      class="q-mb-sm"
    />
    <div class="q-mt-md">
      <q-btn
        :label="t('common.next')"
        color="primary"
        @click="submit"
        :loading="props.loading"
        unelevated
      />
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { sanitizePreview } from 'src/api/marketplace.api'

const { t } = useI18n()

const emit = defineEmits(['submit'])

const props = defineProps({
  loading: { type: Boolean, default: false },
})

const languageOptions = ['English', 'German', 'French', 'Spanish', 'Arabic', 'Portuguese']

const canonicalTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone

const form = reactive({
  displayName: '',
  bio: '',
  city: '',
  district: '',
  languages: [],
})

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
        showContactWarning.value = res.data.detectionFound === true
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

function submit() {
  if (!form.displayName || form.languages.length === 0) return
  emit('submit', {
    displayName: form.displayName,
    bio: form.bio || null,
    city: form.city || null,
    district: form.district || null,
    languages: form.languages,
    canonicalTimezone,
  })
}
</script>
