<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.config.title') }}</div>
        <div class="text-meta">{{ t('admin.config.subtitle') }}</div>
      </div>

      <div class="row q-col-gutter-sm q-mb-md">
        <div class="col-12 col-sm-6 col-md-4">
          <q-input
            v-model="search"
            outlined
            dense
            clearable
            :label="t('admin.config.searchLabel')"
            data-testid="config-search-input"
          >
            <template #prepend><q-icon name="search" /></template>
          </q-input>
        </div>
      </div>

      <q-banner class="bg-info text-white q-mb-md" rounded>
        {{ t('admin.config.propagationNotice') }}
      </q-banner>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.config.loadError') }}
      </q-banner>

      <q-table
        :rows="filteredRows"
        :columns="columns"
        row-key="key"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 25 }"
        :rows-per-page-options="[25, 50, 100, 0]"
        :no-data-label="t('admin.config.emptyState')"
      >
        <template #body-cell-key="props">
          <q-td :props="props">
            <div class="config-key">{{ props.row.key }}</div>
            <div v-if="props.row.description" class="text-meta">{{ props.row.description }}</div>
          </q-td>
        </template>
        <template #body-cell-value="props">
          <q-td :props="props">
            <span v-if="props.row.stored" class="config-value">{{ props.row.value }}</span>
            <span v-else class="text-meta" data-testid="config-code-default">
              {{ t('admin.config.codeDefault', { value: props.row.codeDefault }) }}
            </span>
          </q-td>
        </template>
        <template #body-cell-valueType="props">
          <q-td :props="props">
            <q-chip dense outline>{{ typeLabel(props.row.valueType) }}</q-chip>
          </q-td>
        </template>
        <template #body-cell-range="props">
          <q-td :props="props">{{ rangeLabel(props.row) }}</q-td>
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
              :label="t('common.edit')"
              data-testid="config-edit-btn"
              @click="openEdit(props.row)"
            />
          </q-td>
        </template>
      </q-table>
    </div>

    <q-dialog v-model="editOpen">
      <q-card
        data-testid="config-edit-dialog"
        style="min-width: 320px; max-width: 560px; width: 100%"
      >
        <template v-if="editing">
          <q-card-section>
            <div class="text-h6">{{ t('admin.config.editTitle') }}</div>
            <div class="config-key">{{ editing.key }}</div>
            <div v-if="editing.description" class="text-meta">{{ editing.description }}</div>
          </q-card-section>
          <q-card-section class="q-pt-none">
            <div class="row q-gutter-sm items-center">
              <q-chip dense outline>{{ typeLabel(editing.valueType) }}</q-chip>
              <span v-if="hasRange(editing)" class="text-meta">
                {{ t('admin.config.rangeLabel') }}: {{ rangeLabel(editing) }}
              </span>
            </div>
            <div v-if="editing.note" class="text-meta q-mt-xs">{{ editing.note }}</div>
            <q-banner
              v-if="editing.failFast"
              class="bg-warning text-dark q-mt-sm"
              rounded
              data-testid="config-failfast-warning"
            >
              {{ t('admin.config.failFastWarning') }}
            </q-banner>
            <div v-if="!editing.stored" class="text-meta q-mt-sm">
              {{ t('admin.config.unseededHint', { value: editing.codeDefault }) }}
            </div>
          </q-card-section>
          <q-card-section>
            <q-input
              v-model="draft"
              outlined
              :label="t('admin.config.valueLabel')"
              :error="!!fieldError"
              :error-message="fieldError"
              data-testid="config-value-input"
              @update:model-value="serverError = ''"
            />
          </q-card-section>
          <q-card-actions align="right">
            <q-btn flat :label="t('common.cancel')" v-close-popup />
            <q-btn
              unelevated
              color="primary"
              :label="t('common.save')"
              :loading="saving"
              :disable="!canSave"
              data-testid="config-save-btn"
              @click="save"
            />
          </q-card-actions>
        </template>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'

const { t, locale } = useI18n()
const $q = useQuasar()

const INTEGER_RE = /^-?\d+$/

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const search = ref('')

const editOpen = ref(false)
const editing = ref(null)
const draft = ref('')
const serverError = ref('')
const saving = ref(false)

const typeLabels = computed(() => ({
  LONG: t('admin.config.types.LONG'),
  STRING: t('admin.config.types.STRING'),
}))

