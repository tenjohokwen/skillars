<template>
  <q-page class="q-pa-md">
    <div class="app-page fade-in">
      <div class="page-header q-mb-lg">
        <div class="text-page-title">{{ t('admin.moderation.title') }}</div>
        <div class="text-meta">{{ t('admin.moderation.subtitle') }}</div>
      </div>

      <!-- Open-alert counts per bucket (GET /api/admin/queue/summary) -->
      <div v-if="summary" class="row q-gutter-sm q-mb-lg" data-testid="moderation-summary">
        <q-chip
          v-for="bucket in summaryBuckets"
          :key="bucket.key"
          :color="bucket.key === 'total' ? 'primary' : undefined"
          :text-color="bucket.key === 'total' ? 'white' : undefined"
          :outline="bucket.key !== 'total'"
          :data-testid="`moderation-summary-${bucket.key}`"
        >
          {{ bucket.label }}: <strong class="q-ml-xs">{{ summary[bucket.key] ?? 0 }}</strong>
        </q-chip>
      </div>

      <q-tabs v-model="tab" align="left" dense class="q-mb-md" active-color="primary">
        <q-tab name="queue" :label="t('admin.moderation.tabQueue')" data-testid="tab-queue" />
        <q-tab name="reviews" :label="t('admin.moderation.tabReviews')" data-testid="tab-reviews" />
      </q-tabs>

      <q-tab-panels v-model="tab" animated keep-alive>
        <q-tab-panel name="queue" class="q-pa-none">
          <div class="row items-center q-mb-md">
            <q-select
              v-model="typeFilter"
              :options="typeOptions"
              :label="t('admin.moderation.typeFilterLabel')"
              emit-value
              map-options
              outlined
              dense
              style="min-width: 260px"
              data-testid="moderation-type-filter"
              @update:model-value="onTypeFilterChange"
            />
          </div>

          <q-banner v-if="loadError" class="bg-negative text-white q-mb-md" rounded>
            {{ t('admin.moderation.loadError') }}
          </q-banner>

          <q-table
            :rows="rows"
            :columns="columns"
            row-key="alertId"
            flat
            :loading="loading"
            :pagination="{ rowsPerPage: 20 }"
            hide-pagination
            :no-data-label="t('admin.moderation.emptyState')"
          >
            <template #body-cell-type="props">
              <q-td :props="props">{{ typeLabel(props.row.type) }}</q-td>
            </template>
            <template #body-cell-summary="props">
              <q-td :props="props" class="moderation-summary-cell">{{ props.row.summary }}</q-td>
            </template>
            <template #body-cell-createdAt="props">
              <q-td :props="props">{{ formatDate(props.row.createdAt) }}</q-td>
            </template>
            <template #body-cell-actions="props">
              <q-td :props="props">
                <q-btn
                  v-if="isMessageAlert(props.row.type)"
                  flat
                  dense
                  size="sm"
                  color="primary"
                  :label="t('admin.moderation.reviewMessage')"
                  data-testid="alert-open-message-btn"
                  @click="openMessage(props.row.referenceId)"
                />
                <q-btn
                  v-else-if="props.row.type === 'CONVERSATION_REPORT'"
                  flat
                  dense
                  size="sm"
                  color="primary"
                  :label="t('admin.moderation.reviewConversation')"
                  data-testid="alert-open-conversation-btn"
                  @click="openConversation(props.row.referenceId)"
                />
                <q-btn
                  v-else-if="props.row.type === 'REVIEW_FLAG'"
                  flat
                  dense
                  size="sm"
                  color="primary"
                  :label="t('admin.moderation.openReviewQueue')"
                  data-testid="alert-open-reviews-btn"
                  @click="tab = 'reviews'"
                />
                <q-btn
                  v-else-if="isCoachAlert(props.row.type)"
                  flat
                  dense
                  size="sm"
                  color="primary"
                  :label="t('admin.moderation.openCoach')"
                  :to="{ path: '/admin/coaches', query: { coachId: props.row.referenceId } }"
                  data-testid="alert-open-coach-btn"
                />
                <q-btn
                  v-else-if="props.row.type === 'DISPUTE_RAISED'"
                  flat
                  dense
                  size="sm"
                  color="primary"
                  :label="t('admin.moderation.openDisputes')"
                  to="/admin/disputes"
                  data-testid="alert-open-disputes-btn"
                />
                <span v-else class="text-meta">{{ t('admin.moderation.noInlineAction') }}</span>
              </q-td>
            </template>
          </q-table>

          <div v-if="totalPages > 1" class="q-mt-md flex flex-center">
            <q-pagination v-model="currentPage" :max="totalPages" @update:model-value="loadPage" />
          </div>
        </q-tab-panel>

        <q-tab-panel name="reviews" class="q-pa-none">
          <ReviewModerationPanel @changed="loadSummary" />
        </q-tab-panel>
      </q-tab-panels>
    </div>

    <!-- Held / reported message -->
    <q-dialog v-model="messageDialogOpen">
      <q-card data-testid="message-dialog" style="min-width: 360px; max-width: 720px; width: 100%">
        <q-card-section>
          <div class="text-h6">{{ t('admin.moderation.messageTitle') }}</div>
        </q-card-section>

        <q-card-section v-if="detailLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>
        <q-card-section v-else-if="detailLoadError" class="text-negative">
          {{ t('admin.moderation.detailLoadError') }}
        </q-card-section>

        <template v-else-if="message">
          <q-card-section data-testid="message-detail-content">
            <div>{{ t('admin.moderation.senderRoleLabel') }}: {{ message.senderRole }}</div>
            <div>{{ t('admin.moderation.statusLabel') }}: {{ message.moderationStatus }}</div>
            <div>{{ t('admin.moderation.sentLabel') }}: {{ formatDate(message.createdAt) }}</div>
            <div class="moderation-message-body q-mt-sm q-pa-sm">{{ message.content }}</div>

            <div v-if="contextMessages.length" class="q-mt-md">
              <div class="text-subtitle2">{{ t('admin.moderation.contextLabel') }}</div>
              <q-list dense separator>
                <q-item
                  v-for="m in contextMessages"
                  :key="m.messageId"
                  :class="{ 'moderation-context-focus': m.messageId === message.messageId }"
                >
                  <q-item-section>
                    <q-item-label caption>
                      {{ m.senderRole }} · {{ formatDate(m.createdAt) }} · {{ m.moderationStatus }}
                    </q-item-label>
                    <q-item-label>{{ m.content }}</q-item-label>
                  </q-item-section>
                </q-item>
              </q-list>
            </div>

            <div v-if="message.reports && message.reports.length" class="q-mt-md">
              <div class="text-subtitle2">{{ t('admin.moderation.reportsLabel') }}</div>
              <q-list dense>
                <q-item v-for="(r, i) in message.reports" :key="i">
                  <q-item-section>
                    <q-item-label>{{ r.reason }}</q-item-label>
                    <q-item-label v-if="r.details" caption>{{ r.details }}</q-item-label>
                  </q-item-section>
                  <q-item-section side>{{ formatDate(r.createdAt) }}</q-item-section>
                </q-item>
              </q-list>
            </div>
          </q-card-section>

          <q-card-actions align="right">
            <q-btn flat :label="t('common.close')" v-close-popup />
            <template v-if="message.moderationStatus === 'UNDER_REVIEW'">
              <q-btn
                flat
                color="negative"
                :label="t('admin.moderation.blockAction')"
                data-testid="message-block-open-btn"
                @click="openBlockMessageDialog"
              />
              <q-btn
                unelevated
                color="primary"
                :label="t('admin.moderation.approveAction')"
                :loading="submitting"
                data-testid="message-approve-btn"
                @click="approveMessage"
              />
            </template>
            <span v-else class="text-meta q-ml-sm">{{ t('admin.moderation.alreadyDecided') }}</span>
          </q-card-actions>
        </template>
      </q-card>
    </q-dialog>

    <!-- Block message (reason required) -->
    <q-dialog v-model="blockDialogOpen">
      <q-card
        data-testid="message-block-dialog"
        style="min-width: 320px; max-width: 480px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.moderation.blockDialogTitle') }}</div>
          <div class="text-meta">{{ t('admin.moderation.blockDialogHint') }}</div>
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
            data-testid="message-block-reason-input"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="negative"
            :label="t('admin.moderation.blockAction')"
            :loading="submitting"
            :disable="!blockReason.trim()"
            data-testid="message-block-submit-btn"
            @click="blockMessage"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>

    <!-- Reported conversation -->
    <q-dialog v-model="conversationDialogOpen">
      <q-card
        data-testid="conversation-dialog"
        style="min-width: 360px; max-width: 720px; width: 100%"
      >
        <q-card-section>
          <div class="text-h6">{{ t('admin.moderation.conversationTitle') }}</div>
        </q-card-section>

        <q-card-section v-if="detailLoading" class="flex flex-center q-py-lg">
          <q-spinner size="40px" />
        </q-card-section>
        <q-card-section v-else-if="detailLoadError" class="text-negative">
          {{ t('admin.moderation.detailLoadError') }}
        </q-card-section>

        <template v-else-if="conversation">
          <q-card-section data-testid="conversation-detail-content">
            <div>{{ t('admin.moderation.statusLabel') }}: {{ conversation.status }}</div>

            <div v-if="conversation.reports && conversation.reports.length" class="q-mt-md">
              <div class="text-subtitle2">{{ t('admin.moderation.reportsLabel') }}</div>
              <q-list dense>
                <q-item v-for="(r, i) in conversation.reports" :key="i">
                  <q-item-section>
                    <q-item-label>{{ r.reason }}</q-item-label>
                    <q-item-label v-if="r.details" caption>{{ r.details }}</q-item-label>
                  </q-item-section>
                  <q-item-section side>{{ formatDate(r.createdAt) }}</q-item-section>
                </q-item>
              </q-list>
            </div>

            <div class="q-mt-md">
              <div class="text-subtitle2">{{ t('admin.moderation.messagesLabel') }}</div>
              <div v-if="!conversation.messages || !conversation.messages.length" class="text-meta">
                {{ t('admin.moderation.noMessages') }}
              </div>
              <q-list v-else dense separator style="max-height: 320px; overflow-y: auto">
                <q-item v-for="m in conversation.messages" :key="m.messageId">
                  <q-item-section>
                    <q-item-label caption>
                      {{ m.senderRole }} · {{ formatDate(m.createdAt) }} · {{ m.moderationStatus }}
                    </q-item-label>
                    <q-item-label>{{ m.content }}</q-item-label>
                  </q-item-section>
                </q-item>
              </q-list>
            </div>
          </q-card-section>

          <q-card-actions align="right">
            <q-btn flat :label="t('common.close')" v-close-popup />
            <q-btn
              v-if="conversation.status !== 'ACTIVE'"
              unelevated
              color="primary"
              :label="t('admin.moderation.unblockAction')"
              :loading="submitting"
              data-testid="conversation-unblock-btn"
              @click="unblockConversation"
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
import ReviewModerationPanel from 'components/admin/ReviewModerationPanel.vue'

