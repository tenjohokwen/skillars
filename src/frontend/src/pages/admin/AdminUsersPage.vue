<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.users.title') }}</div>
        <div class="text-meta">{{ t('admin.users.subtitle') }}</div>
      </div>

      <form class="row q-col-gutter-sm items-center q-mb-md" @submit.prevent="search">
        <div class="col-12 col-sm-8 col-md-6">
          <q-input
            v-model="query"
            outlined
            dense
            clearable
            maxlength="100"
            :label="t('admin.users.searchLabel')"
            data-testid="user-search-input"
          >
            <template #prepend>
              <q-icon name="search" />
            </template>
          </q-input>
        </div>
        <div class="col-auto">
          <q-btn
            type="submit"
            unelevated
            color="primary"
            :label="t('admin.users.searchAction')"
            data-testid="user-search-btn"
          />
        </div>
      </form>

      <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
        {{ t('admin.users.loadError') }}
      </q-banner>

      <q-table
        :rows="rows"
        :columns="columns"
        row-key="userId"
        flat
        :loading="loading"
        :pagination="{ rowsPerPage: 20 }"
        hide-pagination
        :no-data-label="t('admin.users.emptyState')"
      >
        <template #body-cell-name="props">
          <q-td :props="props">{{ props.row.firstName }} {{ props.row.lastName }}</q-td>
        </template>
        <template #body-cell-status="props">
          <q-td :props="props">
            <q-chip dense :color="statusColor(props.row)" text-color="white">
              {{ statusLabel(props.row) }}
            </q-chip>
          </q-td>
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
              :label="t('admin.users.view')"
              data-testid="user-row-view-btn"
              @click="openDetail(props.row.userId)"
            />
          </q-td>
        </template>
      </q-table>

      <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
        <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
      </div>
    </div>

    <!-- User detail -->
    <q-dialog v-model="detailDialogOpen">
      <q-card
        data-testid="user-detail-dialog"
        style="min-width: 360px; max-width: 640px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.users.detailTitle') }}</div>
        </q-card-section>

        <q-card-section v-if="detailLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>
        <q-card-section v-else-if="detailLoadError" class="text-negative">
          {{ t('admin.users.detailLoadError') }}
        </q-card-section>

        <template v-else-if="detail">
          <q-card-section data-testid="user-detail-content">
            <div class="text-subtitle1 text-weight-medium">
              {{ detail.firstName }} {{ detail.lastName }}
            </div>
            <div>{{ t('admin.users.emailLabel') }}: {{ detail.email }}</div>
            <div v-if="detail.login && detail.login !== detail.email">
              {{ t('admin.users.loginLabel') }}: {{ detail.login }}
            </div>
            <div>{{ t('admin.users.roleLabel') }}: {{ detail.role || '—' }}</div>
            <div>
              {{ t('admin.users.verificationLabel') }}: {{ detail.verificationStatus || '—' }}
            </div>
            <div>{{ t('admin.users.createdLabel') }}: {{ formatDate(detail.createdAt) }}</div>
            <div class="q-mt-sm">
              <q-chip dense :color="statusColor(detail)" text-color="white">
                {{ statusLabel(detail) }}
              </q-chip>
            </div>
            <div class="q-mt-sm" data-testid="user-failed-logins">
              {{ t('admin.users.failedLoginsLabel') }}: {{ detail.recentFailedLoginAttempts }}
            </div>

            <q-banner
              v-if="detail.privileged"
              dense
              rounded
              class="bg-grey-3 text-dark q-mt-md"
              data-testid="user-privileged-banner"
            >
              {{ t('admin.users.privilegedNotice') }}
            </q-banner>

            <div class="q-mt-md">
              <div class="text-subtitle2">{{ t('admin.users.historyTitle') }}</div>
              <div v-if="!detail.history || !detail.history.length" class="text-meta">
                {{ t('admin.users.noHistory') }}
              </div>
              <q-list v-else dense separator>
                <q-item v-for="(h, i) in detail.history" :key="i">
                  <q-item-section>
                    <q-item-label>{{ actionLabel(h.actionType) }}</q-item-label>
                    <q-item-label v-if="h.reason" caption>{{ h.reason }}</q-item-label>
                  </q-item-section>
                  <q-item-section side>{{ formatDate(h.createdAt) }}</q-item-section>
                </q-item>
              </q-list>
            </div>
          </q-card-section>

          <q-card-actions align="right">
            <q-btn flat :label="t('common.close')" v-close-popup />
            <q-btn
              v-if="!detail.privileged"
              flat
              color="primary"
              :label="t('admin.users.unlockLoginAction')"
              data-testid="user-unlock-open-btn"
              @click="openAction('unlock')"
            />
            <q-btn
              v-if="detail.suspended && !detail.privileged"
              unelevated
              color="positive"
              :label="t('admin.users.reactivateAction')"
              data-testid="user-reactivate-open-btn"
              @click="openAction('reactivate')"
            />
            <q-btn
              v-else-if="!detail.privileged"
              unelevated
              color="negative"
              :label="t('admin.users.suspendAction')"
              data-testid="user-suspend-open-btn"
              @click="openAction('suspend')"
            />
          </q-card-actions>
        </template>
      </q-card>
    </q-dialog>

    <!-- Confirmation: suspend / reactivate (reason required) / unlock login -->
    <q-dialog v-model="actionDialogOpen">
      <q-card
        data-testid="user-action-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ actionTitle }}</div>
          <div class="text-meta">{{ actionHint }}</div>
        </q-card-section>
        <q-card-section v-if="actionMode !== 'unlock'">
          <q-input
            v-model="actionReason"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            :label="t('common.reason')"
            data-testid="user-action-reason-input"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            :color="actionMode === 'suspend' ? 'negative' : 'primary'"
            :label="t('common.confirm')"
            :loading="submitting"
            :disable="actionMode !== 'unlock' && !actionReason.trim()"
            data-testid="user-action-submit-btn"
            @click="submitAction"
          />
        </q-card-actions>
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

