<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.videos.title') }}</div>
        <div class="text-meta">{{ t('admin.videos.subtitle') }}</div>
      </div>

      <div class="row q-col-gutter-sm items-start q-mb-md">
        <div class="col-12 col-sm-4 col-md-3">
          <q-select
            v-model="filter"
            :options="filterOptions"
            :label="t('admin.videos.filterLabel')"
            emit-value
            map-options
            outlined
            dense
            data-testid="video-filter"
            @update:model-value="onFilterChange"
          />
        </div>
        <div class="col-12 col-sm-5 col-md-4">
          <q-input
            v-model="videoIdInput"
            outlined
            dense
            :label="t('admin.videos.videoIdLabel')"
            data-testid="video-id-input"
            @keyup.enter="openById"
          />
        </div>
        <div class="col-auto">
          <q-btn
            outline
            color="primary"
            :label="t('admin.videos.lookupAction')"
            :disable="!videoIdValid"
            data-testid="video-lookup-btn"
            @click="openById"
          />
        </div>
      </div>
      <div class="text-meta q-mb-md">{{ filterHint }}</div>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.videos.loadError') }}
      </q-banner>

      <q-table
        :rows="rows"
        :columns="columns"
        row-key="id"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('admin.videos.emptyState')"
      >
        <template #body-cell-state="props">
          <q-td :props="props">
            <q-chip dense :color="opColor(props.row.operationalState)" text-color="white">
              {{ opLabel(props.row.operationalState) }}
            </q-chip>
            <q-chip dense outline :color="accessColor(props.row.accessState)">
              {{ accessLabel(props.row.accessState) }}
            </q-chip>
          </q-td>
        </template>
        <template #body-cell-flaggedLayers="props">
          <q-td :props="props">
            <span v-if="!props.row.flaggedLayers || !props.row.flaggedLayers.length">—</span>
            <q-chip
              v-for="layer in props.row.flaggedLayers"
              :key="layer"
              dense
              outline
              color="negative"
            >
              {{ layerLabel(layer) }}
            </q-chip>
          </q-td>
        </template>
        <template #body-cell-updatedAt="props">
          <q-td :props="props">{{ formatDate(props.row.updatedAt) }}</q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat
              dense
              size="sm"
              color="primary"
              :label="t('admin.videos.view')"
              data-testid="video-row-view-btn"
              @click="openDetail(props.row)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
      </div>
    </div>

    <!-- Video detail -->
    <q-dialog v-model="detailOpen">
      <q-card
        data-testid="video-detail-dialog"
        style="min-width: 340px; max-width: 720px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.videos.detailTitle') }}</div>
        </q-card-section>
        <q-card-section v-if="detailLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>
        <q-card-section v-else-if="detailError" class="text-negative">
          {{ detailError }}
        </q-card-section>
        <template v-else-if="video">
          <q-card-section data-testid="video-detail-content">
            <div class="text-subtitle1 text-weight-medium">{{ video.title }}</div>
            <div class="video-id text-meta">{{ video.id }}</div>
            <div class="row q-gutter-sm q-mt-xs">
              <q-chip dense :color="opColor(video.operationalState)" text-color="white">
                {{ opLabel(video.operationalState) }}
              </q-chip>
              <q-chip dense outline :color="accessColor(video.accessState)">
                {{ accessLabel(video.accessState) }}
              </q-chip>
              <q-chip
                v-for="layer in video.flaggedLayers || []"
                :key="layer"
                dense
                outline
                color="negative"
              >
                {{ t('admin.videos.flaggedBy', { layer: layerLabel(layer) }) }}
              </q-chip>
            </div>
            <q-list dense class="q-mt-sm">
              <q-item>
                <q-item-section>{{ t('admin.videos.ownerLabel') }}</q-item-section>
                <q-item-section side>{{ video.ownerId }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section>{{ t('admin.videos.typeLabel') }}</q-item-section>
                <q-item-section side>{{ video.videoType || '—' }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section>{{ t('admin.videos.createdLabel') }}</q-item-section>
                <q-item-section side>{{ formatDate(video.createdAt) }}</q-item-section>
              </q-item>
              <q-item>
                <q-item-section>{{ t('admin.videos.updatedLabel') }}</q-item-section>
                <q-item-section side>{{ formatDate(video.updatedAt) }}</q-item-section>
              </q-item>
              <q-item v-if="video.accessState === 'BLOCKED' && video.lifecycleLockedAt">
                <q-item-section>{{ t('admin.videos.blockedSinceLabel') }}</q-item-section>
                <q-item-section side>{{ formatDate(video.lifecycleLockedAt) }}</q-item-section>
              </q-item>
              <q-item v-if="video.archivedAt">
                <q-item-section>{{ t('admin.videos.archivedAtLabel') }}</q-item-section>
                <q-item-section side>{{ formatDate(video.archivedAt) }}</q-item-section>
              </q-item>
            </q-list>
          </q-card-section>

          <q-card-section
            v-if="!isGone && video.accessState === 'ARCHIVED'"
            class="text-meta"
            data-testid="video-archived-hint"
          >
            {{ t('admin.videos.archivedHint') }}
          </q-card-section>
          <q-card-section v-if="isGone" class="text-meta">
            {{ t('admin.videos.goneHint') }}
          </q-card-section>
          <q-card-actions v-else-if="!isLimitedAdmin" align="left" class="q-px-md q-gutter-sm">
            <q-btn
              v-if="video.accessState === 'ACTIVE'"
              unelevated
              color="warning"
              text-color="dark"
              :label="t('admin.videos.blockAction')"
              data-testid="video-block-btn"
              @click="openAction('block')"
            />
            <q-btn
              v-if="video.accessState === 'BLOCKED'"
              unelevated
              color="positive"
              :label="t('admin.videos.restoreAction')"
              data-testid="video-restore-btn"
              @click="openAction('restore')"
            />
            <q-btn
              outline
              color="primary"
              :label="t('admin.videos.reconcileAction')"
              data-testid="video-reconcile-btn"
              @click="openAction('reconcile')"
            />
            <q-btn
              unelevated
              color="negative"
              :label="t('admin.videos.deleteAction')"
              data-testid="video-delete-btn"
              @click="openAction('delete')"
            />
          </q-card-actions>

          <q-separator class="q-my-sm" />

          <q-card-section>
            <div class="row items-center justify-between">
              <div class="text-subtitle2">{{ t('admin.videos.sessionsTitle') }}</div>
              <q-btn
                flat
                dense
                size="sm"
                color="primary"
                :label="t('admin.videos.loadSessions')"
                :loading="sessionsLoading"
                data-testid="video-sessions-btn"
                @click="loadSessions"
              />
            </div>
            <div v-if="sessionsError" class="text-negative">
              {{ t('admin.videos.sessionsLoadError') }}
            </div>
            <div v-else-if="sessions && !sessions.length" class="text-meta">
              {{ t('admin.videos.noSessions') }}
            </div>
            <q-list v-else-if="sessions" dense separator data-testid="video-sessions-list">
              <q-item v-for="s in sessions" :key="s.id">
                <q-item-section>
                  <q-item-label>{{ sessionStatusLabel(s.status) }}</q-item-label>
                  <q-item-label caption>
                    {{ t('admin.videos.sessionCreated') }} {{ formatDate(s.createdAt) }} ·
                    {{ t('admin.videos.sessionExpires') }} {{ formatDate(s.expiresAt) }} ·
                    {{ formatBytes(s.reservedBytes) }}
                  </q-item-label>
                </q-item-section>
              </q-item>
            </q-list>
          </q-card-section>

          <q-card-actions align="right">
            <q-btn flat :label="t('common.close')" v-close-popup />
          </q-card-actions>
        </template>
      </q-card>
    </q-dialog>

    <!-- Confirmation shared by block / restore / reconcile / delete -->
    <q-dialog v-model="actionOpen">
      <q-card
        data-testid="video-action-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ actionTitle }}</div>
          <div class="text-body2 q-mt-sm">{{ actionHint }}</div>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            :color="actionMode === 'delete' ? 'negative' : 'primary'"
            :label="t('common.confirm')"
            :loading="submitting"
            data-testid="video-action-submit-btn"
            @click="submitAction"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { useAdminTier } from 'src/composables/useAdminTier'
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useRoute } from 'vue-router'
import { adminApi } from 'src/api/admin.api'
import { formatDateTime, isUuid } from 'src/utils/adminFormat'

const { t, locale } = useI18n()
const { isLimitedAdmin } = useAdminTier()
const $q = useQuasar()
const route = useRoute()

const GONE_STATES = ['DELETED', 'PURGED']

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)
const filter = ref('ATTENTION')
const videoIdInput = ref('')

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const video = ref(null)
const selectedVideoId = ref(null)