const { t, locale } = useI18n()
const $q = useQuasar()

const tab = ref('queue')
const summary = ref(null)

const rows = ref([])
const loading = ref(false)
const loadError = ref(false)
const currentPage = ref(1)
const totalPages = ref(0)
const typeFilter = ref('ALL')

const messageDialogOpen = ref(false)
const conversationDialogOpen = ref(false)
const blockDialogOpen = ref(false)
const detailLoading = ref(false)
const detailLoadError = ref(false)
const message = ref(null)
const conversation = ref(null)
const blockReason = ref('')
const submitting = ref(false)

// Explicit literal keys (not `admin.moderation.type.${type}`) so every label stays statically
// checkable by i18nTemplateKeyResolutionSpec.
const typeLabels = computed(() => ({
  MESSAGE_REPORT: t('admin.moderation.types.MESSAGE_REPORT'),
  CONVERSATION_REPORT: t('admin.moderation.types.CONVERSATION_REPORT'),
  REVIEW_FLAG: t('admin.moderation.types.REVIEW_FLAG'),
  STRIKE_THRESHOLD: t('admin.moderation.types.STRIKE_THRESHOLD'),
  DISPUTE_RAISED: t('admin.moderation.types.DISPUTE_RAISED'),
  MODERATION_UNRESOLVED: t('admin.moderation.types.MODERATION_UNRESOLVED'),
  GDPR_ERASURE_DEADLINE: t('admin.moderation.types.GDPR_ERASURE_DEADLINE'),
  SUBSCRIPTION_ORPHANED: t('admin.moderation.types.SUBSCRIPTION_ORPHANED'),
}))

