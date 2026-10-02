<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 450px; max-width: 550px">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updateCoachSpecialties') }}</div>
      </q-card-section>

      <q-card-section>
        <q-form @submit.prevent="handleSubmit">
          <q-select
            v-model="form.specialties"
            :label="t('auth.coach.step2Specialties')"
            :options="specialtyOptions"
            outlined
            multiple
            use-chips
            :rules="[(v) => (v && v.length > 0) || t('validation.required')]"
            :error="hasFieldError('specialties')"
            :error-message="getFieldError('specialties')"
            class="q-mb-md"
          />
          <div class="text-label q-mb-sm">{{ t('auth.coach.step2AgeGroups') }}</div>
          <div class="profile-dialog__age-groups q-mb-lg">
            <q-checkbox v-model="form.ageGroups" val="U10" :label="t('auth.coach.ageGroupU10')" />
            <q-checkbox
              v-model="form.ageGroups"
              val="AGE_10_12"
              :label="t('auth.coach.ageGroup10to12')"
            />
            <q-checkbox
              v-model="form.ageGroups"
              val="AGE_13_17"
              :label="t('auth.coach.ageGroup13to17')"
            />
            <q-checkbox
              v-model="form.ageGroups"
              val="ADULT"
              :label="t('auth.coach.ageGroupAdult')"
            />
          </div>
          <div v-if="hasFieldError('ageGroups')" class="text-negative text-caption q-mb-md">
            {{ getFieldError('ageGroups') }}
          </div>

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
import { ref, reactive, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { saveProfileBuilderStep } from 'src/api/marketplace.api'
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

const specialtyOptions = [
  'Dribbling',
  'Shooting',
  'Passing',
  'Defending',
  'Goalkeeping',
  'Fitness',
  'Tactics',
  'Set Pieces',
  'Heading',
  'First Touch',
]

const form = reactive({
  specialties: [],
  ageGroups: [],
})

const isSubmitting = ref(false)

watch(
  () => props.current,
  (profile) => {
    if (profile) {
      form.specialties = profile.specialties || []
      form.ageGroups = profile.ageGroups || []
    }
  },
  { immediate: true },
)

function close() {
  clearError()
  emit('update:modelValue', false)
}

async function handleSubmit() {
  clearError()
  if (!form.specialties.length || !form.ageGroups.length) return
  isSubmitting.value = true
  try {
    await saveProfileBuilderStep(2, {
      specialties: form.specialties,
      ageGroups: form.ageGroups,
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
.profile-dialog__age-groups {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 12px 16px;
  background: var(--surface-glass);
  border: 1px solid var(--border-soft);
  border-radius: 14px;
}
</style>
