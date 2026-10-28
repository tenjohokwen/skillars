<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card class="drill-editor-dialog glass-card">
      <q-card-section class="row items-center q-pb-none">
        <div class="text-h6">
          {{ isEdit ? t('session.drillEditor.editTitle') : t('session.drillEditor.createTitle') }}
        </div>
        <q-space />
        <q-btn icon="close" flat round dense :disable="saving" @click="close" />
      </q-card-section>

      <q-card-section class="q-gutter-sm">
        <q-input
          v-model="form.name"
          :label="t('session.drillEditor.name')"
          outlined
          dense
          maxlength="200"
          :error="!!nameError"
          :error-message="nameError"
          data-test="drill-name"
        />
        <q-input
          v-model="form.description"
          :label="t('session.drillEditor.description')"
          type="textarea"
          outlined
          dense
          autogrow
          maxlength="2000"
        />
        <q-select
          v-model="form.primarySkills"
          :label="t('session.drillEditor.primarySkills')"
          :options="skillOptions"
          :hint="t('session.drillEditor.skillsHint')"
          multiple
          use-chips
          use-input
          new-value-mode="add-unique"
          :max-values="5"
          outlined
          dense
        />
        <q-select
          v-model="form.secondarySkills"
          :label="t('session.drillEditor.secondarySkills')"
          :options="skillOptions"
          multiple
          use-chips
          use-input
          new-value-mode="add-unique"
          :max-values="5"
          outlined
          dense
        />
        <div class="row q-col-gutter-sm">
          <div class="col-12 col-sm-6">
            <q-select
              v-model="form.difficultyTier"
              :label="t('session.drillLibrary.metaDifficulty')"
              :options="difficultyOptions"
              outlined
              dense
            />
          </div>
          <div class="col-12 col-sm-6">
            <q-input
              v-model="form.recommendedGroupSize"
              :label="t('session.drillLibrary.metaGroupSize')"
              outlined
              dense
              maxlength="20"
            />
          </div>
        </div>
        <q-input
          v-model.number="form.repDensity"
          :label="t('session.drillEditor.repDensity')"
          :hint="t('session.drillEditor.repDensityHint')"
          type="number"
          min="0"
          max="200"
          step="1"
          outlined
          dense
        />
        <div v-for="scale in scaleFields" :key="scale.field" class="q-mt-sm">
          <div class="text-caption">
            {{
              t('session.drillEditor.scaleValue', { label: scale.label, value: form[scale.field] })
            }}
          </div>
          <q-slider v-model="form[scale.field]" :min="0" :max="10" :step="1" markers />
        </div>
        <q-toggle v-model="form.weakFootBias" :label="t('session.drillLibrary.weakFootLabel')" />
        <q-select
          v-model="form.equipmentRequired"
          :label="t('session.drillLibrary.metaEquipment')"
          :options="equipmentOptions"
          multiple
          use-chips
          use-input
          new-value-mode="add-unique"
          :max-values="10"
          outlined
          dense
        />
        <q-input
          v-model="form.coachingPointsText"
          :label="t('session.drillLibrary.detail.coachingPoints')"
          :hint="t('session.drillEditor.coachingPointsHint')"
          type="textarea"
          outlined
          dense
          autogrow
        />
        <q-banner v-if="formError" class="bg-negative text-white" rounded dense>
          {{ formError }}
        </q-banner>
        <div v-if="!isEdit" class="text-caption" style="color: var(--text-secondary)">
          {{ t('session.drillEditor.videoAfterCreate') }}
        </div>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn flat :label="t('common.cancel')" :disable="saving" @click="close" />
        <q-btn
          unelevated
          color="primary"
          :label="t('common.save')"
          :loading="saving"
          :disable="!canSave"
          data-test="drill-save"
          @click="save"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useSessionStore } from 'src/stores/session.store'

defineOptions({ name: 'DrillEditorDialog' })

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // null → create a new PRIVATE drill; a PRIVATE drill object → edit it.
  drill: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue', 'saved'])

const { t } = useI18n()
const $q = useQuasar()
const sessionStore = useSessionStore()

// Same vocabularies the library's filter sheet offers; coaches may add their own (add-unique).
const skillOptions = [
  'ball_mastery',
  'dribbling',
  'passing',
  'shooting',
  'finishing',
  'defending',
  'heading',
  'positioning',
  'coordination',
]
const equipmentOptions = ['ball', 'cones', 'bibs', 'goals', 'poles']
const difficultyOptions = ['U8', 'U10', 'U12', 'U14', 'U16', 'U18', 'Adult']
const scaleFields = computed(() => [
  { field: 'intensity', label: t('session.drillEditor.intensity') },
  { field: 'pressureLevel', label: t('session.drillEditor.pressureLevel') },
  { field: 'cognitiveLoad', label: t('session.drillEditor.cognitiveLoad') },
  { field: 'matchRealism', label: t('session.drillEditor.matchRealism') },
])
// Mirrors DrillUpsertRequest: coachingPoints @Size(max = 8), each @Size(max = 300).
const MAX_COACHING_POINTS = 8
const MAX_COACHING_POINT_LENGTH = 300

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const isEdit = computed(() => !!props.drill?.id)

