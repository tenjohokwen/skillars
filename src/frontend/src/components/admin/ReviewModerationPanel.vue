<template>
  <div>
    <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
      {{ t('admin.reviewModeration.loadError') }}
    </q-banner>

    <q-table
      :rows="rows"
      :columns="columns"
      row-key="reviewId"
      flat
      :loading="loading"
      :pagination="{ rowsPerPage: 20 }"
      hide-pagination
      :no-data-label="t('admin.reviewModeration.emptyState')"
    >
      <template #body-cell-rating="props">
        <q-td :props="props">
          <q-rating :model-value="props.row.rating" readonly size="1em" color="amber" />
        </q-td>
      </template>
      <template #body-cell-body="props">
        <q-td :props="props" class="review-body-cell">{{ truncate(props.row.body) }}</q-td>
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
            :label="t('admin.reviewModeration.view')"
            data-testid="review-row-view-btn"
            @click="openDetail(props.row)"
          />
        </q-td>
      </template>
    </q-table>

    <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
      <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
    </div>

    <!-- Review detail -->
    <q-dialog v-model="detailDialogOpen">
      <q-card
        v-if="selected"
        data-testid="review-detail-dialog"
        style="min-width: 360px; max-width: 640px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.reviewModeration.detailTitle') }}</div>
          <div class="text-meta">{{ selected.coachName }} · {{ selected.authorRole }}</div>
        </q-card-section>
        <q-card-section>
          <q-rating :model-value="selected.rating" readonly size="1.2em" color="amber" />
          <div class="review-body q-mt-sm">{{ selected.body }}</div>
          <div v-if="selected.heldReason" class="q-mt-sm">
            {{ t('admin.reviewModeration.heldReasonLabel') }}: {{ selected.heldReason }}
          </div>
          <div class="q-mt-md text-subtitle2">
            {{ t('admin.reviewModeration.flagsLabel') }} ({{ selected.flagCount }})
          </div>
          <q-list v-if="selected.flags && selected.flags.length" dense>
            <q-item v-for="(f, i) in selected.flags" :key="i">
              <q-item-section>
                <q-item-label>{{ f.reason }}</q-item-label>
                <q-item-label v-if="f.details" caption>{{ f.details }}</q-item-label>
              </q-item-section>
              <q-item-section side>{{ formatDate(f.createdAt) }}</q-item-section>
            </q-item>
          </q-list>
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.close')" v-close-popup />
          <q-btn
            flat
            color="negative"
            :label="t('admin.reviewModeration.blockAction')"
            data-testid="review-block-open-btn"
            @click="openBlockDialog"
          />
          <q-btn
            unelevated
            color="primary"
            :label="t('admin.reviewModeration.approveAction')"
            :loading="submitting"
            data-testid="review-approve-btn"
            @click="approve"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Block review (reason required) -->
    <q-dialog v-model="blockDialogOpen">
      <q-card
        data-testid="review-block-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.reviewModeration.blockDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-input
            v-model="blockReason"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            :label="t('common.reason')"
            data-testid="review-block-reason-input"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="negative"
            :label="t('admin.reviewModeration.blockAction')"
            :loading="submitting"
            :disable="!blockReason.trim()"
            data-testid="review-block-submit-btn"
            @click="block"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'

const emit = defineEmits(['changed'])

const { t, locale } = useI18n()
const $q = useQuasar()

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)

const detailDialogOpen = ref(false)
const blockDialogOpen = ref(false)
const selected = ref(null)
const blockReason = ref('')
const submitting = ref(false)

const columns = [
  {
    name: 'coachName',
    label: t('admin.reviewModeration.colCoach'),
    field: 'coachName',
    align: 'left',
  },
  {
    name: 'authorRole',
    label: t('admin.reviewModeration.colAuthorRole'),
    field: 'authorRole',
    align: 'left',
  },
  { name: 'rating', label: t('admin.reviewModeration.colRating'), field: 'rating', align: 'left' },
  { name: 'body', label: t('admin.reviewModeration.colBody'), field: 'body', align: 'left' },
  {
    name: 'flagCount',
    label: t('admin.reviewModeration.colFlags'),
    field: 'flagCount',
    align: 'center',
  },
  {
    name: 'createdAt',
    label: t('admin.reviewModeration.colCreatedAt'),
    field: 'createdAt',
    align: 'left',
  },
  {
    name: 'actions',
    label: t('admin.reviewModeration.colActions'),
    field: 'actions',
    align: 'center',
  },
]

onMounted(() => loadQueue())

async function loadQueue() {
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.getReviewQueue(currentPage.value - 1)
    if (resp.content.length === 0 && currentPage.value > 1 && resp.totalPages > 0) {
      currentPage.value = Math.min(currentPage.value - 1, resp.totalPages)
      return loadQueue()
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
  await loadQueue()
}

function openDetail(row) {
  selected.value = row
  detailDialogOpen.value = true
}

function openBlockDialog() {
  blockReason.value = ''
  blockDialogOpen.value = true
}

async function approve() {
  await decide(
    () => adminApi.approveReview(selected.value.reviewId),
    t('admin.reviewModeration.approveSuccess'),
  )
}

async function block() {
  await decide(
    () => adminApi.blockReview(selected.value.reviewId, blockReason.value.trim()),
    t('admin.reviewModeration.blockSuccess'),
  )
}

async function decide(action, successMessage) {
  submitting.value = true
  try {
    await action()
    blockDialogOpen.value = false
    detailDialogOpen.value = false
    $q.notify({ message: successMessage, type: 'positive' })
    emit('changed')
    await loadQueue()
  } catch (err) {
    // 409 = ALREADY_APPROVED / ALREADY_BLOCKED (ReviewApiAdvice): another admin got there first.
    if (err?.response?.status === 409) {
      $q.notify({ message: t('admin.reviewModeration.alreadyDecided'), type: 'negative' })
      blockDialogOpen.value = false
      detailDialogOpen.value = false
      emit('changed')
      await loadQueue()
    } else {
      $q.notify({ message: t('admin.reviewModeration.actionFailed'), type: 'negative' })
    }
  } finally {
    submitting.value = false
  }
}

function truncate(text) {
  if (!text) return ''
  return text.length > 120 ? `${text.slice(0, 120)}…` : text
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
}
</script>

<style scoped lang="scss">
.review-body-cell {
  white-space: normal;
  max-width: 360px;
}
.review-body {
  white-space: pre-wrap;
}
</style>