const sessions = ref(null)
const sessionsLoading = ref(false)
const sessionsError = ref(false)

const actionOpen = ref(false)
const actionMode = ref(null)
const submitting = ref(false)

const videoIdValid = computed(() => isUuid(videoIdInput.value.trim()))
const isGone = computed(() => GONE_STATES.includes(video.value?.operationalState))

const filterOptions = computed(() => [
  { label: t('admin.videos.filters.ATTENTION'), value: 'ATTENTION' },
  { label: t('admin.videos.filters.LOCKED'), value: 'LOCKED' },
  { label: t('admin.videos.filters.FAILED'), value: 'FAILED' },
  { label: t('admin.videos.filters.BLOCKED'), value: 'BLOCKED' },
])

const filterHints = computed(() => ({
  ATTENTION: t('admin.videos.filterHints.ATTENTION'),
  LOCKED: t('admin.videos.filterHints.LOCKED'),
  FAILED: t('admin.videos.filterHints.FAILED'),
  BLOCKED: t('admin.videos.filterHints.BLOCKED'),
}))
const filterHint = computed(() => filterHints.value[filter.value] ?? '')

const opLabels = computed(() => ({
  UPLOADING: t('admin.videos.opStates.UPLOADING'),
  PROCESSING: t('admin.videos.opStates.PROCESSING'),
  SCANNING: t('admin.videos.opStates.SCANNING'),
  TRANSCODING: t('admin.videos.opStates.TRANSCODING'),
  READY: t('admin.videos.opStates.READY'),
  LOCKED: t('admin.videos.opStates.LOCKED'),
  HIDDEN: t('admin.videos.opStates.HIDDEN'),
  REJECTED: t('admin.videos.opStates.REJECTED'),
  FAILED: t('admin.videos.opStates.FAILED'),
  DELETED: t('admin.videos.opStates.DELETED'),
  PURGED: t('admin.videos.opStates.PURGED'),
}))

