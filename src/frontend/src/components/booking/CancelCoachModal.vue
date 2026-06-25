<template>
  <q-dialog v-model="open" persistent>
    <q-card class="glass-card cancel-coach-modal q-pa-md" style="min-width: 340px">
      <q-card-section>
        <div class="text-h6">{{ t('cancellation.title') }}</div>
      </q-card-section>

      <q-card-section>
        <q-select
          v-model="selectedReason"
          :options="reasonOptions"
          :label="t('cancellation.selectReason')"
          emit-value
          map-options
          outlined
          dense
        />
      </q-card-section>

      <q-card-actions align="right" class="q-pt-none">
        <q-btn flat :label="t('common.cancel')" @click="open = false" />
        <q-btn
          color="negative"
          :label="t('cancellation.confirmCancel')"
          :loading="loading"
          :disable="!selectedReason"
          @click="confirm"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'confirm'])

const { t } = useI18n()
const selectedReason = ref(null)
const loading = ref(false)

const open = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const reasonOptions = [
  { label: t('cancellation.reasonMutualAgreement'), value: 'MUTUAL_AGREEMENT' },
  { label: t('cancellation.reasonHealthMedical'), value: 'HEALTH_MEDICAL' },
  { label: t('cancellation.reasonFamilyEmergency'), value: 'FAMILY_EMERGENCY' },
  { label: t('cancellation.reasonWeather'), value: 'WEATHER' },
  { label: t('cancellation.reasonSchedulingPreference'), value: 'SCHEDULING_PREFERENCE' },
  { label: t('cancellation.reasonOtherUnexcused'), value: 'OTHER_UNEXCUSED' },
]

async function confirm() {
  loading.value = true
  try {
    emit('confirm', selectedReason.value)
    open.value = false
  } finally {
    loading.value = false
    selectedReason.value = null
  }
}
</script>
