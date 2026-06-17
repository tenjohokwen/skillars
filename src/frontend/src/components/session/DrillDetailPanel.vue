<template>
  <!-- Mobile: bottom sheet — max-height applied here so Quasar's chrome is included in the 75vh cap -->
  <q-bottom-sheet v-if="isMobile" v-model="open" @hide="emit('close')" class="drill-detail-panel__sheet">
    <div class="drill-detail-panel__content">
      <template v-if="drill">
        <div class="drill-detail-panel__video-area q-mb-md">
          <video
            v-if="drill.videoUrl"
            controls
            :src="drill.videoUrl"
            class="drill-detail-panel__video"
          />
          <div v-else class="drill-detail-panel__no-video text-center q-pa-md">
            <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
            <div class="text-caption text-secondary">Video preview available after upload</div>
          </div>
        </div>

        <q-list dense bordered separator class="q-mb-md rounded-borders">
          <q-item>
            <q-item-section>
              <q-item-label overline>{{ t('session.drillLibrary.detail.metadata') }}</q-item-label>
            </q-item-section>
          </q-item>
          <q-item>
            <q-item-section><q-item-label caption>Difficulty</q-item-label></q-item-section>
            <q-item-section side>{{ drill.metadata?.difficultyTier }}</q-item-section>
          </q-item>
          <q-item>
            <q-item-section><q-item-label caption>Group Size</q-item-label></q-item-section>
            <q-item-section side>{{ drill.metadata?.recommendedGroupSize }}</q-item-section>
          </q-item>
          <q-item>
            <q-item-section><q-item-label caption>Equipment</q-item-label></q-item-section>
            <q-item-section side>{{
              (drill.metadata?.equipmentRequired ?? []).join(', ')
            }}</q-item-section>
          </q-item>
        </q-list>

        <div class="q-mb-md">
          <div class="text-subtitle2 q-mb-sm">
            {{ t('session.drillLibrary.detail.sluBreakdown') }}
          </div>
          <q-list dense>
            <q-item v-for="item in sluBreakdown" :key="item.skill">
              <q-item-section>{{ item.skill }}</q-item-section>
              <q-item-section side>{{ item.slu }} SLU</q-item-section>
            </q-item>
          </q-list>
        </div>

        <div v-if="drill.metadata?.setupDiagram" class="q-mb-md">
          <img
            :src="drill.metadata.setupDiagram"
            alt="Setup diagram"
            style="width: 100%; border-radius: 8px"
          />
        </div>

        <div class="q-mb-md">
          <div class="text-subtitle2 q-mb-sm">
            {{ t('session.drillLibrary.detail.coachingPoints') }}
          </div>
          <ul>
            <li
              v-for="(point, idx) in drill.metadata?.coachingPoints"
              :key="idx"
              class="text-body2"
            >
              {{ point }}
            </li>
          </ul>
        </div>

        <div v-if="drill.tags?.length" class="q-mb-md">
          <div class="text-subtitle2 q-mb-sm">Tags</div>
          <div class="row q-gutter-xs">
            <q-chip v-for="tag in drill.tags" :key="tag" size="sm">{{ tag }}</q-chip>
          </div>
        </div>

        <!-- Upload section (COACH drills only, INSTRUCTOR+ tier) -->
        <div v-if="props.drill.libraryType === 'COACH' && sessionStore.canUploadVideo === true"
             class="detail-panel__upload q-mt-md">
          <template v-if="!props.drill.hasVideo">
            <q-file
              v-model="selectedVideoFile"
              accept="video/*"
              :label="t('session.drillLibrary.upload.selectVideo')"
              dense
              outlined
              @update:model-value="onFileSelected"
            >
              <template #prepend>
                <q-icon name="videocam" />
              </template>
            </q-file>
            <q-btn
              v-if="selectedVideoFile"
              :loading="isUploading"
              :label="t('session.drillLibrary.upload.startUpload')"
              color="primary"
              class="q-mt-sm"
              @click="startUpload"
            />
            <q-linear-progress
              v-if="isUploading"
              :value="uploadPercent"
              class="q-mt-sm"
              color="primary"
            />
          </template>
          <template v-else>
            <q-btn
              flat
              dense
              color="negative"
              :label="t('session.drillLibrary.upload.removeVideo')"
              icon="delete"
              @click="confirmRemoveVideo"
            />
          </template>
        </div>
      </template>
    </div>
  </q-bottom-sheet>

  <!-- Desktop: right-side dialog -->
  <q-dialog v-else v-model="open" position="right" full-height @hide="emit('close')">
    <q-card class="drill-detail-panel__desktop-card glass-card">
      <q-card-section class="row items-center q-pb-none">
        <div class="text-h6">{{ drill?.name }}</div>
        <q-space />
        <q-btn icon="close" flat round dense v-close-popup />
      </q-card-section>
      <q-separator />
      <q-scroll-area style="height: calc(100% - 60px)">
        <q-card-section>
          <template v-if="drill">
            <div class="drill-detail-panel__video-area q-mb-md">
              <video
                v-if="drill.videoUrl"
                controls
                :src="drill.videoUrl"
                class="drill-detail-panel__video"
              />
              <div v-else class="drill-detail-panel__no-video text-center q-pa-md">
                <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
                <div class="text-caption text-secondary">Video preview available after upload</div>
              </div>
            </div>

            <q-list dense bordered separator class="q-mb-md rounded-borders">
              <q-item>
                <q-item-section>
                  <q-item-label overline>{{
                    t('session.drillLibrary.detail.metadata')
                  }}</q-item-label>
                </q-item-section>
              </q-item>
              <q-item>
                <q-item-section><q-item-label caption>Difficulty</q-item-label></q-item-section>
                <q-item-section side>{{ drill.metadata?.difficultyTier }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section><q-item-label caption>Group Size</q-item-label></q-item-section>
                <q-item-section side>{{ drill.metadata?.recommendedGroupSize }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section><q-item-label caption>Equipment</q-item-label></q-item-section>
                <q-item-section side>{{
                  (drill.metadata?.equipmentRequired ?? []).join(', ')
                }}</q-item-section>
              </q-item>
            </q-list>

            <div class="q-mb-md">
              <div class="text-subtitle2 q-mb-sm">
                {{ t('session.drillLibrary.detail.sluBreakdown') }}
              </div>
              <q-list dense>
                <q-item v-for="item in sluBreakdown" :key="item.skill">
                  <q-item-section>{{ item.skill }}</q-item-section>
                  <q-item-section side>{{ item.slu }} SLU</q-item-section>
                </q-item>
              </q-list>
            </div>

            <div v-if="drill.metadata?.setupDiagram" class="q-mb-md">
              <img
                :src="drill.metadata.setupDiagram"
                alt="Setup diagram"
                style="width: 100%; border-radius: 8px"
              />
            </div>

            <div class="q-mb-md">
              <div class="text-subtitle2 q-mb-sm">
                {{ t('session.drillLibrary.detail.coachingPoints') }}
              </div>
              <ul>
                <li
                  v-for="(point, idx) in drill.metadata?.coachingPoints"
                  :key="idx"
                  class="text-body2"
                >
                  {{ point }}
                </li>
              </ul>
            </div>

            <div v-if="drill.tags?.length" class="q-mb-md">
              <div class="text-subtitle2 q-mb-sm">Tags</div>
              <div class="row q-gutter-xs">
                <q-chip v-for="tag in drill.tags" :key="tag" size="sm">{{ tag }}</q-chip>
              </div>
            </div>

            <!-- Upload section (COACH drills only, INSTRUCTOR+ tier) -->
            <div v-if="props.drill.libraryType === 'COACH' && sessionStore.canUploadVideo === true"
                 class="detail-panel__upload q-mt-md">
              <template v-if="!props.drill.hasVideo">
                <q-file
                  v-model="selectedVideoFile"
                  accept="video/*"
                  :label="t('session.drillLibrary.upload.selectVideo')"
                  dense
                  outlined
                  @update:model-value="onFileSelected"
                >
                  <template #prepend>
                    <q-icon name="videocam" />
                  </template>
                </q-file>
                <q-btn
                  v-if="selectedVideoFile"
                  :loading="isUploading"
                  :label="t('session.drillLibrary.upload.startUpload')"
                  color="primary"
                  class="q-mt-sm"
                  @click="startUpload"
                />
                <q-linear-progress
                  v-if="isUploading"
                  :value="uploadPercent"
                  class="q-mt-sm"
                  color="primary"
                />
              </template>
              <template v-else>
                <q-btn
                  flat
                  dense
                  color="negative"
                  :label="t('session.drillLibrary.upload.removeVideo')"
                  icon="delete"
                  @click="confirmRemoveVideo"
                />
              </template>
            </div>
          </template>
        </q-card-section>
      </q-scroll-area>
    </q-card>
  </q-dialog>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'
import { Upload } from 'tus-js-client'
import { useSessionStore } from 'src/stores/session.store'

defineOptions({ name: 'DrillDetailPanel' })

const props = defineProps({
  drill: { type: Object, default: null },
  isOpen: { type: Boolean, default: false },
})

const emit = defineEmits(['close'])

const $q = useQuasar()
const { t } = useI18n()
const sessionStore = useSessionStore()

const isMobile = computed(() => $q.screen.lt.sm)

// Local ref avoids Quasar's sheet snapping back while the parent processes the close event
const open = ref(props.isOpen)
watch(() => props.isOpen, (val) => { open.value = val })

const sluBreakdown = computed(() => {
  if (!props.drill?.metadata) return []
  const { repDensity, skillWeighting } = props.drill.metadata
  return Object.entries(skillWeighting ?? {}).map(([skill, weight]) => ({
    skill,
    slu: Math.round((repDensity * weight) / 100),
  }))
})

const selectedVideoFile = ref(null)
const selectedVideoFileDuration = ref(0)
const isUploading = ref(false)
const uploadPercent = ref(0)
let tusUpload = null

onMounted(async () => {
  if (props.drill?.libraryType === 'COACH') {
    await sessionStore.fetchVideoUploadEligibility()
  }
})

onUnmounted(() => {
  tusUpload?.abort()
  sessionStore.uploadingDrillId = null
})

async function onFileSelected(file) {
  if (!file) {
    selectedVideoFileDuration.value = 0
    return
  }
  try {
    const duration = Math.round(await readVideoDuration(file))
    selectedVideoFileDuration.value = isFinite(duration) ? duration : 0
  } catch {
    selectedVideoFileDuration.value = 0
  }
}

function readVideoDuration(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const video = document.createElement('video')
    video.preload = 'metadata'
    video.onloadedmetadata = () => { URL.revokeObjectURL(url); resolve(video.duration) }
    video.onerror = () => { URL.revokeObjectURL(url); reject(new Error('metadata read failed')) }
    video.src = url
  })
}

