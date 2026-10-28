<template>
  <q-page class="q-pa-md">
    <div class="messaging-page glass-card q-pa-lg">
      <div class="row items-center q-mb-md q-gutter-sm">
        <div class="text-h6 col">{{ t('messaging.pageTitle') }}</div>
        <!-- Parent oversight (story 8.2): view one child's conversations (adult children excluded —
             the backend refuses oversight for 18+ with messaging.parentalOversightNotApplicable). -->
        <q-select
          v-if="oversightOptions.length > 1"
          :model-value="store.oversightPlayerId ?? OWN_CONVERSATIONS"
          :options="oversightOptions"
          :label="t('messaging.oversight.viewLabel')"
          emit-value
          map-options
          outlined
          dense
          class="oversight-select"
          data-testid="oversight-child-select"
          @update:model-value="changeOversightPlayer"
        />
      </div>

      <q-banner
        v-if="inOversight"
        dense
        rounded
        class="q-mb-md bg-info text-white"
        data-testid="oversight-banner"
      >
        {{ t('messaging.oversight.banner', { name: oversightChildName }) }}
      </q-banner>

      <div class="row q-gutter-md messaging-layout">
        <!-- Conversation list panel -->
        <div class="col-12 col-md-4 conversation-panel glass-card q-pa-md">
          <div class="text-subtitle2 q-mb-sm">{{ t('messaging.conversations') }}</div>
          <q-list v-if="store.conversations.length">
            <q-item
              v-for="conv in store.conversations"
              :key="conv.conversationId"
              clickable
              :active="sameId(store.activeConversationId, conv.conversationId)"
              active-class="bg-primary text-white"
              :data-testid="`conversation-item-${conv.conversationId}`"
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
                <q-item-label v-if="inOversight || conv.status === 'BLOCKED'" caption>
                  <q-badge
                    v-if="conv.status === 'BLOCKED'"
                    color="negative"
                    :label="t('messaging.blockedChip')"
                    class="q-mr-xs"
                    data-testid="conversation-blocked-chip"
                  />
                  <q-badge
                    v-if="inOversight"
                    outline
                    color="primary"
                    data-testid="oversight-role-chip"
                    :label="
                      conv.parentObserver
                        ? t('messaging.oversight.observerChip')
                        : t('messaging.oversight.primaryChip')
                    "
                  />
                </q-item-label>
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
          <div v-else-if="listErrorKey" class="text-caption text-negative q-mt-md">
            {{ t(listErrorKey) }}
          </div>
          <div v-else class="text-caption text-secondary q-mt-md">
            {{ t('messaging.noConversations') }}
          </div>
        </div>

        <!-- Message thread panel -->
        <div class="col-12 col-md-8 message-panel glass-card q-pa-md column">
          <template v-if="store.activeConversationId">
            <div class="message-thread col overflow-auto q-mb-md" ref="threadRef">
              <div v-if="activePagination?.hasMore" class="row justify-center q-mb-sm">
                <q-btn
                  flat
                  dense
                  no-caps
                  color="primary"
                  icon="expand_less"
                  data-testid="load-older-btn"
                  :label="t('messaging.loadOlder')"
                  :loading="activePagination.loadingOlder"
                  @click="loadOlder"
                />
              </div>
              <div
                v-for="msg in currentMessages"
                :key="msg.messageId"
                class="q-mb-sm row items-center q-gutter-xs"
                :class="isMine(msg) ? 'justify-end' : 'justify-start'"
              >
                <q-chip
                  :color="isMine(msg) ? 'primary' : 'grey-3'"
                  :text-color="isMine(msg) ? 'white' : 'dark'"
                  :data-testid="`message-bubble-${msg.messageId}`"
                >
                  <span v-if="msg.locallyDeleted" class="text-italic">{{
                    t('messaging.messageDeleted')
                  }}</span>
                  <span
                    v-else-if="moderationNotice(msg)"
                    class="text-italic"
                    :data-testid="`moderation-notice-${msg.messageId}`"
                  >
                    <q-icon :name="moderationIcon(msg)" size="16px" class="q-mr-xs" />{{
                      t(moderationNotice(msg))
                    }}
                  </span>
                  <span v-else>{{ msg.content ?? t('messaging.contentRemoved') }}</span>
                  <q-icon
                    v-if="!msg.locallyDeleted && isMine(msg) && msg.moderationStatus === 'PENDING'"
                    name="schedule"
                    size="14px"
                    class="q-ml-xs"
                    :title="t('messaging.moderation.pending')"
                  />
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

            <!-- Oversight is a read-only lens (it does not mark messages read and includes BLOCKED
                 history); replying goes through the parent's own conversation list. -->
            <div v-if="inOversight" class="row items-center q-gutter-sm">
              <div class="text-caption text-secondary col">
                {{ t('messaging.oversight.readOnly') }}
              </div>
              <q-btn
                v-if="store.activeConversation?.canSend"
                flat
                no-caps
                color="primary"
                data-testid="oversight-reply-btn"
                :label="t('messaging.oversight.replyInMyConversations')"
                @click="replyFromOversight"
              />
            </div>
            <template v-else>
              <q-banner
                v-if="composerLockKey"
                dense
                rounded
                class="q-mb-sm bg-grey-3 text-dark"
                data-testid="composer-locked-banner"
              >
                {{ t(composerLockKey) }}
              </q-banner>
              <!-- Message input -->
              <div v-else class="row q-gutter-sm items-center">
                <q-input
                  v-model="newMessage"
                  :placeholder="t('messaging.typeMessage')"
                  outlined
                  dense
                  class="col"
                  data-testid="message-input"
                  @keyup.enter="submitMessage"
                  :maxlength="2000"
                />
                <q-btn
                  color="primary"
                  icon="send"
                  data-testid="send-btn"
                  :loading="sending"
                  :disable="!newMessage.trim()"
                  @click="submitMessage"
                />
              </div>
            </template>
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
          <!-- Story 8.4: reporting a conversation is also how a user blocks it — there is no separate
               block action, the report sets the conversation BLOCKED for both parties. -->
          <div
            v-if="reportTargetType === 'conversation'"
            class="text-caption q-mt-xs"
            data-testid="report-conversation-blocks-hint"
          >
            {{ t('messaging.reportConversationBlocksHint') }}
          </div>
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
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useQuasar } from 'quasar'
import { useRoute, useRouter } from 'vue-router'
import { useMessagingStore, sameId } from 'src/stores/messaging.store'
import { useAuthStore } from 'src/stores/auth.store'
import { usePlayerStore } from 'src/stores/playerStore'
import { softDeleteMessage, reportMessage, reportConversation } from 'src/api/messaging.api'
import { messagingErrorI18nKey } from 'src/utils/messagingErrors'
import { date } from 'quasar'