const columns = computed(() => [
  { name: 'key', label: t('admin.config.colKey'), field: 'key', align: 'left', sortable: true },
  { name: 'value', label: t('admin.config.colValue'), field: 'value', align: 'left' },
  { name: 'valueType', label: t('admin.config.colType'), field: 'valueType', align: 'left' },
  { name: 'range', label: t('admin.config.colRange'), field: 'min', align: 'left' },
  { name: 'updatedAt', label: t('admin.config.colUpdated'), field: 'updatedAt', align: 'left' },
  { name: 'actions', label: '', field: 'actions', align: 'right' },
])

const filteredRows = computed(() => {
  const q = (search.value || '').trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter(
    (r) => r.key.toLowerCase().includes(q) || (r.description || '').toLowerCase().includes(q),
  )
})

// Client-side mirror of ConfigService.updateConfig's checks so the admin sees the problem before
// submitting. The server stays authoritative (it also enforces cross-key rules this page cannot
// know about); its 400 reason is shown in the same place.
const clientError = computed(() => {
  const row = editing.value
  if (!row) return ''
  const v = (draft.value ?? '').trim()
  if (!v) return t('admin.config.errorRequired')
  if (row.valueType === 'LONG' || hasRange(row)) {
    if (!INTEGER_RE.test(v)) return t('admin.config.errorInteger')
    if (hasRange(row) && !withinRange(v, row)) {
      return t('admin.config.errorRange', { range: rangeLabel(row) })
    }
  }
  return ''
})

const fieldError = computed(() => serverError.value || clientError.value)
const canSave = computed(
  () =>
    !clientError.value &&
    !!editing.value &&
    (!editing.value.stored || draft.value.trim() !== editing.value.value),
)

function typeLabel(type) {
  return typeLabels.value[type] ?? type ?? '—'
}

function hasRange(row) {
  return row.min !== null && row.min !== undefined && row.max !== null && row.max !== undefined
}

// Bounds above Number.MAX_SAFE_INTEGER (Long.MAX_VALUE "no upper limit") lose precision in JSON;
// treat them as unbounded and leave the exact check to the server.
function isUnboundedMax(max) {
  return max >= Number.MAX_SAFE_INTEGER
}

function withinRange(value, row) {
  const n = BigInt(value)
  if (n < BigInt(row.min)) return false
  if (!isUnboundedMax(row.max) && n > BigInt(row.max)) return false
  return true
}

function rangeLabel(row) {
  if (!hasRange(row)) return '—'
  return isUnboundedMax(row.max)
    ? t('admin.config.rangeAtLeast', { min: row.min })
    : `${row.min} – ${row.max}`
}

onMounted(loadList)

async function loadList() {
  loading.value = true
  loadError.value = false
  try {
    rows.value = await adminApi.getConfigValues()
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  editing.value = row
  draft.value = row.stored ? row.value : String(row.codeDefault ?? '')
  serverError.value = ''
  editOpen.value = true
}

async function save() {
  const row = editing.value
  const value = draft.value.trim()
  saving.value = true
  serverError.value = ''
  try {
    const resp = await adminApi.updateConfigValue(row.key, value)
    const idx = rows.value.findIndex((r) => r.key === row.key)
    if (idx >= 0) {
      rows.value[idx] = {
        ...rows.value[idx],
        value: resp.value,
        valueType: resp.valueType ?? rows.value[idx].valueType,
        updatedAt: resp.updatedAt,
        stored: true,
      }
    }
    editOpen.value = false
    $q.notify({ message: t('admin.config.saved', { key: row.key }), type: 'positive' })
  } catch (err) {
    const status = err?.response?.status
    const data = err?.response?.data
    if (status === 400) {
      serverError.value =
        data?.fieldErrors?.[0]?.errorMsg?.message ||
        data?.errorMsg?.message ||
        t('admin.config.errorInvalid')
    } else if (status === 404) {
      serverError.value = t('admin.config.errorNotFound')
    } else {
      $q.notify({ message: t('admin.config.saveFailed'), type: 'negative' })
    }
  } finally {
    saving.value = false
  }
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
}
</script>

<style scoped>
.config-key,
.config-value {
  font-family: monospace;
  word-break: break-all;
}
</style>