const query = ref('')
const activeQuery = ref('')
const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)

const detailDialogOpen = ref(false)
const detailLoading = ref(false)
const detailLoadError = ref(false)
const detail = ref(null)
const selectedUserId = ref(null)

const actionDialogOpen = ref(false)
const actionMode = ref(null)
const actionReason = ref('')
const submitting = ref(false)

const columns = [
  { name: 'name', label: t('admin.users.colName'), field: 'lastName', align: 'left' },
  { name: 'email', label: t('admin.users.colEmail'), field: 'email', align: 'left' },
  { name: 'role', label: t('admin.users.colRole'), field: 'role', align: 'left' },
  { name: 'status', label: t('admin.users.colStatus'), field: 'suspended', align: 'left' },
  { name: 'createdAt', label: t('admin.users.colCreatedAt'), field: 'createdAt', align: 'left' },
  { name: 'actions', label: t('admin.users.colActions'), field: 'actions', align: 'center' },
]

const actionTitle = computed(
  () =>
    ({
      suspend: t('admin.users.suspendTitle'),
      reactivate: t('admin.users.reactivateTitle'),
      unlock: t('admin.users.unlockTitle'),
    })[actionMode.value] ?? '',
)

const actionHint = computed(
  () =>
    ({
      suspend: t('admin.users.suspendHint'),
      reactivate: t('admin.users.reactivateHint'),
      unlock: t('admin.users.unlockHint'),
    })[actionMode.value] ?? '',
)

function statusLabel(user) {
  if (user.suspended) return t('admin.users.statusSuspended')
  if (!user.activated) return t('admin.users.statusNotActivated')
  return t('admin.users.statusActive')
}

function statusColor(user) {
  if (user.suspended) return 'negative'
  if (!user.activated) return 'grey-7'
  return 'positive'
}

function actionLabel(actionType) {
  return (
    {
      USER_SUSPEND: t('admin.users.actions.USER_SUSPEND'),
      USER_REACTIVATE: t('admin.users.actions.USER_REACTIVATE'),
      USER_LOGIN_UNLOCK: t('admin.users.actions.USER_LOGIN_UNLOCK'),
    }[actionType] ?? actionType
  )
}

let detailToken = 0

onMounted(() => loadUsers())

async function loadUsers() {
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.searchUsers(activeQuery.value, currentPage.value - 1)
    rows.value = resp.content
    totalPages.value = resp.totalPages
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function search() {
  activeQuery.value = (query.value || '').trim()
  currentPage.value = 1
  await loadUsers()
}

async function loadPage(page) {
  currentPage.value = page
  await loadUsers()
}

async function openDetail(userId) {
  selectedUserId.value = userId
  detailDialogOpen.value = true
  await reloadDetail()
}

async function reloadDetail() {
  const token = ++detailToken
  detailLoading.value = true
  detailLoadError.value = false
  detail.value = null
  try {
    const resp = await adminApi.getUser(selectedUserId.value)
    if (token !== detailToken) return
    detail.value = resp
  } catch {
    if (token !== detailToken) return
    detailLoadError.value = true
  } finally {
    if (token === detailToken) detailLoading.value = false
  }
}

function openAction(mode) {
  actionMode.value = mode
  actionReason.value = ''
  actionDialogOpen.value = true
}

async function submitAction() {
  const userId = selectedUserId.value
  const reason = actionReason.value.trim()
  submitting.value = true
  try {
    let successMessage
    if (actionMode.value === 'suspend') {
      await adminApi.suspendUser(userId, reason)
      successMessage = t('admin.users.suspendSuccess')
    } else if (actionMode.value === 'reactivate') {
      await adminApi.reactivateUser(userId, reason)
      successMessage = t('admin.users.reactivateSuccess')
    } else {
      const resp = await adminApi.unlockUserLogin(userId)
      successMessage = t('admin.users.unlockSuccess', { count: resp?.clearedAttempts ?? 0 })
    }
    actionDialogOpen.value = false
    $q.notify({ message: successMessage, type: 'positive' })
    await Promise.all([reloadDetail(), loadUsers()])
  } catch (err) {
    $q.notify({ message: errorMessage(err), type: 'negative' })
  } finally {
    submitting.value = false
  }
}

// AdminUserService refusals carry their reason key in errorMsg.message (ResponseStatusException).
function errorMessage(err) {
  const key = err?.response?.data?.errorMsg?.message
  if (key === 'admin.users.cannotSuspendAdmin') return t('admin.users.cannotSuspendAdmin')
  if (key === 'admin.users.cannotModifyAdmin') return t('admin.users.cannotModifyAdmin')
  if (key === 'admin.users.cannotSuspendSelf') return t('admin.users.cannotSuspendSelf')
  if (key === 'admin.users.erased') return t('admin.users.erased')
  if (key === 'admin.users.safetyHold') return t('admin.users.safetyHold')
  return t('admin.users.actionFailed')
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
}
</script>
