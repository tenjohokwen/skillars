<template>
  <div>
    <div class="text-meta q-mb-md">{{ t('auth.coach.step5Helper') }}</div>

    <div class="row q-col-gutter-md items-start q-mb-md">
      <div v-if="previewUrl" class="col-auto">
        <q-img :src="previewUrl" class="profile-builder__avatar-preview" />
      </div>
      <div class="col">
        <q-file
          v-model="selectedFile"
          :label="t('auth.coach.step5PhotoLabel')"
          outlined
          accept=".jpg,.jpeg,.png"
          :max-file-size="5242880"
          @update:model-value="onFileSelected"
        >
          <template #prepend>
            <q-icon name="photo_camera" />
          </template>
        </q-file>
      </div>
    </div>

    <div class="q-gutter-sm">
      <q-btn
        :label="t('auth.coach.step5Upload')"
        class="btn-accent"
        @click="submit"
        :loading="loading"
        :disable="!selectedFile"
        unelevated
      />
      <q-btn
        :label="t('auth.coach.step5SkipLabel')"
        class="btn-ghost"
        @click="skip"
        :disable="loading"
        unelevated
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

<style lang="scss" scoped>
.profile-builder__avatar-preview {
  width: 88px;
  height: 88px;
  border-radius: 24px;
  border: 1px solid var(--border-soft);
}
</style>
