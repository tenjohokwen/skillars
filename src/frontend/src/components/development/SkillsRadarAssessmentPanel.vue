<template>
  <q-dialog v-model="open" persistent>
    <q-card style="min-width: 600px; max-width: 90vw">
      <q-card-section class="row items-center q-pb-none">
        <div class="text-h6">{{ $t('development.radar.assessmentPanelTitle') }}</div>
        <q-space />
        <q-btn icon="close" flat round dense @click="close" />
      </q-card-section>

      <q-card-section>
        <q-banner class="bg-grey-2 q-mb-md text-caption rounded-borders">
          {{ $t('development.radar.scoreTierReference') }}
        </q-banner>

        <div class="row q-col-gutter-md q-mb-md">
          <div class="col-12 col-sm-6">
            <q-select
              v-model="assessmentType"
              :options="assessmentTypeOptions"
              :label="$t('development.radar.assessmentTypeLabel')"
              emit-value
              map-options
              outlined
              dense
            />
          </div>
          <div class="col-12 col-sm-6">
            <q-input
              v-model="assessmentDate"
              :label="$t('development.radar.assessmentDateLabel')"
              type="date"
              :max="localDateString()"
              outlined
              dense
            />
          </div>
        </div>

        <div v-for="skill in skillDefinitions" :key="skill.code" class="row items-center q-mb-sm q-col-gutter-sm">
          <div class="col-4 text-caption">{{ skill.displayName }} ({{ skill.code }})</div>
          <div class="col-5">
            <q-input
              v-model.number="scores[skill.code].score"
              :label="$t('development.radar.scoreLabel')"
              type="number"
              min="1"
              max="100"
              outlined
              dense
            >
              <template v-if="skill.rubricCriteria" #append>
                <q-icon name="info" class="cursor-pointer">
                  <q-tooltip max-width="300px">{{ skill.rubricCriteria }}</q-tooltip>
                </q-icon>
              </template>
            </q-input>
          </div>
          <div class="col-3">
            <q-input
              v-model="scores[skill.code].notes"
              :label="$t('development.radar.notesLabel')"
              maxlength="500"
              outlined
              dense
            />
          </div>
        </div>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn flat :label="$t('common.cancel')" @click="close" />
        <q-btn
          color="primary"
          :label="$t('development.radar.submitLabel')"
          :disable="!canSubmit"
          :loading="submitting"
          @click="submit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useDevelopmentStore } from 'src/stores/development.store'

const props = defineProps({
  modelValue: Boolean,
  playerId: { type: Number, required: true },
  skillDefinitions: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue'])

const { t } = useI18n()
const $q = useQuasar()
const developmentStore = useDevelopmentStore()

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const assessmentGroupId = ref(null)
const assessmentType = ref('OBJECTIVE')
const assessmentDate = ref(localDateString())
const scores = ref({})
const submitting = ref(false)

function localDateString() {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

const assessmentTypeOptions = computed(() => [
  { label: t('development.radar.assessmentTypeLabelObjective'), value: 'OBJECTIVE' },
  { label: t('development.radar.assessmentTypeLabelMatchObs'), value: 'MATCH_OBSERVATION' },
  { label: t('development.radar.assessmentTypeLabelCoachEval'), value: 'COACH_EVALUATION' },
])

watch(() => props.modelValue, (val) => {
  if (val) {
    assessmentGroupId.value = crypto.randomUUID()
    assessmentType.value = 'OBJECTIVE'
    assessmentDate.value = localDateString()
    scores.value = Object.fromEntries(
      props.skillDefinitions.map((s) => [s.code, { score: null, notes: '' }])
    )
  }
}, { immediate: true })

const canSubmit = computed(() =>
  Object.values(scores.value).some(
    (s) => s.score !== null && s.score >= 1 && s.score <= 100
  )
)

async function submit() {
  const entries = Object.entries(scores.value)
    .filter(([, s]) => s.score !== null && s.score >= 1 && s.score <= 100)
    .map(([code, s]) => ({
      skillCode: code,
      score: s.score,
      notes: s.notes || null,
    }))

  const payload = {
    assessmentGroupId: assessmentGroupId.value,
    assessmentDate: assessmentDate.value,
    assessmentType: assessmentType.value,
    entries,
  }

  submitting.value = true
  try {
    await developmentStore.submitRadarAssessment(props.playerId, payload)
    $q.notify({ type: 'positive', message: t('development.radar.submitSuccess') })
    close()
  } catch (err) {
    const is403 = err?.response?.status === 403
    $q.notify({
      type: 'negative',
      message: is403
        ? t('development.radar.submitErrorFeatureGated')
        : (err?.response?.data?.message ?? t('development.radar.submitError')),
    })
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:modelValue', false)
}
</script>
