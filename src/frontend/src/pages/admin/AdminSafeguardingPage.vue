<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-xl">
        <div class="text-page-title">{{ t('safeguarding.admin.title') }}</div>
        <div class="text-meta">{{ t('safeguarding.admin.subtitle') }}</div>
      </div>

      <q-tabs
        v-model="tab"
        dense
        align="left"
        class="q-mb-md"
        data-testid="safeguarding-tabs"
        @update:model-value="onTabChange"
      >
        <q-tab name="requests" no-caps :label="t('safeguarding.admin.tabRequests')" />
        <q-tab name="records" no-caps :label="t('safeguarding.admin.tabRecords')" />
      </q-tabs>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('safeguarding.admin.loadError') }}
      </q-banner>

      <q-table
        v-if="tab === 'requests'"
        :rows="rows"
        :columns="requestColumns"
        row-key="coachId"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('safeguarding.admin.emptyRequests')"
        data-testid="safeguarding-requests-table"
      >
        <template #body-cell-coachDisplayName="props">
          <q-td :props="props">
            {{ props.row.coachDisplayName }}
            <q-chip v-if="props.row.hasValidCheck" dense size="sm" outline>{{
              t('safeguarding.admin.renewal')
            }}</q-chip>
          </q-td>
        </template>
        <template #body-cell-requestedAt="props">
          <q-td :props="props">{{ formatDate(props.row.requestedAt) }}</q-td>
        </template>
        <template #body-cell-hasUpload="props">
          <q-td :props="props">
            <q-btn
              v-if="props.row.hasUpload"
              flat
              dense
              no-caps
              size="sm"
              color="primary"
              icon="mdi-file-eye-outline"
              :label="t('safeguarding.admin.viewDocument')"
              data-testid="safeguarding-view-btn"
              @click="viewDocument(props.row)"
            />
            <span v-else class="text-meta">{{ t('safeguarding.admin.uploadNo') }}</span>
          </q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat
              dense
              no-caps
              size="sm"
              color="positive"
              :label="t('safeguarding.admin.inspect')"
              data-testid="safeguarding-inspect-btn"
              @click="openAction('inspect', props.row)"
            />
            <q-btn
              flat
              dense
              no-caps
              size="sm"
              color="negative"
              :label="t('safeguarding.admin.reject')"
              data-testid="safeguarding-reject-btn"
              @click="openAction('reject', props.row)"
            />
          </q-td>
        </template>
      </q-table>

      <q-table
        v-else
        :rows="rows"
        :columns="recordColumns"
        row-key="coachId"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('safeguarding.admin.emptyRecords')"
        data-testid="safeguarding-records-table"
      >
        <template #body-cell-inspectedAt="props">
          <q-td :props="props">{{ formatDate(props.row.inspectedAt) }}</q-td>
        </template>
        <template #body-cell-expiresAt="props">
          <q-td :props="props">{{ formatDate(props.row.expiresAt) }}</q-td>
        </template>
        <template #body-cell-actions="props">
          <q-td :props="props">
            <q-btn
              flat
              dense
              no-caps
              size="sm"
              color="negative"
              :label="t('safeguarding.admin.revoke')"
              data-testid="safeguarding-revoke-btn"
              @click="openAction('revoke', props.row)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
      </div>
    </div>

    <q-dialog v-model="actionOpen">
      <q-card
        v-if="action"
        style="min-width: 360px; max-width: 560px; width: 100%"
        data-testid="safeguarding-action-dialog"
      >
        <q-card-section>
          <div class="text-h6">{{ actionText.title }}</div>
        </q-card-section>
        <q-card-section>
          {{ actionText.body }}
          <q-input
            v-if="action.type !== 'inspect'"
            v-model="reason"
            outlined
            autogrow
            maxlength="300"
            counter
            class="q-mt-md"
            data-testid="safeguarding-reason-input"
            :label="t('safeguarding.admin.reasonLabel')"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat no-caps :label="t('safeguarding.admin.cancel')" v-close-popup />
          <q-btn
            unelevated
            no-caps
            :color="action.type === 'inspect' ? 'positive' : 'negative'"
            :label="actionText.confirm"
            :loading="submitting"
            data-testid="safeguarding-action-confirm"
            @click="confirmAction"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
// S-09: staff inspection queue for coach safeguarding checks (ROLE_ADMIN only on the server). Viewing an upload,
// marking it inspected, rejecting and revoking are each audited server-side; the upload is deleted by the
// inspect / reject action itself.
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'

const { t, locale } = useI18n()
const $q = useQuasar()

const tab = ref('requests')
const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)

const actionOpen = ref(false)
// { type: 'inspect' | 'reject' | 'revoke', row }
const action = ref(null)
const reason = ref('')
const submitting = ref(false)

