<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 420px; max-width: 520px">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.updateCoachPhoto') }}</div>
      </q-card-section>

      <q-card-section>
        <div class="row q-col-gutter-md items-start q-mb-md">
          <div v-if="previewUrl" class="col-auto">
            <q-img :src="previewUrl" class="profile-dialog__avatar-preview" />
          </div>
          <div v-else-if="props.current?.photoUrl" class="col-auto text-meta">
            {{ $t('profile.photoCurrentlySet') }}
          </div>
          <div class="col">
            <q-file
              v-model="selectedFile"
              :label="t('auth.coach.step5PhotoLabel')"
              outlined
              accept=".jpg,.jpeg,.png"
              :max-file-size="5242880"
              @update:model-value="onFileSelected"
              @rejected="onFileRejected"
            >
              <template #prepend>
                <q-icon name="photo_camera" />
              </template>
            </q-file>
          </div>
        </div>

        <q-banner v-if="rejectedMessage" class="bg-negative text-white q-mb-md" rounded dense>
          {{ rejectedMessage }}
        </q-banner>

        <q-banner v-if="confirmingDelete" class="profile-dialog__confirm q-mb-md" rounded dense>
          {{ $t('profile.confirmDeletePhoto') }}
          <template #action>
            <q-btn
              flat
              dense
              no-caps
              :label="$t('common.cancel')"
              @click="confirmingDelete = false"
            />
            <q-btn
              flat
              dense
              no-caps
              color="negative"
              :label="$t('common.confirm')"
              :loading="isDeleting"
              @click="handleDelete"
            />
          </template>
        </q-banner>

        <q-banner
          v-if="hasError && !isValidationError"
          class="bg-negative text-white q-mb-md"
          rounded
        >
          {{ errorMessage }}
          <template v-if="helpCode">
            <br />
            <small>{{ $t('error.helpCode') }}: {{ helpCode }}</small>
          </template>
        </q-banner>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn
          v-if="props.current?.photoUrl && !confirmingDelete"
          flat
          no-caps
          color="negative"
          :label="$t('profile.removePhoto')"
          @click="confirmingDelete = true"
        />
        <q-btn flat :label="$t('common.cancel')" @click="close" />
        <q-btn
          color="primary"
          :label="$t('common.save')"
          :loading="isSubmitting"
          :disable="!selectedFile"
          @click="handleUpload"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import {
  signUpload,
  confirmUpload,
  saveProfileBuilderStep,
  deleteCoachPhoto,
} from 'src/api/marketplace.api'
import { useAuthStore } from 'src/stores/auth.store'
import { useErrorHandler } from 'src/composables/useErrorHandler'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  current: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue', 'updated'])

const { t } = useI18n()
const $q = useQuasar()
const authStore = useAuthStore()
const { setError, clearError, hasError, errorMessage, isValidationError, helpCode } =
  useErrorHandler()

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const selectedFile = ref(null)
const previewUrl = ref(null)
const isSubmitting = ref(false)
const isDeleting = ref(false)
const confirmingDelete = ref(false)
const rejectedMessage = ref('')

// skillars-deferred-139 review Patch 10: revokes whatever blob URL is currently held, if any —
// called before replacing it with a new one and before clearing it, so every createObjectURL call
// is matched by exactly one revoke.
function revokePreviewUrl() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      selectedFile.value = null
      revokePreviewUrl()
      confirmingDelete.value = false
      rejectedMessage.value = ''
    }
  },
)

onUnmounted(() => {
  revokePreviewUrl()
})

function onFileSelected(file) {
  revokePreviewUrl()
  if (!file) {
    return
  }
  rejectedMessage.value = ''
  previewUrl.value = URL.createObjectURL(file)
}

// skillars-deferred-139 review Patch 9: without this, an oversize or wrong-type pick leaves q-file's
// model untouched and the dialog gives no feedback at all — it just looks like nothing happened.
function onFileRejected() {
  rejectedMessage.value = t('profile.photoRejected')
}

function close() {
  clearError()
  emit('update:modelValue', false)
}

async function handleUpload() {
  clearError()
  if (!selectedFile.value) return
  isSubmitting.value = true
  try {
    const file = selectedFile.value
    const userId = authStore.userId
    // skillars-deferred-139 review Patch 12: split('.').pop() with no dot check returns the whole
    // basename for a dotless filename (e.g. 'myphoto' -> 'myphoto', not ''); lastIndexOf makes the
    // no-dot case explicit instead of silently misusing the filename as the extension.
    const dotIndex = file.name.lastIndexOf('.')
    const extension = dotIndex > 0 ? file.name.slice(dotIndex + 1).toLowerCase() : ''
    const contentType = file.type

    const signRes = await signUpload({
      entity: 'coach_profile',
      entityId: String(userId),
      contentType,
      extension,
      fileSizeBytes: file.size,
    })

    const { key, uploadUrl } = signRes
    await fetch(uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': contentType },
    })

    const confirmRes = await confirmUpload(key, {
      contentType,
      fileSizeBytes: file.size,
    })

    const photoUrl = confirmRes?.key || key
    await saveProfileBuilderStep(5, { photoUrl })

    $q.notify({ type: 'positive', message: t('success.infoChanged') })
    emit('updated')
    close()
  } catch (err) {
    setError(err)
  } finally {
    isSubmitting.value = false
  }
}

async function handleDelete() {
  clearError()
  isDeleting.value = true
  try {
    await deleteCoachPhoto()
    $q.notify({ type: 'positive', message: t('success.infoChanged') })
    confirmingDelete.value = false
    emit('updated')
    close()
  } catch (err) {
    setError(err)
  } finally {
    isDeleting.value = false
  }
}
</script>

<style lang="scss" scoped>
.profile-dialog__avatar-preview {
  width: 88px;
  height: 88px;
  border-radius: 24px;
  border: 1px solid var(--border-soft);
}
.profile-dialog__confirm {
  background: var(--surface-warning) !important;
  color: var(--accent-warning) !important;
  border-radius: 8px !important;
  font-size: 13px;
}
</style>
