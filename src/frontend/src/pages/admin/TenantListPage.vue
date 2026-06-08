<template>
  <q-page>
    <div class="app-page fade-in">

      <div class="page-header q-mb-xl">
        <div class="row items-center">
          <div>
            <div class="text-page-title">Tenants</div>
            <div class="text-meta">Manage platform tenants</div>
          </div>
          <q-space />
          <q-btn
            unelevated
            class="btn-accent"
            label="Create Tenant"
            icon="add"
            no-caps
            @click="showCreateDialog = true"
          />
        </div>
      </div>

      <!-- Filter bar -->
      <div class="glass-card--static filter-bar q-mb-lg">
        <div class="row items-center q-col-gutter-md">
          <div class="col-auto">
            <q-select
              v-model="statusFilter"
              :options="statusOptions"
              outlined dense
              emit-value map-options
              style="min-width: 180px"
            />
          </div>
          <div class="col-auto">
            <q-btn unelevated class="btn-accent" label="Search" no-caps @click="onSearch" />
          </div>
        </div>
      </div>

      <!-- Table -->
      <div class="glass-card--static table-wrap">
        <q-table
          :rows="rows"
          :columns="columns"
          row-key="tenantRef"
          :loading="loading"
          flat
          :pagination="pagination"
          @request="onRequest"
          @row-click="onRowClick"
          class="tenants-table"
        >
          <template #body-cell-tenantStatus="props">
            <q-td :props="props">
              <span
                class="status-chip"
                :class="{
                  'status-chip--active': props.row.tenantStatus === 'ACTIVE',
                  'status-chip--suspended': props.row.tenantStatus === 'SUSPENDED',
                  'status-chip--other': !['ACTIVE','SUSPENDED'].includes(props.row.tenantStatus),
                }"
              >{{ props.row.tenantStatus }}</span>
            </q-td>
          </template>

          <template #body-cell-createdAt="props">
            <q-td :props="props">
              {{ props.row.createdAt ? new Date(props.row.createdAt).toLocaleDateString() : '' }}
            </q-td>
          </template>

          <template #no-data>
            <div class="full-width column flex-center q-pa-xl">
              <q-icon name="group_off" size="48px" style="color: var(--text-muted)" class="q-mb-md" />
              <div class="text-card-title q-mb-xs">No tenants found</div>
              <div class="text-meta">Try adjusting your filter or create a new tenant.</div>
            </div>
          </template>
        </q-table>
      </div>

    </div>

    <!-- Create Tenant dialog -->
    <q-dialog v-model="showCreateDialog" persistent>
      <div class="glass-card--static create-dialog">
        <div class="text-section-title q-mb-lg">Create Tenant</div>
        <div class="q-gutter-md">
          <q-input v-model="createForm.name" label="Tenant Name" outlined autofocus
            :error="!!createErrors.name" :error-message="createErrors.name" />
          <q-select v-model="createForm.environment" :options="envOptions" label="Initial Environment"
            outlined emit-value map-options />
          <q-input v-model="createForm.email" label="Email (optional)" outlined type="email" />
          <q-input v-model="createForm.webhookUrl" label="Webhook URL (optional)" outlined />
        </div>
        <div class="row justify-end q-gutter-sm q-mt-lg">
          <q-btn flat no-caps label="Cancel" class="header-btn" @click="cancelCreate" />
          <q-btn unelevated no-caps class="btn-accent" label="Create" :loading="creating" @click="doCreate" />
        </div>
      </div>
    </q-dialog>

    <OneTimeKeyModal v-model="showKeyModal" :raw-key="rawKey || ''" @close="onKeyModalClose" />
  </q-page>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api.js'
import OneTimeKeyModal from 'src/components/admin/OneTimeKeyModal.vue'

const $q = useQuasar()
const router = useRouter()

const statusFilter = ref('ALL')
const statusOptions = [
  { label: 'All', value: 'ALL' },
  { label: 'Active', value: 'ACTIVE' },
  { label: 'Suspended', value: 'SUSPENDED' },
]

