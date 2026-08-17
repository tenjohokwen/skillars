<template>
  <div>
    <div class="text-label q-mb-sm">{{ t('auth.coach.step1SectionBasics') }}</div>
    <q-input
      v-model="form.displayName"
      :label="t('auth.coach.step1DisplayName')"
      outlined
      lazy-rules
      :rules="[
        (v) => !!v || t('validation.required'),
        (v) => v.length <= 120 || t('validation.maxLength', { max: 120 }),
      ]"
      class="q-mb-sm"
    />
    <q-input
      v-model="form.bio"
      :label="t('auth.coach.step1Bio')"
      type="textarea"
      outlined
      autogrow
      :rules="[(v) => !v || v.length <= 2000 || t('validation.maxLength', { max: 2000 })]"
      class="q-mb-sm"
    />
    <q-banner v-if="showContactWarning" class="contact-warning q-mb-md" rounded dense>
      <template #avatar>
        <q-icon name="warning" />
      </template>
      {{ t('auth.coach.contactDetailWarning') }}
    </q-banner>

    <div class="text-label q-mb-sm q-mt-md">{{ t('auth.coach.step1SectionLocation') }}</div>
    <div class="row q-col-gutter-md q-mb-sm">
      <div class="col-12 col-sm-6">
        <q-input v-model="form.city" :label="t('auth.coach.step1City')" outlined />
      </div>
      <div class="col-12 col-sm-6">
        <q-input v-model="form.district" :label="t('auth.coach.step1District')" outlined />
      </div>
    </div>

    <div class="text-label q-mb-sm q-mt-md">{{ t('auth.coach.step1SectionLanguages') }}</div>
    <q-select
      v-model="form.languages"
      :label="t('auth.coach.step1Languages')"
      :options="languageOptions"
      outlined
      multiple
      use-chips
      :rules="[(v) => (v && v.length > 0) || t('validation.required')]"
      class="q-mb-md"
    />

    <div class="text-label q-mb-sm q-mt-md">{{ t('auth.coach.step1SectionTimezone') }}</div>
    <TimezoneSelect v-model="canonicalTimezone" />

    <div class="q-mt-md">
      <q-btn
        :label="t('common.next')"
        class="btn-accent"
        @click="submit"
        :loading="props.loading"
        :disable="!canonicalTimezone"
        unelevated
      />
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { sanitizePreview } from 'src/api/marketplace.api'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'
import TimezoneSelect from './TimezoneSelect.vue'

const { t } = useI18n()
const store = useProfileBuilderStore()

const emit = defineEmits(['submit'])

const props = defineProps({
  loading: { type: Boolean, default: false },
})

const languageOptions = ['English', 'German', 'French', 'Spanish', 'Arabic', 'Portuguese']

// The coach picks a zone from the server's own list rather than having
// Intl.DateTimeFormat().resolvedOptions().timeZone sent blind. A browser on newer tzdata than the
// deployed JVM (Europe/Kyiv, America/Ciudad_Juarez) used to make this step permanently
// uncompletable — and since the profile stays DRAFT until the builder finishes, and search only
// returns ACTIVE/REDUCED, that also kept the coach out of the marketplace entirely.
const canonicalTimezone = ref(null)

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

function submit() {
  // canonicalTimezone joins the existing guard: submitting without one would 400 on @NotBlank, and
  // the whole point of the picker is that the coach always has a value the server accepts.
  if (!form.displayName || form.languages.length === 0 || !canonicalTimezone.value) return
  // Remembered so Step 4 can default to the same zone instead of re-detecting the browser.
  store.setSelectedTimezone(canonicalTimezone.value)
  emit('submit', {
    displayName: form.displayName,
    bio: form.bio || null,
    city: form.city || null,
    district: form.district || null,
    languages: form.languages,
    canonicalTimezone: canonicalTimezone.value,
  })
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
