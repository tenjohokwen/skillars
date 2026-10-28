<template>
  <q-dialog v-model="dialogVisible" persistent>
    <q-card style="min-width: 320px; max-width: 560px; width: 100%">
      <q-card-section>
        <div class="text-h6">{{ $t('profile.coachGalleryTitle') }}</div>
        <div class="text-caption" style="color: var(--text-secondary)">
          {{ $t('profile.coachGalleryHelp', { max: MAX_ITEMS }) }}
        </div>
      </q-card-section>

      <q-card-section>
        <div v-if="loading" class="flex flex-center q-py-md">
          <q-spinner-dots size="32px" color="primary" />
        </div>
        <template v-else>
          <div v-if="!items.length" class="text-body2 q-mb-md" style="color: var(--text-secondary)">
            {{ $t('profile.coachGalleryEmpty') }}
          </div>
          <div v-else class="coach-gallery__grid q-mb-md">
            <div
              v-for="item in items"
              :key="item.id"
              class="coach-gallery__item"
              data-test="gallery-item"
            >
              <q-img :src="item.fileUrl" :ratio="1" class="coach-gallery__img" />
              <q-btn
                round
                dense
                size="sm"
                color="negative"
                icon="delete"
                class="coach-gallery__delete"
                :loading="deletingId === item.id"
                :disable="busy"
                :aria-label="$t('profile.coachGalleryRemove')"
                data-test="gallery-delete"
                @click="removeItem(item)"
              />
            </div>
          </div>

          <q-file
            v-if="items.length < MAX_ITEMS"
            v-model="selectedFile"
            :label="$t('profile.coachGalleryAdd')"
            outlined
            dense
            accept=".jpg,.jpeg,.png,.webp"
            :max-file-size="MAX_BYTES"
            :disable="busy"
            @rejected="onFileRejected"
          >
            <template #prepend>
              <q-icon name="add_photo_alternate" />
            </template>
          </q-file>
          <div v-else class="text-caption" style="color: var(--text-secondary)">
            {{ $t('profile.coachGalleryFull', { max: MAX_ITEMS }) }}
          </div>
        </template>

        <q-banner v-if="message" class="bg-negative text-white q-mt-md" rounded dense>
          {{ message }}
        </q-banner>
      </q-card-section>

      <q-card-actions align="right">
        <q-btn flat :label="$t('common.close')" :disable="busy" @click="close" />
        <q-btn
          v-if="items.length < MAX_ITEMS"
          color="primary"
          :label="$t('profile.coachGalleryUpload')"
          :loading="uploading"
          :disable="!selectedFile || busy"
          data-test="gallery-upload"
          @click="upload"
        />
      </q-card-actions>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import {
  signUpload,
  confirmUpload,
  getCoachGallery,
  addCoachGalleryImage,
  deleteCoachGalleryItem,
  deleteStorageObject,
} from 'src/api/marketplace.api'
import { useAuthStore } from 'src/stores/auth.store'

defineOptions({ name: 'EditCoachGalleryDialog' })

// Mirrors CoachGalleryService (MAX_ITEMS, MAX_IMAGE_BYTES, ALLOWED_CONTENT_TYPES) — the server
// re-validates every one of these against the confirmed storage object.
const MAX_ITEMS = 6
const MAX_BYTES = 5 * 1024 * 1024

const props = defineProps({
  modelValue: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'updated'])

const { t } = useI18n()
const $q = useQuasar()
const authStore = useAuthStore()

const dialogVisible = computed({
  get: () => props.modelValue,
  set: (val) => emit('update:modelValue', val),
})

const items = ref([])
const loading = ref(false)
const uploading = ref(false)
const deletingId = ref(null)
const selectedFile = ref(null)
const message = ref('')
const busy = computed(() => uploading.value || deletingId.value !== null)

// Backend MarketplaceException keys (CoachGalleryService) → user-facing copy.
function errorMessage(e) {
  switch (e?.response?.data?.errorMsg?.errorKey) {
    case 'marketplace.galleryFull':
      return t('profile.coachGalleryFull', { max: MAX_ITEMS })
    case 'marketplace.galleryInvalidType':
      return t('profile.coachGalleryInvalidType')
    case 'marketplace.galleryFileTooLarge':
      return t('profile.coachGalleryTooLarge')
    default:
      return t('profile.coachGalleryFailed')
  }
}

async function load() {
  loading.value = true
  message.value = ''
  try {
    items.value = (await getCoachGallery()) ?? []
  } catch (e) {
    items.value = []
    message.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  (visible) => {
    selectedFile.value = null
    message.value = ''
    if (visible) load()
  },
  { immediate: true },
)

function onFileRejected() {
  message.value = t('profile.coachGalleryRejected')
}

async function upload() {
  if (!selectedFile.value || busy.value) return
  message.value = ''
  uploading.value = true
  try {
    const file = selectedFile.value
    const dotIndex = file.name.lastIndexOf('.')
    const extension = dotIndex > 0 ? file.name.slice(dotIndex + 1).toLowerCase() : ''
    const contentType = file.type

    // Same sign → PUT → confirm flow as the profile photo (EditCoachPhotoDialog); the
    // coach_gallery entity is owner-bound to the caller's own user id server-side.
    const { key, uploadUrl } = await signUpload({
      entity: 'coach_gallery',
      entityId: String(authStore.userId),
      contentType,
      extension,
      fileSizeBytes: file.size,
    })
    const putRes = await fetch(uploadUrl, {
      method: 'PUT',
      body: file,
      headers: { 'Content-Type': contentType },
    })
    if (putRes && putRes.ok === false) throw new Error(`upload failed: ${putRes.status}`)
    const confirmRes = await confirmUpload(key, { contentType, fileSizeBytes: file.size })

    const storedKey = confirmRes?.key || key
    let added
    try {
      added = await addCoachGalleryImage(storedKey)
    } catch (linkErr) {
      // The file is uploaded and confirmed but the gallery refused it — don't leave an orphan
      // counting against the coach's storage quota. Best effort: the original error is what we show.
      deleteStorageObject(storedKey).catch(() => {})
      throw linkErr
    }
    if (!items.value.some((i) => i.id === added.id)) items.value.push(added)
    selectedFile.value = null
    $q.notify({ type: 'positive', message: t('profile.coachGalleryAdded') })
    emit('updated')
  } catch (e) {
    message.value = errorMessage(e)
  } finally {
    uploading.value = false
  }
}

async function removeItem(item) {
  if (busy.value) return
  message.value = ''
  deletingId.value = item.id
  try {
    await deleteCoachGalleryItem(item.id)
    items.value = items.value.filter((i) => i.id !== item.id)
    $q.notify({ type: 'positive', message: t('profile.coachGalleryRemoved') })
    emit('updated')
  } catch (e) {
    message.value = errorMessage(e)
  } finally {
    deletingId.value = null
  }
}

function close() {
  emit('update:modelValue', false)
}
</script>

<style lang="scss" scoped>
.coach-gallery__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
  gap: 8px;
}

.coach-gallery__item {
  position: relative;
}

.coach-gallery__img {
  border-radius: 8px;
  border: 1px solid var(--border-soft);
}

.coach-gallery__delete {
  position: absolute;
  top: 4px;
  right: 4px;
}
</style>
