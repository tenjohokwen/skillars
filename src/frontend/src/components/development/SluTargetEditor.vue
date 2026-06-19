<template>
  <q-dialog v-model="open" persistent>
    <q-card style="min-width: 380px">
      <q-card-section>
        <div class="text-h6">{{ $t('development.setTargetsLabel') }}</div>
      </q-card-section>

      <q-card-section class="q-gutter-sm">
        <div v-for="skill in skillDefinitions" :key="skill.code">
          <q-input
            v-model.number="localTargets[skill.code]"
            :label="$t('development.targetLabel', { skill: skill.code })"
            type="number"
            min="0"
            step="0.5"
            dense
            outlined
            clearable
          />
        </div>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn flat :label="$t('common.cancel')" v-close-popup />
        <q-btn color="primary" :label="$t('development.saveTargets')" @click="onSave" />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  skillDefinitions: { type: Array, default: () => [] },
  currentTargets: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue', 'save'])

const open = ref(props.modelValue)
watch(
  () => props.modelValue,
  (v) => (open.value = v),
)
watch(open, (v) => emit('update:modelValue', v))

const localTargets = ref({})

watch(
  () => props.currentTargets,
  (targets) => {
    localTargets.value = {}
    for (const t of targets) {
      localTargets.value[t.skillCode] = t.weeklyTargetSlu
    }
  },
  { immediate: true },
)

function onSave() {
  const payload = props.skillDefinitions.map((s) => ({
    skillCode: s.code,
    weeklyTargetSlu: localTargets.value[s.code] ?? null,
  }))
  emit('save', payload)
  open.value = false
}
</script>
