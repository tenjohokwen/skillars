<template>
  <div>
    <q-file
      v-model="selectedFile"
      :label="t('auth.coach.step5PhotoLabel')"
      outlined
      accept=".jpg,.jpeg,.png"
      :max-file-size="5242880"
      @update:model-value="onFileSelected"
      class="q-mb-md"
    >
      <template #prepend>
        <q-icon name="photo_camera" />
      </template>
    </q-file>

    <div v-if="previewUrl" class="q-mb-md">
      <q-img :src="previewUrl" style="max-width: 200px; max-height: 200px" />
    </div>

    <div class="q-gutter-sm">
      <q-btn
        :label="t('auth.coach.step5Upload')"
        color="primary"
        @click="submit"
        :loading="loading"
        :disable="!selectedFile"
        unelevated
      />
      <q-btn
        :label="t('auth.coach.step5SkipLabel')"
        flat
        @click="skip"
        :disable="loading"
      />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps({ loading: Boolean })
const emit = defineEmits(['submit', 'skip'])

const selectedFile = ref(null)
const previewUrl = ref(null)

function onFileSelected(file) {
  if (!file) {
    previewUrl.value = null
    return
  }
  previewUrl.value = URL.createObjectURL(file)
}

function submit() {
  if (!selectedFile.value) return
  emit('submit', selectedFile.value)
}

function skip() {
  emit('skip')
}
</script>