const typeOptions = computed(() => [
  { label: t('admin.moderation.allTypes'), value: 'ALL' },
  ...Object.entries(typeLabels.value).map(([value, label]) => ({ label, value })),
])

const summaryBuckets = computed(() => [
  { key: 'messageReports', label: typeLabels.value.MESSAGE_REPORT },
  { key: 'moderationHolds', label: typeLabels.value.MODERATION_UNRESOLVED },
  { key: 'conversationReports', label: typeLabels.value.CONVERSATION_REPORT },
  { key: 'reviewFlags', label: typeLabels.value.REVIEW_FLAG },
  { key: 'strikeAlerts', label: typeLabels.value.STRIKE_THRESHOLD },
  { key: 'disputes', label: typeLabels.value.DISPUTE_RAISED },
  { key: 'gdprErasureDeadlines', label: typeLabels.value.GDPR_ERASURE_DEADLINE },
  { key: 'subscriptionOrphaned', label: typeLabels.value.SUBSCRIPTION_ORPHANED },
  { key: 'total', label: t('admin.moderation.totalOpen') },
])

const columns = [
  { name: 'type', label: t('admin.moderation.colType'), field: 'type', align: 'left' },
  { name: 'summary', label: t('admin.moderation.colSummary'), field: 'summary', align: 'left' },
  {
    name: 'createdAt',
    label: t('admin.moderation.colCreatedAt'),
    field: 'createdAt',
    align: 'left',
  },
  { name: 'actions', label: t('admin.moderation.colActions'), field: 'actions', align: 'center' },
]

