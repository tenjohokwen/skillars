<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-xl">
        <div class="text-page-title">{{ t('admin.appeals.title') }}</div>
        <div class="text-meta">{{ t('admin.appeals.subtitle') }}</div>
      </div>

      <div class="row q-mb-md">
        <q-btn-toggle
          :model-value="statusFilter"
          data-testid="appeal-status-filter"
          no-caps
          unelevated
          toggle-color="primary"
          :options="statusOptions"
          @update:model-value="changeStatus"
        />
      </div>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.appeals.loadError') }}
      </q-banner>

      <q-table
        :rows="rows"
        :columns="columns"
        row-key="appealId"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('admin.appeals.emptyState')"
      >
        <template #body-cell-subjectType="props">
          <q-td :props="props">{{ t(`appeals.subject.${props.row.subjectType}`) }}</q-td>
        </template>
        <template #body-cell-appellantName="props">
          <q-td :props="props">
            {{ props.row.appellantName || `#${props.row.appellantUserId}` }}
          </q-td>
        </template>
        <template #body-cell-status="props">
          <q-td :props="props">{{ t(`appeals.status.${props.row.status}`) }}</q-td>
        </template>
        <template #body-cell-enforcedAt="props">
          <q-td :props="props">{{ formatDate(props.row.enforcedAt) }}</q-td>
        </template>
        <template #body-cell-createdAt="props">
          <q-td :props="props">{{ formatDate(props.row.createdAt) }}</q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat
              dense
              size="sm"
              color="primary"
              :label="t('admin.appeals.view')"
              data-testid="appeal-row-view-btn"
              @click="openDetail(props.row.appealId)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
      </div>
    </div>

    <!-- Detail + decision dialog -->
    <q-dialog v-model="detailDialogOpen">
      <q-card
        data-testid="appeal-detail-dialog"
        style="min-width: 360px; max-width: 640px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.appeals.detailTitle') }}</div>
        </q-card-section>

        <q-card-section v-if="detailLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>

        <q-card-section
          v-else-if="detailLoadError"
          class="text-negative"
          data-testid="appeal-detail-load-error"
        >
          {{ t('admin.appeals.detailLoadError') }}
        </q-card-section>

        <template v-else-if="detail">
          <q-card-section data-testid="appeal-detail-content">
            <div>
              {{ t('admin.appeals.colSubject') }}: {{ t(`appeals.subject.${detail.subjectType}`) }}
            </div>
            <div>
              {{ t('admin.appeals.colAppellant') }}:
              {{ detail.appellantName || `#${detail.appellantUserId}` }}
            </div>
            <div>{{ t('admin.appeals.colEnforcedAt') }}: {{ formatDate(detail.enforcedAt) }}</div>
            <div>{{ t('admin.appeals.colCreatedAt') }}: {{ formatDate(detail.createdAt) }}</div>
            <div>
              {{ t('admin.appeals.colStatus') }}: {{ t(`appeals.status.${detail.status}`) }}
            </div>

            <div class="text-subtitle2 q-mt-md">{{ t('admin.appeals.reasonLabel') }}</div>
            <div class="appeal-text" data-testid="appeal-detail-reason">{{ detail.reason }}</div>

            <div class="text-subtitle2 q-mt-md">{{ t('admin.appeals.subjectLabel') }}</div>
            <div v-if="detail.subject" data-testid="appeal-detail-subject">
              <div v-for="(value, key) in detail.subject" :key="key">
                {{ subjectFieldLabel(key) }}: {{ value ?? '—' }}
              </div>
            </div>
            <div v-else class="text-meta" data-testid="appeal-detail-subject-gone">
              {{ t('admin.appeals.subjectGone') }}
            </div>

            <template v-if="detail.status !== 'OPEN'">
              <div class="text-subtitle2 q-mt-md">{{ t('admin.appeals.decisionLabel') }}</div>
              <div>{{ formatDate(detail.decidedAt) }}</div>
              <div v-if="detail.decisionNote" class="appeal-text">{{ detail.decisionNote }}</div>
              <div
                v-if="detail.status === 'OVERTURNED'"
                class="text-meta q-mt-xs"
                data-testid="appeal-detail-reversal"
              >
                {{
                  detail.reversalApplied
                    ? t('admin.appeals.reversalApplied')
                    : t('admin.appeals.reversalNotApplied')
                }}
              </div>
            </template>
          </q-card-section>

          <template v-if="detail.status === 'OPEN'">
            <q-card-section class="q-pt-none">
              <div class="text-meta q-mb-sm">{{ t('admin.appeals.overturnHint') }}</div>
              <q-input
                v-model="decisionNote"
                type="textarea"
                outlined
                autogrow
                maxlength="1000"
                counter
                data-testid="appeal-decision-note-input"
                :label="t('admin.appeals.decisionNoteLabel')"
              />
            </q-card-section>
            <q-card-actions align="right">
              <q-btn
                flat
                color="negative"
                data-testid="appeal-uphold-btn"
                :label="t('admin.appeals.uphold')"
                :loading="submitting === 'UPHELD'"
                :disable="!decisionNote.trim() || submitting !== null"
                @click="decide('UPHELD')"
              />
              <q-btn
                unelevated
                color="primary"
                data-testid="appeal-overturn-btn"
                :label="t('admin.appeals.overturn')"
                :loading="submitting === 'OVERTURNED'"
                :disable="!decisionNote.trim() || submitting !== null"
                @click="decide('OVERTURNED')"
              />
            </q-card-actions>
          </template>
        </template>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
