<template>
  <div>
    <div class="text-caption q-mb-xs">{{ t('auth.coach.step2Specialties') }}</div>
    <q-select
      v-model="form.specialties"
      :options="specialtyOptions"
      outlined
      multiple
      use-chips
      :rules="[v => (v && v.length > 0) || t('validation.required')]"
      class="q-mb-md"
    />
    <div class="text-caption q-mb-xs">{{ t('auth.coach.step2AgeGroups') }}</div>
    <div class="q-gutter-sm q-mb-md">
      <q-checkbox v-model="form.ageGroups" val="U10" label="U10" />
      <q-checkbox v-model="form.ageGroups" val="AGE_10_12" label="10–12" />
      <q-checkbox v-model="form.ageGroups" val="AGE_13_17" label="13–17" />
      <q-checkbox v-model="form.ageGroups" val="ADULT" label="18+" />
    </div>
    <q-btn
      :label="t('common.next')"
      color="primary"
      @click="submit"
      :loading="loading"
      unelevated
    />
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit'])

const specialtyOptions = [
  'Dribbling', 'Shooting', 'Passing', 'Defending', 'Goalkeeping',
  'Fitness', 'Tactics', 'Set Pieces', 'Heading', 'First Touch',
]

const form = reactive({
  specialties: [],
  ageGroups: [],
})

function submit() {
  if (!form.specialties.length || !form.ageGroups.length) return
  emit('submit', {
    specialties: form.specialties,
    ageGroups: form.ageGroups,
  })
}
</script>
