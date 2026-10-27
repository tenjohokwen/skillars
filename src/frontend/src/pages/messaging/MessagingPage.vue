<template>
  <q-page class="q-pa-md">
    <div class="messaging-page glass-card q-pa-lg">
      <div class="text-h6 q-mb-md">{{ t('messaging.pageTitle') }}</div>

      <div class="row q-gutter-md messaging-layout">
        <!-- Conversation list panel -->
        <div class="col-12 col-md-4 conversation-panel glass-card q-pa-md">
          <div class="text-subtitle2 q-mb-sm">{{ t('messaging.conversations') }}</div>
          <q-list v-if="store.conversations.length">
            <q-item
              v-for="conv in store.conversations"
              :key="conv.conversationId"
              clickable
              :active="store.activeConversationId === conv.conversationId"
              active-class="bg-primary text-white"
              @click="selectConversation(conv.conversationId)"
            >
              <q-item-section avatar>
                <q-avatar color="primary" text-color="white">
                  {{ conv.otherPartyName?.charAt(0) ?? '?' }}
                </q-avatar>
              </q-item-section>
              <q-item-section>
                <q-item-label lines="1">{{ conv.otherPartyName }}</q-item-label>
                <q-item-label caption lines="1">{{
                  conv.lastMessagePreview ?? t('messaging.noMessages')
                }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-badge v-if="conv.unreadCount > 0" color="primary" :label="conv.unreadCount" />
                <q-item-label caption>{{ relativeTime(conv.lastMessageAt) }}</q-item-label>
              </q-item-section>
              <!-- skillars-deferred-165 AC6: conversation-level report. -->
              <q-item-section side>
                <q-btn
                  flat
                  dense
                  round
                  icon="more_vert"
                  data-testid="conversation-kebab-btn"
                  @click.stop
                >
                  <q-menu>
                    <q-list>
                      <q-item
                        clickable
                        v-close-popup
                        data-testid="report-conversation-action"
                        @click="openReportDialog('conversation', conv.conversationId)"
                      >
                        <q-item-section>{{ t('messaging.reportConversation') }}</q-item-section>
                      </q-item>
                    </q-list>
                  </q-menu>
                </q-btn>
              </q-item-section>
            </q-item>
          </q-list>
          <div v-else class="text-caption text-secondary q-mt-md">
            {{ t('messaging.noConversations') }}
          </div>
        </div>

        <!-- Message thread panel -->
        <div class="col-12 col-md-8 message-panel glass-card q-pa-md column">
          <template v-if="store.activeConversationId">
            <div class="message-thread col overflow-auto q-mb-md" ref="threadRef">
              <div
                v-for="msg in currentMessages"
                :key="msg.messageId"
                class="q-mb-sm row items-center q-gutter-xs"
                :class="msg.senderId === myUserId ? 'justify-end' : 'justify-start'"
              >
                <q-chip
                  :color="msg.senderId === myUserId ? 'primary' : 'grey-3'"
                  :text-color="msg.senderId === myUserId ? 'white' : 'dark'"
                  :data-testid="`message-bubble-${msg.messageId}`"
                >
                  <span v-if="msg.locallyDeleted" class="text-italic">{{
                    t('messaging.messageDeleted')
                  }}</span>
                  <span v-else>{{ msg.content ?? t('messaging.contentRemoved') }}</span>
                </q-chip>
                <!-- skillars-deferred-165 AC6: delete (own messages only) / report (any message). -->
                <q-btn
                  v-if="!msg.locallyDeleted"
                  flat
                  dense
                  round
                  size="sm"
                  icon="more_vert"
                  :data-testid="`message-kebab-${msg.messageId}`"
                >
                  <q-menu>
                    <q-list>
                      <q-item
                        v-if="canDelete(msg)"
                        clickable
                        v-close-popup
                        data-testid="delete-message-action"
                        @click="handleDeleteMessage(msg)"
                      >
                        <q-item-section class="text-negative">{{
                          t('messaging.deleteMessage')
                        }}</q-item-section>
                      </q-item>
                      <q-item
                        clickable
                        v-close-popup
                        data-testid="report-message-action"
                        @click="openReportDialog('message', msg.messageId)"
                      >
                        <q-item-section>{{ t('messaging.reportMessage') }}</q-item-section>
                      </q-item>
                    </q-list>
                  </q-menu>
                </q-btn>
              </div>
            </div>

            <!-- Message input -->
            <div class="row q-gutter-sm items-center">
              <q-input
                v-model="newMessage"
                :placeholder="t('messaging.typeMessage')"
                outlined
                dense
                class="col"
                @keyup.enter="submitMessage"
                :maxlength="2000"
              />
              <q-btn
                color="primary"
                icon="send"
                :loading="sending"
                :disable="!newMessage.trim()"
                @click="submitMessage"
              />
            </div>
          </template>
          <div v-else class="column items-center justify-center full-height text-secondary q-pa-xl">
            <q-icon name="chat" size="48px" class="q-mb-sm" />
            <div>{{ t('messaging.selectConversation') }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- skillars-deferred-165 AC6: shared report dialog (message- and conversation-level). -->
    <q-dialog v-model="reportDialogOpen">
      <q-card data-testid="report-dialog" style="min-width: 320px; max-width: 480px; width: 100%">
        <q-card-section>
          <div class="text-h6">{{ t('messaging.reportDialogTitle') }}</div>
        </q-card-section>
        <q-card-section>
          <q-select
            v-model="reportReason"
            data-testid="report-reason-select"
            :options="reportReasonOptions"
            :label="t('messaging.reportReasonLabel')"
            emit-value
            map-options
            outlined
            dense
            class="q-mb-md"
          />
          <q-input
            v-model="reportDetails"
            data-testid="report-details-input"
            type="textarea"
            outlined
            autogrow
            maxlength="500"
            counter
            :label="t('messaging.reportDetailsLabel')"
          />
        </q-card-section>
        <q-card-actions align="right">
          <q-btn flat :label="t('common.cancel')" v-close-popup />
          <q-btn
            unelevated
            color="primary"
            data-testid="report-submit-btn"
            :label="t('messaging.reportSubmit')"
            :loading="reportSubmitting"
            :disable="!reportReason"
            @click="submitReport"
          />
        </q-card-actions>
      </q-card>
    </q-dialog>
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useMessagingStore } from 'src/stores/messaging.store'
import { useAuthStore } from 'src/stores/auth.store'
import { softDeleteMessage, reportMessage, reportConversation } from 'src/api/messaging.api'
import { date } from 'quasar'

const { t } = useI18n()
const $q = useQuasar()
const store = useMessagingStore()
const authStore = useAuthStore()

const newMessage = ref('')
const sending = ref(false)
const threadRef = ref(null)

// skillars-deferred-165 AC6 fix: authStore has no `user` object (only a flat `userId` ref) — this
// read was always undefined, so `msg.senderId === myUserId` never matched and no message ever
// rendered as "mine". Found while wiring the own-message-only Delete gate, which depends on the
// same comparison.
const myUserId = computed(() => authStore.userId)

const reportDialogOpen = ref(false)
const reportTargetType = ref(null) // 'message' | 'conversation'
const reportTargetId = ref(null)
const reportReason = ref(null)
const reportDetails = ref('')
const reportSubmitting = ref(false)

const reportReasonOptions = [
  { label: t('messaging.reportReason.HARASSMENT'), value: 'HARASSMENT' },
  { label: t('messaging.reportReason.INAPPROPRIATE_CONTENT'), value: 'INAPPROPRIATE_CONTENT' },
  { label: t('messaging.reportReason.SPAM'), value: 'SPAM' },
  { label: t('messaging.reportReason.CONTACT_SOLICITATION'), value: 'CONTACT_SOLICITATION' },
  { label: t('messaging.reportReason.OTHER'), value: 'OTHER' },
]

const currentMessages = computed(() => {
  if (!store.activeConversationId) return []
  return store.messages[store.activeConversationId] ?? []
})

onMounted(async () => {
  await store.loadConversations()
})

onUnmounted(() => {
  store.disconnectSse()
})

async function selectConversation(conversationId) {
  store.disconnectSse()
  store.activeConversationId = conversationId
  await store.loadMessages(conversationId)
  store.connectSse(conversationId)
}

async function submitMessage() {
  if (!newMessage.value.trim() || !store.activeConversationId) return
  sending.value = true
  try {
    await store.postMessage(store.activeConversationId, newMessage.value.trim())
    newMessage.value = ''
  } finally {
    sending.value = false
  }
}

// MessagingService.softDeleteMessage always refuses a message under active moderation
// (MODERATION_PENDING) — don't offer an action that can only fail.
const UNDELETABLE_MODERATION_STATUSES = ['UNDER_REVIEW', 'BLOCKED']
function canDelete(msg) {
  return (
    msg.senderId === myUserId.value &&
    !UNDELETABLE_MODERATION_STATUSES.includes(msg.moderationStatus)
  )
}

async function handleDeleteMessage(msg) {
  try {
    // 204 No Content (MessagingResource#softDeleteMessage) — optimistically update locally, don't
    // rely on a response body. `msg` is the same object reference the store holds, so mutating it
    // here is reactive without a separate store action.
    await softDeleteMessage(store.activeConversationId, msg.messageId)
    msg.locallyDeleted = true
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'messaging.alreadyDeleted') {
      msg.locallyDeleted = true
    } else if (errorKey === 'messaging.moderationPending') {
      $q.notify({ message: t('messaging.deleteModerationPending'), type: 'negative' })
    } else {
      $q.notify({ message: t('messaging.deleteFailed'), type: 'negative' })
    }
  }
}

