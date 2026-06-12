<template>
  <q-page class="auth-page">
    <div class="auth-card-container fade-in">

      <div class="auth-brand q-mb-xl">
        <div class="gradient-text auth-brand-name">Skillars</div>
      </div>

      <div class="glass-card--static auth-card">

        <div class="text-section-title q-mb-xs">{{ t('player.createTitle') }}</div>

        <q-banner
          v-if="hasError && !hasFieldErrors"
          class="auth-banner auth-banner--error q-mb-md"
          rounded
        >
          {{ errorMessage }}
          <template v-if="helpCode">
            <br /><small>{{ t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </q-banner>

        <q-form @submit.prevent="handleCreate">
          <div class="row q-col-gutter-md">

            <div class="col-12">
              <q-input
                v-model="form.name"
                :label="t('player.nameLabel')"
                outlined lazy-rules
                :rules="[required, maxLen100]"
                :error="hasFieldError('name')"
                :error-message="getFieldError('name')"
              />
            </div>

            <div class="col-12 col-md-6">
              <q-input
                v-model="form.dateOfBirth"
                :label="t('player.dobLabel')"
                type="date"
                outlined lazy-rules
                :rules="[required, pastDate]"
                :error="hasFieldError('dateOfBirth')"
                :error-message="getFieldError('dateOfBirth')"
              />
            </div>

            <div class="col-12 col-md-6">
              <q-select
                v-model="form.position"
                :options="positionOptions"
                :label="t('player.positionLabel')"
                outlined lazy-rules
                emit-value map-options
                :rules="[required]"
                :error="hasFieldError('position')"
                :error-message="getFieldError('position')"
              />
            </div>

            <div v-if="form.dateOfBirth" class="col-12">
              <div class="age-tier-preview text-meta">
                {{ t('player.ageTierLabel') }}: <strong>{{ ageTierPreview }}</strong>
              </div>
            </div>

          </div>

          <div v-if="isMinorDob" class="q-mt-lg">
            <div class="text-section-title q-mb-sm">{{ t('player.consentTitle') }}</div>
            <div class="policy-scroll-box q-mb-sm" @scroll="onConsentScroll">
              <p>{{ t('player.consentBody') }}</p>
            </div>
            <q-checkbox
              v-model="form.parentConsent"
              :disable="!consentScrolled"
              :label="t('player.consentLabel')"
              color="primary"
            />
            <input type="hidden" :value="consentPolicyVersion" />
          </div>

          <q-btn
            type="submit"
            class="full-width btn-accent q-mt-lg"
            :label="t('player.createButton')"
            :loading="isSubmitting"
            :disable="isSubmitting || (isMinorDob && !form.parentConsent)"
            unelevated size="md"
          />
        </q-form>
      </div>
    </div>
  </q-page>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { playerProfileApi } from 'src/api/playerProfile.api'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const router = useRouter()
const { t } = useI18n()
const {
  setError, clearError, hasError, hasFieldErrors, errorMessage,
  helpCode, hasFieldError, getFieldError,
} = useErrorHandler()

const form = ref({
  name: '',
  dateOfBirth: '',
  position: null,
  parentConsent: false,
})

const isSubmitting = ref(false)
const consentPolicyVersion = '1.0'
const consentScrolled = ref(false)

function onConsentScroll(event) {
  const el = event.target
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 4) {
    consentScrolled.value = true
  }
}

const positionOptions = computed(() => [
  { label: t('player.positions.GOALKEEPER'), value: 'GOALKEEPER' },
  { label: t('player.positions.DEFENDER'), value: 'DEFENDER' },
  { label: t('player.positions.MIDFIELDER'), value: 'MIDFIELDER' },
  { label: t('player.positions.FORWARD'), value: 'FORWARD' },
])

const ageFromDob = computed(() => {
  if (!form.value.dateOfBirth) return null
  const dob = new Date(form.value.dateOfBirth)
  const today = new Date()
  let age = today.getFullYear() - dob.getFullYear()
  const m = today.getMonth() - dob.getMonth()
  if (m < 0 || (m === 0 && today.getDate() < dob.getDate())) age--
  return age
})

const isMinorDob = computed(() => ageFromDob.value !== null && ageFromDob.value < 18)
watch(isMinorDob, () => { consentScrolled.value = false; form.value.parentConsent = false })

const ageTierPreview = computed(() => {
  const age = ageFromDob.value
  if (age === null) return ''
  if (age <= 9) return t('player.ageTiers.U10')
  if (age <= 12) return t('player.ageTiers.AGE_10_12')
  if (age <= 17) return t('player.ageTiers.AGE_13_17')
  return t('player.ageTiers.ADULT')
})

const required = val => !!val || t('validation.required')
const maxLen100 = val => !val || val.length <= 100 || t('validation.maxLength', { max: 100 })
const pastDate = val => {
  if (!val) return true
  return new Date(val) < new Date() || t('validation.pastDate')
}

async function handleCreate() {
  clearError()
  isSubmitting.value = true
  try {
    const payload = {
      name: form.value.name,
      dateOfBirth: form.value.dateOfBirth,
      position: form.value.position,
    }
    if (isMinorDob.value) {
      payload.parentConsent = form.value.parentConsent
      payload.consentPolicyVersion = consentPolicyVersion
    }
    await playerProfileApi.createProfile(payload)
    router.push('/parent/dashboard')
  } catch (err) {
    setError(err)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.auth-brand { text-align: center; }
.auth-brand-name {
  font-size: 32px;
  font-weight: 800;
  font-family: 'Inter', sans-serif;
  letter-spacing: -1px;
}
.auth-card { padding: 32px; }
.auth-banner {
  border-radius: 12px !important;
  font-size: 14px;
  &--error { background: rgba(255, 95, 122, 0.12) !important; color: var(--accent-danger) !important; }
}
.age-tier-preview {
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--surface-glass);
  font-size: 14px;
}
.policy-scroll-box {
  max-height: 120px;
  overflow-y: auto;
  padding: 12px;
  border: 1px solid var(--border-subtle);
  border-radius: 8px;
  font-size: 0.85rem;
  color: var(--text-secondary);
  margin-bottom: 8px;
  background: var(--surface-glass);
}
</style>
