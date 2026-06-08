<template>
  <q-page padding>
    <div class="row items-center q-mb-md">
      <div class="text-h5">Tenants</div>
      <q-space />
      <q-btn color="primary" label="Create Tenant" icon="add" @click="showCreateDialog = true" />
    </div>

    <q-card class="q-mb-md">
      <q-card-section>
        <div class="row items-center q-col-gutter-md">
          <div class="col-auto">
            <q-select
              v-model="statusFilter"
              :options="statusOptions"
              outlined
              dense
              emit-value
              map-options
              style="min-width: 200px"
            />
          </div>
          <div class="col-auto">
            <q-btn color="primary" label="Search" @click="onSearch" />
          </div>
        </div>
      </q-card-section>
    </q-card>

    <q-table
      :rows="rows"
      :columns="columns"
      row-key="tenantRef"
      :loading="loading"
      flat
      bordered
      :pagination="pagination"
      @request="onRequest"
      @row-click="onRowClick"
    >
      <template #body-cell-tenantStatus="props">
        <q-td :props="props">
          <q-chip
            :color="props.row.tenantStatus === 'ACTIVE' ? 'positive' : props.row.tenantStatus === 'SUSPENDED' ? 'negative' : 'grey'"
            text-color="white"
            dense
          >
            {{ props.row.tenantStatus }}
          </q-chip>
        </q-td>
      </template>

      <template #body-cell-createdAt="props">
        <q-td :props="props">
          {{ props.row.createdAt ? new Date(props.row.createdAt).toLocaleDateString() : '' }}
        </q-td>
      </template>

      <template #no-data>
        <div class="full-width column flex-center q-pa-lg">
          <div class="text-h6 q-mb-sm">No tenants found</div>
          <div class="text-body2 text-grey-6">
            No tenants match your current filter. Try changing the status filter or create a new tenant.
          </div>
        </div>
      </template>
    </q-table>

    <!-- Create Tenant dialog -->
    <q-dialog v-model="showCreateDialog" persistent>
      <q-card style="min-width: 400px">
        <q-card-section>
          <div class="text-h6">Create Tenant</div>
        </q-card-section>
        <q-card-section class="q-gutter-md">
          <q-input
            v-model="createForm.name"
            label="Tenant Name"
            outlined
            dense
            autofocus
            :error="!!createErrors.name"
            :error-message="createErrors.name"
          />
          <q-select
            v-model="createForm.environment"
            :options="envOptions"
            label="Initial Environment"
            outlined
            dense
            emit-value
            map-options
          />
          <q-input
            v-model="createForm.email"
            label="Email (optional)"
            outlined
            dense
            type="email"
          />
          <q-input
            v-model="createForm.webhookUrl"
            label="Webhook URL (optional)"
            outlined
            dense
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat label="Cancel" @click="cancelCreate" />
          <q-btn color="primary" label="Create" :loading="creating" @click="doCreate" />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- One-time key modal shown after tenant creation -->
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
  } catch  {
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

// --- Create Tenant ---

const showCreateDialog = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', environment: 'PROD', email: '', webhookUrl: '' })
const createErrors = reactive({ name: '' })

const envOptions = [
  { label: 'PROD', value: 'PROD' },
  { label: 'DEV', value: 'DEV' },
  { label: 'SANDBOX', value: 'SANDBOX' },
]

// OneTimeKeyModal state (shown after successful creation)
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
    onSearch() // reload list
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