async function startUpload() {
  if (!selectedVideoFile.value || isUploading.value) return
  isUploading.value = true
  uploadPercent.value = 0
  try {
    const creds = await sessionStore.initiateVideoUpload(
      props.drill.id,
      selectedVideoFile.value,
      selectedVideoFileDuration.value,
    )
    await runTusUpload(selectedVideoFile.value, creds.signedUploadUrl)
    $q.notify({ type: 'positive', message: t('session.drillLibrary.upload.uploadStarted') })
    sessionStore.updateDrillVideoState(props.drill.id, { hasVideo: true, videoUrl: null })
  } catch (e) {
    const helpCode = e?.response?.data?.helpCode
    if (helpCode === 'video.quotaExceeded') {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.quotaExceeded') })
    } else if (helpCode === 'video.constraintViolated') {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.constraintViolated') })
    } else {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.uploadFailed') })
    }
  } finally {
    isUploading.value = false
    selectedVideoFile.value = null
    selectedVideoFileDuration.value = 0
  }
}

function runTusUpload(file, signedUploadUrl) {
  return new Promise((resolve, reject) => {
    tusUpload = new Upload(file, {
      uploadUrl: signedUploadUrl,
      onProgress(bytesUploaded, bytesTotal) {
        uploadPercent.value = bytesTotal > 0 ? bytesUploaded / bytesTotal : 0
      },
      onSuccess: resolve,
      onError: reject,
    })
    tusUpload.start()
  })
}

function confirmRemoveVideo() {
  $q.dialog({
    title: t('session.drillLibrary.upload.removeConfirmTitle'),
    message: t('session.drillLibrary.upload.removeConfirmMsg'),
    ok: { label: t('session.drillLibrary.upload.removeConfirm'), color: 'negative' },
    cancel: true,
  }).onOk(async () => {
    try {
      await sessionStore.removeVideo(props.drill.id)
      $q.notify({ type: 'positive', message: t('session.drillLibrary.upload.videoRemoved') })
    } catch {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.removeFailed') })
    }
  })
}
</script>

<style scoped lang="scss">
.drill-detail-panel {
  &__sheet {
    max-height: 75vh;
    overflow-y: auto;
  }

  &__content {
    padding: 16px;
  }

  &__desktop-card {
    min-width: 420px;
    height: 100%;
  }

  &__video-area {
    background: var(--glass-surface, rgba(255, 255, 255, 0.05));
    border-radius: 8px;
    overflow: hidden;
    min-height: 160px;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__video {
    width: 100%;
    max-height: 280px;
    object-fit: contain;
  }

  &__no-video {
    color: var(--color-text-secondary);
    width: 100%;
  }
}
</style>