const { t } = useI18n()
const $q = useQuasar()
const route = useRoute()
const router = useRouter()
const store = useMessagingStore()
const authStore = useAuthStore()
const playerStore = usePlayerStore()

const newMessage = ref('')
const sending = ref(false)
const threadRef = ref(null)

// skillars-deferred-165 AC6 fix: authStore has no `user` object (only a flat `userId` ref) — this
// read was always undefined, so `msg.senderId === myUserId` never matched and no message ever
// rendered as "mine". Found while wiring the own-message-only Delete gate, which depends on the
// same comparison.
const myUserId = computed(() => authStore.userId)
// Long ids are serialised as JSON strings while the auth cookie id may be a number — compare as text.
const isMine = (msg) => sameId(msg.senderId, myUserId.value)

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
  if (store.activeConversationId == null) return []
  return store.messages[String(store.activeConversationId)] ?? []
})

const activePagination = computed(() =>
  store.activeConversationId == null
    ? null
    : (store.pagination?.[String(store.activeConversationId)] ?? null),
)

// ── Parent oversight ──
// q-select treats a null option value as "no selection", so "my own conversations" gets a sentinel.
const OWN_CONVERSATIONS = '__own__'
const inOversight = computed(() => store.oversightPlayerId != null)
const oversightChildren = computed(() =>
  authStore.isParent ? playerStore.players.filter((p) => p.ageTier !== 'ADULT') : [],
)
const oversightOptions = computed(() => {
  if (!oversightChildren.value.length) return []
  return [
    { label: t('messaging.oversight.myConversations'), value: OWN_CONVERSATIONS },
    ...oversightChildren.value.map((p) => ({ label: p.name, value: p.id })),
  ]
})
const oversightChildName = computed(
  () => playerStore.players.find((p) => sameId(p.id, store.oversightPlayerId))?.name ?? '',
)

const listErrorKey = computed(() => (store.error ? messagingErrorI18nKey(store.error) : null))

// Composer gate — advisory (sendMessage re-checks); from the conversation summary's status/canSend.
const composerLockKey = computed(() => {
  const conv = store.activeConversation
  if (!conv) return null
  if (conv.status === 'BLOCKED') return 'messaging.blockedBanner'
  if (conv.canSend === false) return 'messaging.cannotSend'
  return null
})

async function changeOversightPlayer(value) {
  newMessage.value = ''
  await store.setOversightPlayer(value === OWN_CONVERSATIONS ? null : value)
}

