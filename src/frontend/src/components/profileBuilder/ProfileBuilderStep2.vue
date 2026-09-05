<template>
  <div>
    <div class="text-label q-mb-sm">{{ t('auth.coach.step2Specialties') }}</div>
    <q-select
      v-model="form.specialties"
      :label="t('auth.coach.step2Specialties')"
      :options="specialtyOptions"
      outlined
      multiple
      use-chips
      :rules="[(v) => (v && v.length > 0) || t('validation.required')]"
      class="q-mb-md"
    />
    <div class="text-label q-mb-sm">{{ t('auth.coach.step2AgeGroups') }}</div>
    <div class="profile-builder__age-groups q-mb-lg">
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
      <q-checkbox v-model="form.ageGroups" val="ADULT" :label="t('auth.coach.ageGroupAdult')" />
    </div>
    <q-btn
      :label="t('common.next')"
      class="btn-accent"
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

function submit() {
  if (!form.specialties.length || !form.ageGroups.length) return
  emit('submit', {
    specialties: form.specialties,
    ageGroups: form.ageGroups,
  })
}
</script>

<style lang="scss" scoped>
.profile-builder__age-groups {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 12px 16px;
  background: var(--surface-glass);
  border: 1px solid var(--border-soft);
  border-radius: 14px;
}
</style>