function openReportDialog(targetType, targetId) {
  reportTargetType.value = targetType
  reportTargetId.value = targetId
  reportReason.value = null
  reportDetails.value = ''
  reportDialogOpen.value = true
}

async function submitReport() {
  reportSubmitting.value = true
  try {
    const payload = { reason: reportReason.value, details: reportDetails.value }
    if (reportTargetType.value === 'message') {
      await reportMessage(store.activeConversationId, reportTargetId.value, payload)
    } else {
      await reportConversation(reportTargetId.value, payload)
    }
    reportDialogOpen.value = false
    $q.notify({ message: t('messaging.reportSuccess'), type: 'positive' })
  } catch (err) {
    const errorKey = err?.response?.data?.errorMsg?.errorKey
    if (errorKey === 'messaging.alreadyReported') {
      $q.notify({ message: t('messaging.alreadyReported'), type: 'negative' })
    } else {
      $q.notify({ message: t('messaging.reportFailed'), type: 'negative' })
    }
  } finally {
    reportSubmitting.value = false
  }
}

function relativeTime(isoString) {
  if (!isoString) return ''
  return date.formatDate(isoString, 'D MMM HH:mm')
}

watch(currentMessages, () => {
  if (threadRef.value) {
    threadRef.value.scrollTop = threadRef.value.scrollHeight
  }
})
</script>

<style scoped>
.messaging-layout {
  min-height: 60vh;
}
.conversation-panel {
  max-height: 70vh;
  overflow-y: auto;
}
.message-panel {
  min-height: 400px;
}
.message-thread {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}
</style>
