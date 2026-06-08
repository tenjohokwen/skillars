<template>
  <q-page padding>
    <q-inner-loading :showing="isLoading" />

    <template v-if="!isLoading && tenant">
      <!-- Header row -->
      <div class="row items-center q-mb-md">
        <div class="text-h5">{{ form.name }}</div>
        <q-space />
        <q-btn
          v-if="tenant.tenantStatus === 'ACTIVE'"
          color="negative"
          label="Suspend"
          :loading="togglingStatus"
          @click="confirmSuspend"
        />
        <q-btn
          v-else-if="tenant.tenantStatus === 'SUSPENDED'"
          color="primary"
          label="Reactivate"
          :loading="togglingStatus"
          @click="confirmReactivate"
        />
      </div>

      <!-- Inline edit section -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="row items-center q-mb-sm">
            <q-input
              v-model="form.name"
              label="Name"
              outlined
              dense
              class="col"
            />
            <q-btn
              color="primary"
              label="Update Name"
              :loading="saving.name"
              @click="saveName"
              class="q-ml-sm"
            />
          </div>
          <div class="row items-center q-mb-sm">
            <q-input
              v-model="form.email"
              label="Email"
              outlined
              dense
              class="col"
            />
            <q-btn
              color="primary"
              label="Update Email"
              :loading="saving.email"
              @click="saveEmail"
              class="q-ml-sm"
            />
          </div>
          <div class="row items-center q-mb-sm">
            <q-input
              v-model="form.webhookUrl"
              label="Webhook URL"
              outlined
              dense
              class="col"
            />
            <q-btn
              color="primary"
              label="Update Webhook"
              :loading="saving.webhookUrl"
              @click="saveWebhookUrl"
              class="q-ml-sm"
            />
          </div>
        </q-card-section>
      </q-card>

      <!-- API Keys section -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-h6">API Keys</div>
        </q-card-section>
        <q-card-section>
          <q-table
            :rows="keyRows"
            :columns="keyColumns"
            row-key="id"
            flat
            bordered
            dense
          >
            <template #body-cell-keyStatus="props">
              <q-td :props="props">
                <q-chip
                  :color="statusColor(props.row.keyStatus)"
                  text-color="white"
                  dense
                  size="sm"
                >
                  {{ props.row.keyStatus }}
                </q-chip>
              </q-td>
            </template>

            <template #body-cell-createdAt="props">
              <q-td :props="props">
                {{ new Date(props.row.createdAt).toLocaleDateString() }}
              </q-td>
            </template>

            <template #body-cell-actions="props">
              <q-td :props="props">
                <template v-if="props.row.keyStatus === 'REVOKED'">
                  <q-btn flat dense color="primary" label="Reactivate" @click="doReactivateKey(props.row)" />
                </template>
                <template v-else-if="props.row.keyStatus === 'ACTIVE'">
                  <q-btn flat dense color="primary" label="Rotate" @click="doRotateKey(props.row)" />
                  <q-btn flat dense color="negative" label="Revoke" @click="doRevokeKey(props.row)" />
                </template>
                <!-- ROTATED status: no actions (read-only row) -->
              </q-td>
            </template>
          </q-table>

          <div v-for="env in envsWithoutActiveKey" :key="env" class="q-mt-sm">
            <q-btn color="primary" :label="'Generate ' + env + ' Key'" @click="doGenerateKey(env)" />
          </div>
        </q-card-section>
      </q-card>

      <!-- Webhook Secret section -->
      <q-card class="q-mb-md">
        <q-card-section>
          <div class="text-h6">Webhook Secret</div>
        </q-card-section>
        <q-card-section>
          <q-input
            :model-value="unmasked ? secret : '********'"
            readonly
            outlined
            dense
            :input-style="{ fontFamily: 'monospace' }"
          >
            <template #append>
              <q-btn
                flat
                round
                dense
                :icon="unmasked ? 'visibility_off' : 'visibility'"
                :aria-label="unmasked ? 'Hide webhook secret' : 'Reveal webhook secret'"
                @click="toggleSecret"
              />
            </template>
          </q-input>
          <div v-if="unmasked" class="text-caption text-grey q-mt-xs">
            Auto-hides in {{ countdown }}s
          </div>
          <q-btn
            flat
            color="primary"
            label="Regenerate Secret"
            :loading="regenerating"
            @click="doRegenerateSecret"
            class="q-mt-sm"
          />
        </q-card-section>
      </q-card>
    </template>

    <!-- OneTimeKeyModal -->
    <OneTimeKeyModal v-model="showKeyModal" :raw-key="rawKey || ''" @close="onKeyModalClose" />
  </q-page>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useQuasar } from 'quasar'