const loading = ref(false)
const rows = ref([])
const pagination = ref({ page: 1, rowsPerPage: 20, rowsNumber: 0 })

const columns = [
  { name: 'name', label: 'Tenant Name', field: 'name', align: 'left', sortable: false },
  { name: 'tenantRef', label: 'Ref', field: 'tenantRef', align: 'left', sortable: false },
  { name: 'email', label: 'Email', field: 'email', align: 'left', sortable: false },
  { name: 'tenantStatus', label: 'Status', field: 'tenantStatus', align: 'center', sortable: false },
  { name: 'createdAt', label: 'Created', field: 'createdAt', align: 'left', sortable: false },
]

async function onRequest({ pagination: p }) {
  loading.value = true
  try {
    const resp = await adminApi.listTenants({
      status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
      page: p.page - 1,
      size: p.rowsPerPage,
    })
    rows.value = resp.content
    pagination.value = { ...p, rowsNumber: resp.totalElements }
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load tenants' })
  } finally {
    loading.value = false
  }
}

function onSearch() {
  onRequest({ pagination: { ...pagination.value, page: 1 } })
}

function onRowClick(evt, row) {
  router.push('/admin/tenants/' + row.tenantRef)
}

const showCreateDialog = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', environment: 'PROD', email: '', webhookUrl: '' })
const createErrors = reactive({ name: '' })

const envOptions = [
  { label: 'PROD', value: 'PROD' },
  { label: 'DEV', value: 'DEV' },
  { label: 'SANDBOX', value: 'SANDBOX' },
]

const showKeyModal = ref(false)
const rawKey = ref(null)
let newTenantRef = null

function cancelCreate() {
  showCreateDialog.value = false
  createForm.name = ''
  createForm.environment = 'PROD'
  createForm.email = ''
  createForm.webhookUrl = ''
  createErrors.name = ''
}

async function doCreate() {
  createErrors.name = createForm.name.trim() ? '' : 'Name is required'
  if (createErrors.name) return
  creating.value = true
  try {
    const resp = await adminApi.createTenant({
      name: createForm.name.trim(),
      environment: createForm.environment,
      email: createForm.email.trim() || null,
      webhookUrl: createForm.webhookUrl.trim() || null,
    })
    newTenantRef = resp.tenant.tenantRef
    rawKey.value = resp.apiKey.rawKey
    showCreateDialog.value = false
    showKeyModal.value = true
    onSearch()
  } catch (err) {
    if (err?.response?.status === 409) {
      createErrors.name = 'A tenant with this name already exists'
    } else {
      $q.notify({ type: 'negative', message: 'Failed to create tenant. Please try again.' })
    }
  } finally {
    creating.value = false
  }
}

function onKeyModalClose() {
  rawKey.value = null
  if (newTenantRef) {
    router.push('/admin/tenants/' + newTenantRef)
    newTenantRef = null
  }
}

onMounted(() => {
  onRequest({ pagination: pagination.value })
})
</script>

<style lang="scss" scoped>
.page-header {
  border-bottom: 1px solid var(--border-soft);
  padding-bottom: 24px;
}

.filter-bar {
  padding: 16px 20px;
}

.table-wrap {
  padding: 0;
  overflow: hidden;
}

.tenants-table {
  cursor: pointer;
}

.status-chip {
  display: inline-block;
  padding: 3px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.4px;

  &--active {
    background: rgba(0, 255, 180, 0.12);
    color: var(--accent-primary);
  }
  &--suspended {
    background: rgba(255, 95, 122, 0.12);
    color: var(--accent-danger);
  }
  &--other {
    background: var(--surface-glass-strong);
    color: var(--text-muted);
  }
}

.create-dialog {
  padding: 32px;
  width: 480px;
  max-width: 95vw;
}

.header-btn {
  color: var(--text-secondary) !important;
  border-radius: 14px !important;

  &:hover {
    color: var(--text-primary) !important;
    background: var(--surface-glass-hover) !important;
  }
}
</style>