const accessLabels = computed(() => ({
  ACTIVE: t('admin.videos.accessStates.ACTIVE'),
  BLOCKED: t('admin.videos.accessStates.BLOCKED'),
  ARCHIVED: t('admin.videos.accessStates.ARCHIVED'),
}))

const layerLabels = computed(() => ({
  ARACHNID: t('admin.videos.layers.ARACHNID'),
  VIDEOINTEL: t('admin.videos.layers.VIDEOINTEL'),
  MINOR_GATE: t('admin.videos.layers.MINOR_GATE'),
}))

const sessionStatusLabels = computed(() => ({
  PENDING: t('admin.videos.sessionStatuses.PENDING'),
  COMMITTED: t('admin.videos.sessionStatuses.COMMITTED'),
  EXPIRED: t('admin.videos.sessionStatuses.EXPIRED'),
}))

const columns = computed(() => [
  { name: 'title', label: t('admin.videos.colTitle'), field: 'title', align: 'left' },
  { name: 'state', label: t('admin.videos.colState'), field: 'operationalState', align: 'left' },
  {
    name: 'flaggedLayers',
    label: t('admin.videos.colFlagged'),
    field: 'flaggedLayers',
    align: 'left',
  },
  { name: 'ownerId', label: t('admin.videos.colOwner'), field: 'ownerId', align: 'left' },
  { name: 'updatedAt', label: t('admin.videos.colUpdated'), field: 'updatedAt', align: 'left' },
  { name: 'actions', label: t('admin.videos.colActions'), field: 'actions', align: 'center' },
])

const actionTitles = computed(() => ({
  block: t('admin.videos.blockTitle'),
  restore: t('admin.videos.restoreTitle'),
  reconcile: t('admin.videos.reconcileTitle'),
  delete: t('admin.videos.deleteTitle'),
}))
const actionHints = computed(() => ({
  block: t('admin.videos.blockHint'),
  restore:
    video.value?.operationalState === 'READY'
      ? t('admin.videos.restoreHint')
      : t('admin.videos.restoreNotPlayableHint'),
  reconcile: t('admin.videos.reconcileHint'),
  delete: t('admin.videos.deleteHint'),
}))
const actionTitle = computed(() => actionTitles.value[actionMode.value] ?? '')
const actionHint = computed(() => actionHints.value[actionMode.value] ?? '')