async function replyFromOversight() {
  const id = store.activeConversationId
  await store.setOversightPlayer(null)
  await selectConversation(id)
}

async function openFromRoute(conversationId) {
  if (conversationId == null || conversationId === '') return
  // A deep link always targets the caller's own conversations, never an oversight view.
  if (store.oversightPlayerId != null) await store.setOversightPlayer(null)
  await selectConversation(conversationId)
}

onMounted(async () => {
  if (authStore.isParent && !playerStore.players.length) {
    try {
      await playerStore.fetchPlayers()
    } catch {
      // The oversight selector simply stays hidden — the own-conversations view still works.
    }
  }
  await store.loadConversations()
  if (route?.query?.conversation) {
    await openFromRoute(route.query.conversation)
  } else if (store.activeConversationId != null) {
    // The store outlives this page: a thread still selected from an earlier visit is re-fetched and
    // re-subscribed (onUnmounted closed its SSE stream) instead of being shown from a stale cache.
    await selectConversation(store.activeConversationId)
  }
})

watch(
  () => route?.query?.conversation,
  (id, prev) => {
    // selectConversation itself writes this query param — don't re-open what is already open.
    if (id && id !== prev && !sameId(id, store.activeConversationId)) openFromRoute(id)
  },
)

onUnmounted(() => {
  store.disconnectSse()
})

async function selectConversation(conversationId) {
  store.disconnectSse()
  store.setActiveConversation(conversationId)
  newMessage.value = ''
  await store.loadMessages(conversationId, { reset: true })
  // The user may have picked another conversation while this one was loading — that selection owns
  // the SSE stream and the URL now.
  if (!sameId(store.activeConversationId, conversationId)) return
  store.connectSse(conversationId)
  // Keep the URL in step so a refresh / shared link reopens the same thread.
  if (!inOversight.value && route?.query?.conversation !== String(conversationId)) {
    router?.replace({ query: { ...route?.query, conversation: String(conversationId) } })
  }
}

async function submitMessage() {
  if (!newMessage.value.trim() || store.activeConversationId == null || sending.value) return
  sending.value = true
  try {
    await store.postMessage(store.activeConversationId, newMessage.value.trim())
    newMessage.value = ''
  } catch (err) {
    // Keep the draft: the user can fix it (invalidContent) or copy it elsewhere.
    $q.notify({ message: t(messagingErrorI18nKey(err)), type: 'negative' })
  } finally {
    sending.value = false
  }
}

// ── Moderation indicators (story 8.3) ──
// The API nulls content for UNDER_REVIEW and BLOCKED for both parties; the SENDER is told the
// message is pending review / was not delivered, the recipient that it was held / removed.
function moderationNotice(msg) {
  const mine = isMine(msg)
  if (msg.moderationStatus === 'BLOCKED') {
    return mine ? 'messaging.moderation.blockedOwn' : 'messaging.moderation.blocked'
  }
  if (msg.moderationStatus === 'UNDER_REVIEW') {
    return mine ? 'messaging.moderation.pendingReviewOwn' : 'messaging.moderation.underReview'
  }
  return null
}

function moderationIcon(msg) {
  return msg.moderationStatus === 'BLOCKED' ? 'block' : 'hourglass_empty'
}

// MessagingService.softDeleteMessage always refuses a message under active moderation
// (MODERATION_PENDING) — don't offer an action that can only fail.
const UNDELETABLE_MODERATION_STATUSES = ['UNDER_REVIEW', 'BLOCKED']
function canDelete(msg) {
  return isMine(msg) && !UNDELETABLE_MODERATION_STATUSES.includes(msg.moderationStatus)
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
      // MessagingReportService.reportConversation sets the conversation BLOCKED.
      store.markConversationBlocked(reportTargetId.value)
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

// Loading an older page prepends; keep the viewport anchored on the message the user was reading
// instead of jumping (the bottom-anchor watcher below only fires when the NEWEST message changes).
async function loadOlder() {
  const el = threadRef.value
  const prevHeight = el?.scrollHeight ?? 0
  const prevTop = el?.scrollTop ?? 0
  const loaded = await store.loadOlderMessages(store.activeConversationId)
  if (!loaded || !el) return
  await nextTick()
  el.scrollTop = el.scrollHeight - prevHeight + prevTop
}

const newestMessageId = computed(() => {
  const list = currentMessages.value
  return list.length ? `${store.activeConversationId}:${list[list.length - 1].messageId}` : null
})

watch(newestMessageId, async () => {
  await nextTick()
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
.oversight-select {
  min-width: 220px;
}
</style>