import { adminApi } from 'src/api/admin.api'
import OneTimeKeyModal from 'src/components/admin/OneTimeKeyModal.vue'

const $q = useQuasar()
const route = useRoute()
const tenantRef = route.params.tenantRef

const isLoading = ref(true)
const tenant = ref(null)
const form = reactive({ name: '', email: '', webhookUrl: '' })
const saving = reactive({ name: false, email: false, webhookUrl: false })
const togglingStatus = ref(false)
const keyRows = ref([])

// OneTimeKeyModal state
const showKeyModal = ref(false)
const rawKey = ref(null)

// Webhook secret state
const secret = ref(null)
const unmasked = ref(false)
const countdown = ref(0)
const regenerating = ref(false)
let maskTimer = null
let countdownInterval = null

// Key table columns per D-03
const keyColumns = [
  { name: 'environment', label: 'Env', field: 'environment', align: 'left' },
  { name: 'keyPrefix', label: 'Key Prefix', field: 'keyPrefix', align: 'left' },
  { name: 'keyStatus', label: 'Status', field: 'keyStatus', align: 'center' },
  { name: 'createdAt', label: 'Created Date', field: 'createdAt', align: 'left' },
  { name: 'actions', label: 'Actions', field: 'actions', align: 'center' },
]

// Compute envs with no active key for Generate button
const envsWithoutActiveKey = computed(() => {
  const allEnvs = ['PROD', 'DEV', 'SANDBOX']
  const activeEnvs = keyRows.value.filter(k => k.keyStatus === 'ACTIVE').map(k => k.environment)
  return allEnvs.filter(e => !activeEnvs.includes(e))
})

function statusColor(status) {
  if (status === 'ACTIVE') return 'positive'
  if (status === 'REVOKED') return 'negative'
  if (status === 'ROTATED') return 'warning'
  return 'grey'
}

// Data loading
async function loadTenant() {
  isLoading.value = true
  try {
    const resp = await adminApi.getTenantDetail(tenantRef)
    tenant.value = resp
    form.name = resp.name
    form.email = resp.email || ''
    form.webhookUrl = resp.webhookUrl || ''
    // Sort keys: PROD first, then by environment name
    keyRows.value = [...resp.keys].sort((a, b) => {
      if (a.environment === 'PROD') return -1
      if (b.environment === 'PROD') return 1
      return a.environment.localeCompare(b.environment)
    })
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load tenant details. Please refresh the page.' })
  } finally {
    isLoading.value = false
  }
}

onMounted(() => loadTenant())

// Per-field save functions (per D-01, D-02)
async function saveName() {
  saving.name = true
  try {
    await adminApi.updateTenantName(tenantRef, { name: form.name })
    tenant.value.name = form.name
    $q.notify({ type: 'positive', message: 'Name updated successfully' })
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to update name. Please try again.' })
  } finally {
    saving.name = false
  }
}

async function saveEmail() {
  saving.email = true
  try {
    await adminApi.updateTenantEmail(tenantRef, { email: form.email })
    tenant.value.email = form.email
    $q.notify({ type: 'positive', message: 'Email updated successfully' })
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to update email. Please try again.' })
  } finally {
    saving.email = false
  }
}

async function saveWebhookUrl() {
  saving.webhookUrl = true
  try {
    await adminApi.updateTenantWebhookUrl(tenantRef, { webhookUrl: form.webhookUrl })
    tenant.value.webhookUrl = form.webhookUrl
    $q.notify({ type: 'positive', message: 'Webhook URL updated successfully' })
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to update webhook URL. Please try again.' })
  } finally {
    saving.webhookUrl = false
  }
}