const contextMessages = computed(() => {
  if (!message.value) return []
  const before = message.value.contextBefore || []
  const after = message.value.contextAfter || []
  return [...before, ...after]
})

function typeLabel(type) {
  return typeLabels.value[type] ?? type
}

function isMessageAlert(type) {
  return type === 'MESSAGE_REPORT' || type === 'MODERATION_UNRESOLVED'
}

function isCoachAlert(type) {
  return type === 'STRIKE_THRESHOLD' || type === 'SUBSCRIPTION_ORPHANED'
}

// Guards a late detail response for a previously opened item overwriting the current one.
let detailToken = 0

onMounted(() => {
  loadSummary()
  loadQueue()
})

async function loadSummary() {
  try {
    summary.value = await adminApi.getModerationSummary()
  } catch {
    summary.value = null
  }
}

async function loadQueue() {
  loading.value = true
  loadError.value = false
  try {
    const resp = await adminApi.getModerationQueue(typeFilter.value, currentPage.value - 1)
    // Acting on the last row of the last page leaves that page empty — step back a page.
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

async function onTypeFilterChange() {
  currentPage.value = 1
  await loadQueue()
}

async function refreshAll() {
  await Promise.all([loadQueue(), loadSummary()])
}

async function openMessage(referenceId) {
  const token = ++detailToken
  conversationDialogOpen.value = false
  messageDialogOpen.value = true
  detailLoading.value = true
  detailLoadError.value = false
  message.value = null
  try {
    const resp = await adminApi.getMessageDetail(referenceId)
    if (token !== detailToken) return
    message.value = resp
  } catch {
    if (token !== detailToken) return
    detailLoadError.value = true
  } finally {
    if (token === detailToken) detailLoading.value = false
  }
}

async function openConversation(referenceId) {
  const token = ++detailToken
  messageDialogOpen.value = false
  conversationDialogOpen.value = true
  detailLoading.value = true
  detailLoadError.value = false
  conversation.value = null
  try {
    const resp = await adminApi.getConversationDetail(referenceId)
    if (token !== detailToken) return
    conversation.value = resp
  } catch {
    if (token !== detailToken) return
    detailLoadError.value = true
  } finally {
    if (token === detailToken) detailLoading.value = false
  }
}

async function approveMessage() {
  submitting.value = true
  try {
    await adminApi.approveMessage(message.value.messageId)
    messageDialogOpen.value = false
    $q.notify({ message: t('admin.moderation.approveSuccess'), type: 'positive' })
    await refreshAll()
  } catch {
    $q.notify({ message: t('admin.moderation.actionFailed'), type: 'negative' })
  } finally {
    submitting.value = false
  }
}

function openBlockMessageDialog() {
  blockReason.value = ''
  blockDialogOpen.value = true
}

async function blockMessage() {
  submitting.value = true
  try {
    await adminApi.blockMessage(message.value.messageId, blockReason.value.trim())
    blockDialogOpen.value = false
    messageDialogOpen.value = false
    $q.notify({ message: t('admin.moderation.blockSuccess'), type: 'positive' })
    await refreshAll()
  } catch {
    $q.notify({ message: t('admin.moderation.actionFailed'), type: 'negative' })
  } finally {
    submitting.value = false
  }
}

async function unblockConversation() {
  submitting.value = true
  try {
    await adminApi.unblockConversation(conversation.value.conversationId)
    conversationDialogOpen.value = false
    $q.notify({ message: t('admin.moderation.unblockSuccess'), type: 'positive' })
    await refreshAll()
  } catch {
    $q.notify({ message: t('admin.moderation.actionFailed'), type: 'negative' })
  } finally {
    submitting.value = false
  }
}

function formatDate(iso) {
  if (!iso) return '—'
  return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
}
</script>

<style scoped lang="scss">
.moderation-summary-cell {
  white-space: normal;
  max-width: 420px;
}
.moderation-message-body {
  white-space: pre-wrap;
  border-radius: 6px;
  background: rgba(127, 127, 127, 0.12);
}
.moderation-context-focus {
  background: rgba(255, 193, 7, 0.15);
}
</style>