function emptyForm() {
  return {
    name: '',
    description: '',
    primarySkills: [],
    secondarySkills: [],
    difficultyTier: 'U12',
    recommendedGroupSize: '',
    repDensity: 20,
    intensity: 5,
    pressureLevel: 5,
    cognitiveLoad: 5,
    matchRealism: 5,
    weakFootBias: false,
    equipmentRequired: [],
    coachingPointsText: '',
  }
}

function formFromDrill(drill) {
  const m = drill.metadata ?? {}
  return {
    name: drill.name ?? '',
    description: drill.description ?? '',
    primarySkills: [...(m.primarySkills ?? [])],
    secondarySkills: [...(m.secondarySkills ?? [])],
    difficultyTier: m.difficultyTier ?? 'U12',
    recommendedGroupSize: m.recommendedGroupSize ?? '',
    repDensity: m.repDensity ?? 0,
    intensity: m.intensity ?? 0,
    pressureLevel: m.pressureLevel ?? 0,
    cognitiveLoad: m.cognitiveLoad ?? 0,
    matchRealism: m.matchRealism ?? 0,
    weakFootBias: !!m.weakFootBias,
    equipmentRequired: [...(m.equipmentRequired ?? [])],
    coachingPointsText: (m.coachingPoints ?? []).join('\n'),
  }
}

const form = reactive(emptyForm())
const saving = ref(false)
const nameError = ref('')
const formError = ref('')

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) return
    Object.assign(form, props.drill ? formFromDrill(props.drill) : emptyForm())
    nameError.value = ''
    formError.value = ''
  },
  { immediate: true },
)

const coachingPoints = computed(() =>
  form.coachingPointsText
    .split('\n')
    .map((p) => p.trim())
    .filter(Boolean),
)

const repDensityValid = computed(
  () => Number.isInteger(form.repDensity) && form.repDensity >= 0 && form.repDensity <= 200,
)

const canSave = computed(
  () =>
    !!form.name.trim() &&
    form.primarySkills.length > 0 &&
    !!form.difficultyTier &&
    repDensityValid.value,
)

function close() {
  emit('update:modelValue', false)
}

function buildPayload() {
  return {
    name: form.name.trim(),
    description: form.description?.trim() || null,
    primarySkills: form.primarySkills,
    secondarySkills: form.secondarySkills,
    // Omitted: the backend derives the skill weighting (or keeps the existing one on edit when the
    // skill set is unchanged), so a coach is never asked for percentages.
    repDensity: form.repDensity,
    intensity: form.intensity,
    pressureLevel: form.pressureLevel,
    cognitiveLoad: form.cognitiveLoad,
    matchRealism: form.matchRealism,
    weakFootBias: form.weakFootBias,
    difficultyTier: form.difficultyTier,
    equipmentRequired: form.equipmentRequired,
    recommendedGroupSize: form.recommendedGroupSize?.trim() || null,
    coachingPoints: coachingPoints.value,
  }
}

async function save() {
  nameError.value = ''
  formError.value = ''
  if (!canSave.value) return
  if (
    coachingPoints.value.length > MAX_COACHING_POINTS ||
    coachingPoints.value.some((p) => p.length > MAX_COACHING_POINT_LENGTH)
  ) {
    formError.value = t('session.drillEditor.coachingPointsTooMany', {
      max: MAX_COACHING_POINTS,
      length: MAX_COACHING_POINT_LENGTH,
    })
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    const saved = isEdit.value
      ? await sessionStore.updateDrill(props.drill.id, payload)
      : await sessionStore.createDrill(payload)
    $q.notify({
      type: 'positive',
      message: isEdit.value ? t('session.drillEditor.updated') : t('session.drillEditor.created'),
    })
    emit('saved', saved)
    close()
  } catch (e) {
    const errorKey = e?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'session.drillNameTaken') {
      nameError.value = t('session.drillEditor.nameTaken')
    } else if (errorKey === 'DRILL_NOT_OWNED') {
      formError.value = t('session.drillLibrary.upload.notOwned')
    } else if (e?.response?.status === 404) {
      formError.value = t('session.drillEditor.notFound')
    } else {
      formError.value = t('session.drillEditor.saveFailed')
    }
  } finally {
    saving.value = false
  }
}

defineExpose({ form, save, canSave })
</script>

<style scoped lang="scss">
.drill-editor-dialog {
  width: 560px;
  max-width: 95vw;
}
</style>