// FR-ADM-004 appeals queue — follows AdminDisputesPage's server-paged queue + detail-dialog shape.
// The decision is final and race-safe server-side (AppealService.decide: conditional UPDATE on
// status = OPEN); a second admin deciding the same appeal gets 409 appeals.alreadyDecided.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'

const { t, te, locale } = useI18n()
const $q = useQuasar()

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)
const statusFilter = ref('OPEN')

const detailDialogOpen = ref(false)
const detailLoading = ref(false)
const detailLoadError = ref(false)
const detail = ref(null)
const selectedAppealId = ref(null)
const decisionNote = ref('')
// null, or the outcome currently being submitted.
const submitting = ref(null)

const statusOptions = computed(() => [
  { label: t('appeals.status.OPEN'), value: 'OPEN' },
  { label: t('appeals.status.UPHELD'), value: 'UPHELD' },
  { label: t('appeals.status.OVERTURNED'), value: 'OVERTURNED' },
])

const columns = computed(() => [
  {
    name: 'subjectType',
    label: t('admin.appeals.colSubject'),
    field: 'subjectType',
    align: 'left',
  },
  {
    name: 'appellantName',
    label: t('admin.appeals.colAppellant'),
    field: 'appellantName',
    align: 'left',
  },
  {
    name: 'enforcedAt',
    label: t('admin.appeals.colEnforcedAt'),
    field: 'enforcedAt',
    align: 'left',
  },
  { name: 'createdAt', label: t('admin.appeals.colCreatedAt'), field: 'createdAt', align: 'left' },
  { name: 'status', label: t('admin.appeals.colStatus'), field: 'status', align: 'left' },
  { name: 'actions', label: t('admin.appeals.colActions'), field: 'actions', align: 'center' },
])

// Guards both loaders against a late response for an earlier request overwriting a newer one
// (a quick filter change, or re-opening a different appeal before the first detail arrived).
let queueToken = 0
let detailToken = 0

onMounted(() => loadQueue())

async function loadQueue() {
  const token = ++queueToken
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.getAppealQueue(statusFilter.value, currentPage.value - 1)
    if (token !== queueToken) return
    // Deciding the last row of the last page leaves it empty — step back to the new last page.
    if (resp.content.length === 0 && currentPage.value > 1 && resp.totalPages > 0) {
      currentPage.value = Math.min(currentPage.value - 1, resp.totalPages)
      return loadQueue()
    }
    rows.value = resp.content
    totalPages.value = resp.totalPages
  } catch {
    if (token !== queueToken) return
    loadError.value = true
  } finally {
    if (token === queueToken) loading.value = false
  }
}

async function loadPage(page) {
  currentPage.value = page
  await loadQueue()
}

async function changeStatus(status) {
  if (status === statusFilter.value) return
  statusFilter.value = status
  currentPage.value = 1
  await loadQueue()
}

async function openDetail(appealId) {
  const token = ++detailToken
  selectedAppealId.value = appealId
  decisionNote.value = ''
  detailDialogOpen.value = true
  detailLoading.value = true
  detailLoadError.value = false
  detail.value = null
  try {
    const resp = await adminApi.getAppealDetail(appealId)
    if (token !== detailToken) return
    detail.value = resp
  } catch {
    if (token !== detailToken) return
    detailLoadError.value = true
  } finally {
    if (token === detailToken) detailLoading.value = false
  }
}

async function decide(outcome) {
  if (submitting.value !== null || !decisionNote.value.trim()) return
  submitting.value = outcome
  try {
    await adminApi.decideAppeal(selectedAppealId.value, {
      outcome,
      note: decisionNote.value.trim(),
    })
    detailDialogOpen.value = false
    $q.notify({
      message:
        outcome === 'OVERTURNED'
          ? t('admin.appeals.overturnSuccess')
          : t('admin.appeals.upholdSuccess'),
      type: 'positive',
    })
    await loadQueue()
  } catch (err) {
    const message = err?.response?.data?.errorMsg?.message
    if (message === 'appeals.alreadyDecided') {
      $q.notify({ message: t('admin.appeals.alreadyDecided'), type: 'negative' })
      detailDialogOpen.value = false
      await loadQueue()
    } else {
      $q.notify({ message: t('admin.appeals.decideFailed'), type: 'negative' })
    }
  } finally {
    submitting.value = null
  }
}

function subjectFieldLabel(key) {
  const path = `admin.appeals.subjectField.${key}`
  return te(path) ? t(path) : key
}

function formatDate(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    d,
  )
}
</script>

<style lang="scss" scoped>
.appeal-text {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
