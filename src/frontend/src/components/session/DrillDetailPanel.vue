<template>
  <!-- Mobile: bottom sheet — max-height applied here so Quasar's chrome is included in the 75vh cap -->
  <q-bottom-sheet
    v-if="isMobile"
    v-model="open"
    @hide="emit('close')"
    class="drill-detail-panel__sheet"
  >
    <div class="drill-detail-panel__content">
      <template v-if="drill">
        <div class="drill-detail-panel__video-area q-mb-md">
          <video
            v-if="drill.videoUrl && !videoFailed"
            controls
            :key="drill.videoUrl"
            :src="drill.videoUrl"
            class="drill-detail-panel__video"
            @error="handleVideoError"
          />
          <div v-else-if="videoFailed" class="drill-detail-panel__no-video text-center q-pa-md">
            <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
            <div class="text-caption text-secondary">
              {{ $t('session.drillLibrary.videoUnavailable') }}
            </div>
          </div>
          <div v-else class="drill-detail-panel__no-video text-center q-pa-md">
            <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
            <div class="text-caption text-secondary">
              {{ $t('session.drillLibrary.videoPreviewAfterUpload') }}
            </div>
          </div>
        </div>

        <q-list dense bordered separator class="q-mb-md rounded-borders">
          <q-item>
            <q-item-section>
              <q-item-label overline>{{ t('session.drillLibrary.detail.metadata') }}</q-item-label>
            </q-item-section>
          </q-item>
          <q-item>
            <q-item-section
              ><q-item-label caption>{{
                $t('session.drillLibrary.metaDifficulty')
              }}</q-item-label></q-item-section
            >
            <q-item-section side>{{ drill.metadata?.difficultyTier }}</q-item-section>
          </q-item>
          <q-item>
            <q-item-section
              ><q-item-label caption>{{
                $t('session.drillLibrary.metaGroupSize')
              }}</q-item-label></q-item-section
            >
            <q-item-section side>{{ drill.metadata?.recommendedGroupSize }}</q-item-section>
          </q-item>
          <q-item>
            <q-item-section
              ><q-item-label caption>{{
                $t('session.drillLibrary.metaEquipment')
              }}</q-item-label></q-item-section
            >
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
              <q-item-section side>{{ item.slu ?? '—' }} SLU</q-item-section>
            </q-item>
          </q-list>
        </div>

        <div v-if="drill.metadata?.setupDiagram" class="q-mb-md">
          <img
            :src="drill.metadata.setupDiagram"
            :alt="$t('session.drillLibrary.setupDiagram')"
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
          <div class="text-subtitle2 q-mb-sm">{{ $t('session.drillLibrary.tags') }}</div>
          <div class="row q-gutter-xs">
            <q-chip v-for="tag in drill.tags" :key="tag" size="sm">{{ tag }}</q-chip>
          </div>
        </div>

        <!-- feat/coach-workflow-gaps D1: edit/delete the coach's own PRIVATE drill (library only) -->
        <div v-if="canManage" class="row q-gutter-sm q-mt-md">
          <q-btn
            outline
            dense
            no-caps
            icon="edit"
            :label="t('session.drillEditor.edit')"
            data-test="drill-edit"
            @click="emit('edit', props.drill)"
          />
          <q-btn
            flat
            dense
            no-caps
            color="negative"
            icon="delete"
            :label="t('session.drillEditor.delete')"
            data-test="drill-delete"
            @click="confirmDeleteDrill"
          />
        </div>

        <!-- Add to session button (session-builder context only) -->
        <q-btn
          v-if="props.context === 'session-builder'"
          color="primary"
          icon="add"
          :label="t('session.builder.addToSession')"
          class="full-width q-mt-md"
          @click="emit('add-to-session', props.drill)"
        />

        <!-- Upload section (PRIVATE drills only, INSTRUCTOR+ tier) -->
        <div
          v-if="props.drill.libraryType === 'PRIVATE' && sessionStore.canUploadVideo === true"
          class="detail-panel__upload q-mt-md"
        >
          <!-- UX-DR22: the server refused the upload as tier-gated (the tier changed since the
               page loaded) — show the soft teaser with an upgrade CTA instead of a toast. -->
          <FeatureGateOverlay
            v-if="uploadGated"
            :required-tier="t('featureGate.tier.INSTRUCTOR')"
            :feature-label="t('session.drillLibrary.upload.startUpload')"
            upgrade-to="/coach/subscription"
            data-testid="drill-upload-gate"
          >
            <div class="q-pa-md" style="min-height: 240px">
              <q-icon name="videocam" size="32px" />
            </div>
          </FeatureGateOverlay>
          <template v-else-if="!props.drill.hasVideo">
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
                v-if="drill.videoUrl && !videoFailed"
                controls
                :key="drill.videoUrl"
                :src="drill.videoUrl"
                class="drill-detail-panel__video"
                @error="handleVideoError"
              />
              <div v-else-if="videoFailed" class="drill-detail-panel__no-video text-center q-pa-md">
                <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
                <div class="text-caption text-secondary">
                  {{ $t('session.drillLibrary.videoUnavailable') }}
                </div>
              </div>
              <div v-else class="drill-detail-panel__no-video text-center q-pa-md">
                <q-icon name="videocam_off" size="48px" class="q-mb-sm" />
                <div class="text-caption text-secondary">
                  {{ $t('session.drillLibrary.videoPreviewAfterUpload') }}
                </div>
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
                <q-item-section
                  ><q-item-label caption>{{
                    $t('session.drillLibrary.metaDifficulty')
                  }}</q-item-label></q-item-section
                >
                <q-item-section side>{{ drill.metadata?.difficultyTier }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section
                  ><q-item-label caption>{{
                    $t('session.drillLibrary.metaGroupSize')
                  }}</q-item-label></q-item-section
                >
                <q-item-section side>{{ drill.metadata?.recommendedGroupSize }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section
                  ><q-item-label caption>{{
                    $t('session.drillLibrary.metaEquipment')
                  }}</q-item-label></q-item-section
                >
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
                  <q-item-section side>{{ item.slu ?? '—' }} SLU</q-item-section>
                </q-item>
              </q-list>
            </div>

            <div v-if="drill.metadata?.setupDiagram" class="q-mb-md">
              <img
                :src="drill.metadata.setupDiagram"
                :alt="$t('session.drillLibrary.setupDiagram')"
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
              <div class="text-subtitle2 q-mb-sm">{{ $t('session.drillLibrary.tags') }}</div>
              <div class="row q-gutter-xs">
                <q-chip v-for="tag in drill.tags" :key="tag" size="sm">{{ tag }}</q-chip>
              </div>
            </div>

            <!-- feat/coach-workflow-gaps D1: edit/delete the coach's own PRIVATE drill (library only) -->
            <div v-if="canManage" class="row q-gutter-sm q-mt-md">
              <q-btn
                outline
                dense
                no-caps
                icon="edit"
                :label="t('session.drillEditor.edit')"
                data-test="drill-edit"
                @click="emit('edit', props.drill)"
              />
              <q-btn
                flat
                dense
                no-caps
                color="negative"
                icon="delete"
                :label="t('session.drillEditor.delete')"
                data-test="drill-delete"
                @click="confirmDeleteDrill"
              />
            </div>

            <!-- Add to session button (session-builder context only) -->
            <q-btn
              v-if="props.context === 'session-builder'"
              color="primary"
              icon="add"
              :label="t('session.builder.addToSession')"
              class="full-width q-mt-md"
              @click="emit('add-to-session', props.drill)"
            />

            <!-- Upload section (PRIVATE drills only, INSTRUCTOR+ tier) -->
            <div
              v-if="props.drill.libraryType === 'PRIVATE' && sessionStore.canUploadVideo === true"
              class="detail-panel__upload q-mt-md"
            >
              <!-- UX-DR22: the server refused the upload as tier-gated (the tier changed since the
                   page loaded) — show the soft teaser with an upgrade CTA instead of a toast. -->
              <FeatureGateOverlay
                v-if="uploadGated"
                :required-tier="t('featureGate.tier.INSTRUCTOR')"
                :feature-label="t('session.drillLibrary.upload.startUpload')"
                upgrade-to="/coach/subscription"
                data-testid="drill-upload-gate"
              >
                <div class="q-pa-md" style="min-height: 240px">
                  <q-icon name="videocam" size="32px" />
                </div>
              </FeatureGateOverlay>
              <template v-else-if="!props.drill.hasVideo">
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
import { computed, onUnmounted, ref, watch } from 'vue'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'
import { videoApi } from 'src/api/video.api'
import { useSessionStore } from 'src/stores/session.store'
import { useDrillVideoPlayback } from 'src/composables/useDrillVideoPlayback'
import FeatureGateOverlay from 'src/components/common/FeatureGateOverlay.vue'

defineOptions({ name: 'DrillDetailPanel' })

const props = defineProps({
  drill: { type: Object, default: null },
  isOpen: { type: Boolean, default: false },
  context: { type: String, default: null },
})

const emit = defineEmits([
  'close',
  'add-to-session',
  'video-error',
  'video-load-failed',
  'edit',
  'deleted',
])

const $q = useQuasar()
const { t } = useI18n()
const sessionStore = useSessionStore()

const isMobile = computed(() => $q.screen.lt.sm)

// D1: the PRIVATE library lists only the caller's own drills, so a PRIVATE drill shown outside the
// session builder is one the coach owns. The backend re-checks ownership on the locked row anyway.
const canManage = computed(
  () => props.drill?.libraryType === 'PRIVATE' && props.context !== 'session-builder',
)

// Local ref avoids Quasar's sheet snapping back while the parent processes the close event
const open = ref(props.isOpen)
watch(
  () => props.isOpen,
  (val) => {
    open.value = val
  },
)

// skillars-deferred-99 AC14: first load failure retries once with a fresh signed URL (silent),
// second failure shows a dismissible toast + an inline "unavailable" panel and stops. The
// composable resets its per-drill state as props.drill swaps (this panel is reused without a
// remount) and is navigation-away safe.
const { videoFailed, handleVideoError } = useDrillVideoPlayback(() => props.drill?.id, emit)

const sluBreakdown = computed(() => {
  if (!props.drill?.metadata) return []
  const { repDensity, skillWeighting } = props.drill.metadata
  // repDensity is a non-null primitive int on the backend today, so this guard currently only
  // protects against undefined/null arriving from stale caches or dev fixtures — it cannot yet
  // distinguish "coach never set this" from a legitimate zero at the API layer.
  return Object.entries(skillWeighting ?? {}).map(([skill, weight]) => ({
    skill,
    slu: repDensity != null && weight != null ? Math.round((repDensity * weight) / 100) : null,
  }))
})

const selectedVideoFile = ref(null)
const selectedVideoFileDuration = ref(0)
const isUploading = ref(false)
// Set when the server rejects an upload as tier-gated; reset when another drill is shown.
const uploadGated = ref(false)
watch(
  () => props.drill?.id,
  () => {
    uploadGated.value = false
  },
)
const uploadPercent = ref(0)
let tusUpload = null

// Watched, not onMounted-only: the drill library mounts this panel once with drill = null and swaps
// drills in later, so a mount-time check never saw a PRIVATE drill there and the demo-video upload
// section (gated on canUploadVideo) never appeared. The store caches the answer after one success.
watch(
  () => props.drill?.libraryType,
  (libraryType) => {
    if (libraryType === 'PRIVATE') sessionStore.fetchVideoUploadEligibility()
  },
  { immediate: true },
)

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
    video.onloadedmetadata = () => {
      URL.revokeObjectURL(url)
      resolve(video.duration)
    }
    video.onerror = () => {
      URL.revokeObjectURL(url)
      reject(new Error('metadata read failed'))
    }
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
    await runTusUpload(selectedVideoFile.value, creds)
    $q.notify({ type: 'positive', message: t('session.drillLibrary.upload.uploadStarted') })
    sessionStore.updateDrillVideoState(props.drill.id, { hasVideo: true, videoUrl: null })
  } catch (e) {
    const errorKey = e?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'QUOTA_EXCEEDED') {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.quotaExceeded') })
    } else if (errorKey === 'UPLOAD_RATE_LIMITED') {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.rateLimited') })
    } else if (errorKey === 'video.constraintViolated') {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.constraintViolated') })
    } else if (errorKey === 'DRILL_VIDEO_ALREADY_LINKED') {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.videoAlreadyLinked') })
    } else if (errorKey === 'DRILL_NOT_OWNED') {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.notOwned') })
    } else if (errorKey === 'security.featureGated') {
      uploadGated.value = true
    } else {
      $q.notify({ type: 'negative', message: t('session.drillLibrary.upload.uploadFailed') })
    }
  } finally {
    isUploading.value = false
    selectedVideoFile.value = null
    selectedVideoFileDuration.value = 0
  }
}

