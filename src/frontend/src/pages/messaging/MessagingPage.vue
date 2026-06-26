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
                <q-item-label caption lines="1">{{ conv.lastMessagePreview ?? t('messaging.noMessages') }}</q-item-label>
              </q-item-section>
              <q-item-section side>
                <q-badge v-if="conv.unreadCount > 0" color="primary" :label="conv.unreadCount" />
                <q-item-label caption>{{ relativeTime(conv.lastMessageAt) }}</q-item-label>
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
                class="q-mb-sm"
                :class="msg.senderId === myUserId ? 'text-right' : 'text-left'"
              >
                <q-chip
                  :color="msg.senderId === myUserId ? 'primary' : 'grey-3'"
                  :text-color="msg.senderId === myUserId ? 'white' : 'dark'"
                >
                  {{ msg.content ?? t('messaging.contentRemoved') }}
                </q-chip>
              </div>
            </div>

            <!-- Message input -->
            <div class="row q-gutter-sm items-center">
              <q-input
                v-model="newMessage"
                :placeholder="t('messaging.typeMessage')"
                outlined dense
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
  </q-page>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessagingStore } from 'src/stores/messaging.store'
import { useAuthStore } from 'src/stores/auth.store'
import { date } from 'quasar'

const { t } = useI18n()
const store = useMessagingStore()
const authStore = useAuthStore()

const newMessage = ref('')
const sending = ref(false)
const threadRef = ref(null)

const myUserId = computed(() => authStore.user?.id ?? null)

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