// Literal keys (not t(`...${type}`)): keeps every key statically checkable by i18nTemplateKeyResolutionSpec.
const actionText = computed(() => {
  const coach = action.value?.row?.coachDisplayName ?? ''
  const texts = {
    inspect: {
      title: t('safeguarding.admin.inspectTitle'),
      body: t('safeguarding.admin.inspectBody', { coach }),
      confirm: t('safeguarding.admin.inspect'),
      done: t('safeguarding.admin.inspectedDone'),
    },
    reject: {
      title: t('safeguarding.admin.rejectTitle'),
      body: t('safeguarding.admin.rejectBody', { coach }),
      confirm: t('safeguarding.admin.reject'),
      done: t('safeguarding.admin.rejectedDone'),
    },
    revoke: {
      title: t('safeguarding.admin.revokeTitle'),
      body: t('safeguarding.admin.revokeBody', { coach }),
      confirm: t('safeguarding.admin.revoke'),
      done: t('safeguarding.admin.revokedDone'),
    },
  }
  return texts[action.value?.type] ?? { title: '', body: '', confirm: '', done: '' }
})

const requestColumns = computed(() => [
  {
    name: 'coachDisplayName',
    label: t('safeguarding.admin.colCoach'),
    field: 'coachDisplayName',
    align: 'left',
  },
  { name: 'checkType', label: t('safeguarding.admin.colType'), field: 'checkType', align: 'left' },
  {
    name: 'requestedAt',
    label: t('safeguarding.admin.colRequested'),
    field: 'requestedAt',
    align: 'left',
  },
  {
    name: 'hasUpload',
    label: t('safeguarding.admin.colUpload'),
    field: 'hasUpload',
    align: 'left',
  },
  { name: 'actions', label: t('safeguarding.admin.colActions'), field: 'actions', align: 'center' },
])

const recordColumns = computed(() => [
  {
    name: 'coachDisplayName',
    label: t('safeguarding.admin.colCoach'),
    field: 'coachDisplayName',
    align: 'left',
  },
  { name: 'checkType', label: t('safeguarding.admin.colType'), field: 'checkType', align: 'left' },
  {
    name: 'inspectedAt',
    label: t('safeguarding.admin.colInspected'),
    field: 'inspectedAt',
    align: 'left',
  },
  {
    name: 'expiresAt',
    label: t('safeguarding.admin.colValidUntil'),
    field: 'expiresAt',
    align: 'left',
  },
  { name: 'actions', label: t('safeguarding.admin.colActions'), field: 'actions', align: 'center' },
])

function formatDate(value) {
  return value ? new Date(value).toLocaleDateString(locale.value) : ''
}

// Guards against a late response for an earlier tab/page overwriting a newer one.
let loadToken = 0

async function load() {
  const token = ++loadToken
  loading.value = true
  loadError.value = false
  try {
    const page = currentPage.value - 1
    const resp =
      tab.value === 'requests'
        ? await adminApi.getSafeguardingRequests(page)
        : await adminApi.getSafeguardingRecords(page)
    if (token !== loadToken) return
    // Deciding the last row of the last page leaves it empty: step back to the new last page.
    if (resp.content.length === 0 && currentPage.value > 1 && resp.totalPages > 0) {
      currentPage.value = Math.min(currentPage.value - 1, resp.totalPages)
      return load()
    }
    rows.value = resp.content
    totalPages.value = resp.totalPages
  } catch {
    if (token !== loadToken) return
    loadError.value = true
    rows.value = []
  } finally {
    if (token === loadToken) loading.value = false
  }
}

onMounted(load)

function onTabChange() {
  currentPage.value = 1
  rows.value = []
  load()
}

function loadPage(page) {
  currentPage.value = page
  return load()
}

async function viewDocument(row) {
  try {
    const blob = await adminApi.getSafeguardingDocument(row.coachId)
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener')
    // The tab has its own reference once it has loaded; release ours shortly after.
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch {
    $q.notify({ message: t('safeguarding.admin.viewFailed'), type: 'negative' })
    await load()
  }
}

function openAction(type, row) {
  action.value = { type, row }
  reason.value = ''
  actionOpen.value = true
}

async function confirmAction() {
  if (!action.value || submitting.value) return
  const { type, row } = action.value
  submitting.value = true
  try {
    if (type === 'inspect') {
      await adminApi.inspectSafeguardingRequest(row.coachId)
    } else if (type === 'reject') {
      await adminApi.rejectSafeguardingRequest(row.coachId, { reason: reason.value.trim() || null })
    } else {
      await adminApi.revokeSafeguardingRecord(row.coachId, { reason: reason.value.trim() || null })
    }
    actionOpen.value = false
    $q.notify({ message: actionText.value.done, type: 'positive' })
  } catch {
    // Another admin (or the coach's withdrawal) got there first: 404/409. Reload to show the truth.
    actionOpen.value = false
    $q.notify({ message: t('safeguarding.admin.actionFailed'), type: 'negative' })
  } finally {
    submitting.value = false
  }
  await load()
}
</script>