// Shares videoApi.createTusUpload with the generic video upload: it sets the Bunny TUS auth
// headers (AuthorizationSignature/AuthorizationExpire/LibraryId/VideoId) and the title/filetype
// metadata Bunny requires. The previous bare `new Upload(file, { uploadUrl })` sent none of them.
function runTusUpload(file, creds) {
  return new Promise((resolve, reject) => {
    tusUpload = videoApi.createTusUpload({
      file,
      signedUploadUrl: creds.signedUploadUrl,
      providerUploadId: creds.providerUploadId,
      tusAuthorizationSignature: creds.tusAuthorizationSignature,
      tusAuthorizationExpire: creds.tusAuthorizationExpire,
      tusLibraryId: creds.tusLibraryId,
      onProgress(bytesUploaded, bytesTotal) {
        uploadPercent.value = bytesTotal > 0 ? bytesUploaded / bytesTotal : 0
      },
      onSuccess: resolve,
      onError: reject,
    })
    tusUpload.start()
  })
}

function confirmDeleteDrill() {
  $q.dialog({
    title: t('session.drillEditor.deleteConfirmTitle'),
    message: t('session.drillEditor.deleteConfirmMsg'),
    ok: { label: t('session.drillEditor.delete'), color: 'negative' },
    cancel: true,
  }).onOk(async () => {
    const drillId = props.drill.id
    try {
      await sessionStore.deleteDrill(drillId)
      $q.notify({ type: 'positive', message: t('session.drillEditor.deleted') })
      emit('deleted', drillId)
    } catch (e) {
      const hasVideo = e?.response?.data?.errorMsg?.errorKey === 'DRILL_HAS_VIDEO'
      $q.notify({
        type: 'negative',
        message: hasVideo
          ? t('session.drillEditor.deleteHasVideo')
          : t('session.drillEditor.deleteFailed'),
      })
    }
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