function opLabel(s) {
  return opLabels.value[s] ?? s
}
function accessLabel(s) {
  return accessLabels.value[s] ?? s
}
function layerLabel(l) {
  return layerLabels.value[l] ?? l
}
function sessionStatusLabel(s) {
  return sessionStatusLabels.value[s] ?? s
}
function opColor(s) {
  if (s === 'LOCKED' || s === 'FAILED' || s === 'REJECTED') return 'negative'
  if (s === 'READY') return 'positive'
  if (GONE_STATES.includes(s)) return 'grey-7'
  return 'info'
}
function accessColor(s) {
  return { ACTIVE: 'positive', BLOCKED: 'warning', ARCHIVED: 'grey-7' }[s] ?? 'grey-7'
}

onMounted(async () => {
  await loadList()
  const id = route?.query?.videoId
  if (typeof id === 'string' && isUuid(id)) {
    await openDetailById(id)
  }
})

async function loadList() {
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.getVideoReviewQueue(filter.value, currentPage.value - 1)
    if (resp.content.length === 0 && currentPage.value > 1 && resp.totalPages > 0) {
      currentPage.value = Math.min(currentPage.value - 1, resp.totalPages)
      return loadList()
    }
    rows.value = resp.content
    totalPages.value = resp.totalPages
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadPage(page) {
  currentPage.value = page
  await loadList()
}

async function onFilterChange() {
  currentPage.value = 1
  await loadList()
}

async function openById() {
  if (!videoIdValid.value) return
  await openDetailById(videoIdInput.value.trim())
}

function openDetail(row) {
  // The queue row already carries everything the detail shows; re-fetch anyway so actions are
  // offered against the current state rather than the possibly stale list snapshot.
  return openDetailById(row.id)
}

let detailToken = 0

async function openDetailById(videoId) {
  selectedVideoId.value = videoId
  sessions.value = null
  sessionsError.value = false
  detailOpen.value = true
  await reloadDetail()
}

async function reloadDetail() {
  const token = ++detailToken
  detailLoading.value = true
  detailError.value = ''
  video.value = null
  try {
    const resp = await adminApi.getAdminVideo(selectedVideoId.value)
    if (token !== detailToken) return
    video.value = resp
  } catch (err) {
    if (token !== detailToken) return
    detailError.value =
      err?.response?.status === 404 ? t('admin.videos.notFound') : t('admin.videos.detailLoadError')
  } finally {
    if (token === detailToken) detailLoading.value = false
  }
}

async function loadSessions() {
  sessionsLoading.value = true
  sessionsError.value = false
  try {
    sessions.value = await adminApi.getVideoUploadSessions(selectedVideoId.value)
  } catch {
    sessionsError.value = true
  } finally {
    sessionsLoading.value = false
  }
}

function openAction(mode) {
  actionMode.value = mode
  actionOpen.value = true
}

async function submitAction() {
  const videoId = selectedVideoId.value
  submitting.value = true
  try {
    let message = t('admin.videos.actionSuccess')
    if (actionMode.value === 'block') {
      await adminApi.setVideoAccessState(videoId, 'BLOCKED')
    } else if (actionMode.value === 'restore') {
      await adminApi.setVideoAccessState(videoId, 'ACTIVE')
    } else if (actionMode.value === 'reconcile') {
      const resp = await adminApi.reconcileVideo(videoId)
      message = resp?.incident
        ? t('admin.videos.reconcileCorrected', { state: opLabel(resp.operationalState) })
        : t('admin.videos.reconcileNoChange')
    } else if (actionMode.value === 'delete') {
      await adminApi.deleteVideo(videoId)
      message = t('admin.videos.deleteSuccess')
    }
    actionOpen.value = false
    $q.notify({ message, type: 'positive' })
    await Promise.all([reloadDetail(), loadList()])
  } catch (err) {
    const status = err?.response?.status
    let message = t('admin.videos.actionFailed')
    if (status === 409) message = t('admin.videos.actionConflict')
    else if (status === 502) message = t('admin.videos.providerUnavailable')
    else if (status === 404) message = t('admin.videos.notFound')
    $q.notify({ message, type: 'negative' })
    // A failure can still leave a partial effect behind (deleteVideo commits DELETED before it
    // releases quota, and rethrows a release failure), so always re-read what is really there.
    await Promise.all([reloadDetail(), loadList()])
  } finally {
    submitting.value = false
  }
}

function formatBytes(bytes) {
  const n = Number(bytes ?? 0)
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(1)} GB`
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(1)} MB`
  return `${Math.round(n / 1024)} KB`
}

function formatDate(iso) {
  return formatDateTime(iso, locale.value)
}
</script>

<style scoped>
.video-id {
  font-family: monospace;
}
</style>