// Status toggle (per D-06 through D-09)
function confirmSuspend() {
  $q.dialog({
    title: `Suspend ${tenant.value.name}?`,
    message: 'All API keys will be revoked. The tenant will no longer have access to the platform.',
    cancel: true,
    ok: { label: 'Suspend', color: 'negative' },
    persistent: true,
  }).onOk(async () => {
    togglingStatus.value = true
    try {
      await adminApi.suspendTenant(tenantRef)
      $q.notify({ type: 'positive', message: 'Tenant suspended' })
      await loadTenant()  // reload to get updated status and key states
    } catch {
      $q.notify({ type: 'negative', message: 'Failed to suspend tenant. Please try again.' })
    } finally {
      togglingStatus.value = false
    }
  })
}

function confirmReactivate() {
  $q.dialog({
    title: `Reactivate ${tenant.value.name}?`,
    message: 'A new PROD key will be generated. You will need to share it with the tenant.',
    cancel: true,
    ok: { label: 'Reactivate', color: 'primary' },
    persistent: true,
  }).onOk(async () => {
    togglingStatus.value = true
    try {
      const resp = await adminApi.reactivateTenant(tenantRef)
      // D-08: open key modal with rawKey from response
      rawKey.value = resp.rawKey
      showKeyModal.value = true
      await loadTenant()  // reload status and keys
    } catch {
      $q.notify({ type: 'negative', message: 'Failed to reactivate tenant. Please try again.' })
    } finally {
      togglingStatus.value = false
    }
  })
}

// Key actions (per D-04, D-05)
async function doGenerateKey(env) {
  try {
    const resp = await adminApi.generateKey(tenantRef, env)
    rawKey.value = resp.rawKey
    showKeyModal.value = true  // D-05: Generate triggers modal
    await loadTenant()
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to generate key. Please try again.' })
  }
}

async function doRotateKey(row) {
  try {
    const resp = await adminApi.rotateKey(tenant.value.id, row.id)
    rawKey.value = resp.rawKey
    showKeyModal.value = true  // D-05: Rotate triggers modal
    await loadTenant()
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to rotate key. Please try again.' })
  }
}

async function doRevokeKey(row) {
  try {
    await adminApi.revokeKey(tenant.value.id, row.id)
    $q.notify({ type: 'positive', message: 'Key revoked' })
    await loadTenant()
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to revoke key. Please try again.' })
  }
}

async function doReactivateKey(row) {
  try {
    await adminApi.reactivateKey(tenant.value.id, row.id)
    $q.notify({ type: 'positive', message: 'Key reactivated' })
    await loadTenant()  // D-04: no modal for key-level reactivate (204, no rawKey)
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to reactivate key. Please try again.' })
  }
}

function onKeyModalClose() {
  rawKey.value = null  // D-11: clear rawKey from component state immediately
}

// Webhook secret reveal (per D-13, D-14, D-15)
function clearTimers() {
  clearTimeout(maskTimer)
  clearInterval(countdownInterval)
  maskTimer = null
  countdownInterval = null
}

function reMask() {
  clearTimers()
  secret.value = null
  unmasked.value = false
  countdown.value = 0
}

function startAutoMask() {
  countdown.value = 30
  countdownInterval = setInterval(() => { countdown.value-- }, 1000)
  maskTimer = setTimeout(() => { reMask() }, 30000)
}

async function toggleSecret() {
  if (unmasked.value) {
    reMask()  // D-15: second click while revealed immediately re-masks
    return
  }
  try {
    const resp = await adminApi.getWebhookSecret(tenantRef)
    secret.value = resp.webhookSecret
    unmasked.value = true
    startAutoMask()  // D-14: start 30s auto-mask timer
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to load webhook secret. Please try again.' })
  }
}

async function doRegenerateSecret() {
  regenerating.value = true
  try {
    await adminApi.regenerateWebhookSecret(tenantRef)
    reMask()  // clear any revealed secret since it's now stale
    $q.notify({ type: 'positive', message: 'Webhook secret regenerated' })
  } catch {
    $q.notify({ type: 'negative', message: 'Failed to regenerate webhook secret. Please try again.' })
  } finally {
    regenerating.value = false
  }
}

onUnmounted(() => clearTimers())  // Prevent timer leak on navigation
</script>
