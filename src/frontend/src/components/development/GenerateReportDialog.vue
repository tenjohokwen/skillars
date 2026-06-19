<template>
  <q-dialog :model-value="modelValue" @update:model-value="$emit('update:modelValue', $event)">
    <q-card style="min-width: 420px; max-width: 560px">
      <q-card-section class="row items-center q-pb-none">
        <div class="text-h6">{{ $t('development.report.generateTitle', { name: playerName }) }}</div>
        <q-space />
        <q-btn icon="close" flat round dense @click="close" />
      </q-card-section>

      <q-card-section>
        <q-input
          v-model="nextSteps"
          type="textarea"
          :label="$t('development.report.nextStepsLabel')"
          :hint="$t('development.report.nextStepsHint')"
          maxlength="500"
          counter
          autogrow
          outlined
        />
      </q-card-section>

      <q-card-actions align="right" class="q-pa-md">
        <q-btn flat :label="$t('common.cancel')" @click="close" />
        <q-btn
          color="primary"
          :label="$t('development.report.generate')"
          :loading="loading"
          :disable="!nextSteps.trim()"
          @click="submit"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, onUnmounted } from 'vue'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'
import { useDevelopmentStore } from 'src/stores/development.store'

const props = defineProps({
  modelValue: { type: Boolean, required: true },
  playerId: { type: [Number, String], required: true },
  playerName: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue', 'generated'])

const $q = useQuasar()
const { t } = useI18n()
const store = useDevelopmentStore()
const nextSteps = ref('')
const loading = ref(false)

onUnmounted(() => {
  loading.value = false
})

function close() {
  emit('update:modelValue', false)
  nextSteps.value = ''
  loading.value = false
}

async function submit() {
  if (!nextSteps.value.trim()) return
  loading.value = true
  try {
    await store.generateReport(props.playerId, nextSteps.value.trim())
    emit('generated')
    close()
    $q.notify({ type: 'positive', message: t('development.report.generateSuccess') })
  } catch {
    $q.notify({ type: 'negative', message: store.error ?? t('common.errorGeneric') })
  } finally {
    loading.value = false
  }
}
</script>
