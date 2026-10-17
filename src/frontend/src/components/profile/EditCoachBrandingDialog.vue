<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 420px; max-width: 520px">
      <q-card-section>
        <div class="text-h6">{{ t('profile.editCoachBranding') }}</div>
      </q-card-section>

      <q-card-section>
        <div class="row q-col-gutter-md items-start q-mb-md">
          <div v-if="previewUrl" class="col-auto">
            <q-img :src="previewUrl" class="profile-dialog__avatar-preview" />
          </div>
          <div v-else-if="props.current?.logoSignedUrl" class="col-auto">
            <q-img :src="props.current.logoSignedUrl" class="profile-dialog__avatar-preview" />
          </div>
          <div class="col">
            <q-file
              v-model="selectedFile"
              :label="t('profile.brandingLogoLabel')"
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

        <q-input
          v-model="brandColour"
          :label="t('profile.brandColourLabel')"
          outlined
          :placeholder="t('profile.brandColourPlaceholder')"
          :rules="[validateColour]"
          class="q-mb-md"
        />

        <q-banner v-if="rejectedMessage" class="bg-negative text-white q-mb-md" rounded dense>
          {{ rejectedMessage }}
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
        <q-btn flat :label="$t('common.cancel')" @click="close" />
        <q-btn
          color="primary"
          :label="$t('common.save')"
          :loading="isSubmitting"
          :disable="!canSave"
          @click="handleSave"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { signUpload, confirmUpload } from 'src/api/marketplace.api'
import { saveCoachBranding } from 'src/api/development.api'
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

const HEX_COLOUR_RE = /^#[0-9A-Fa-f]{6}$/

const selectedFile = ref(null)
const previewUrl = ref(null)
const isSubmitting = ref(false)
const rejectedMessage = ref('')
const brandColour = ref('')

function validateColour(val) {
  if (!val) return true
  return HEX_COLOUR_RE.test(val) || t('profile.brandColourInvalid')
}

const canSave = computed(() => !brandColour.value || HEX_COLOUR_RE.test(brandColour.value))

function revokePreviewUrl() {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = null
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      brandColour.value = props.current?.brandColour || ''
      selectedFile.value = null
      rejectedMessage.value = ''
      clearError()
    } else {
      selectedFile.value = null
      revokePreviewUrl()
      rejectedMessage.value = ''
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  revokePreviewUrl()
})

function onFileSelected(file) {
  revokePreviewUrl()
  if (!file) return
  rejectedMessage.value = ''
  previewUrl.value = URL.createObjectURL(file)
}

function onFileRejected() {
  rejectedMessage.value = t('profile.photoRejected')
}

function close() {
  clearError()
  emit('update:modelValue', false)
}

async function handleSave() {
  clearError()
  if (!canSave.value) return
  isSubmitting.value = true
  try {
    // skillars-deferred-151 AC3 Fix 3: saveBranding is an unconditional full replace, not a merge —
    // a colour-only edit must resend the EXISTING logoKey unchanged, not null, or it silently wipes
    // an already-uploaded logo. The raw key is only available from props.current.logoKey (the GET
    // response's new field) — logoSignedUrl is derived/time-limited and cannot be resent as a key.
    let logoKey = props.current?.logoKey ?? null

    if (selectedFile.value) {
      const file = selectedFile.value
      const userId = authStore.userId
      const dotIndex = file.name.lastIndexOf('.')
      const extension = dotIndex > 0 ? file.name.slice(dotIndex + 1).toLowerCase() : ''
      const contentType = file.type

      const signRes = await signUpload({
        entity: 'coach_branding',
        entityId: String(userId),
        contentType,
        extension,
        fileSizeBytes: file.size,
      })

      const { key, uploadUrl } = signRes
      const res = await fetch(uploadUrl, {
        method: 'PUT',
        body: file,
        headers: { 'Content-Type': contentType },
      })
      if (!res.ok) {
        throw new Error(`Logo upload PUT failed with status ${res.status}`)
      }

      const confirmRes = await confirmUpload(key, {
        contentType,
        fileSizeBytes: file.size,
      })

      logoKey = confirmRes?.key || key
    }

    await saveCoachBranding(logoKey, brandColour.value || null)

    $q.notify({ type: 'positive', message: t('success.infoChanged') })
    emit('updated')
    close()
  } catch (err) {
    // Defensive: the ACADEMY gate could still change mid-session between page load and save.
    setError(err)
  } finally {
    isSubmitting.value = false
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
</style>
