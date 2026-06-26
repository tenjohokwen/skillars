import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchConversations,
  fetchMessages,
  sendMessage,
  initiateConversation,
  subscribeToEvents,
} from 'src/api/messaging.api'

export const useMessagingStore = defineStore('messaging', () => {
  const conversations = ref([])
  const messages = ref({})
  const activeConversationId = ref(null)
  const loading = ref(false)
  const error = ref(null)

  let eventSource = null
  let reconnectAttempts = 0
  let reconnectTimer = null
  let sseRecoveryTimer = null

  async function loadConversations() {
    loading.value = true
    error.value = null
    try {
      const res = await fetchConversations()
      conversations.value = res.data
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  async function loadMessages(conversationId, page = 0) {
    loading.value = true
    error.value = null
    try {
      const res = await fetchMessages(conversationId, page)
      if (!messages.value[conversationId]) {
        messages.value[conversationId] = []
      }
      messages.value[conversationId] = res.data.content
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  async function postMessage(conversationId, content) {
    const res = await sendMessage(conversationId, content)
    if (!messages.value[conversationId]) {
      messages.value[conversationId] = []
    }
    messages.value[conversationId].unshift(res.data)
    await loadConversations()
    return res.data
  }

  async function openConversation(coachId, playerId) {
    const res = await initiateConversation(coachId, playerId)
    activeConversationId.value = res.data.conversationId
    await loadConversations()
    await loadMessages(res.data.conversationId)
    return res.data
  }

  function connectSse(conversationId) {
    disconnectSse()
    const connect = () => {
      eventSource = subscribeToEvents(conversationId)
      eventSource.addEventListener('NEW_MESSAGE', async () => {
        await loadMessages(conversationId)
        await loadConversations()
      })
      eventSource.onopen = () => {
        reconnectAttempts = 0
        if (reconnectTimer) {
          clearInterval(reconnectTimer)
          reconnectTimer = null
        }
        if (sseRecoveryTimer) {
          clearInterval(sseRecoveryTimer)
          sseRecoveryTimer = null
        }
      }
      eventSource.onerror = () => {
        eventSource.close()
        eventSource = null
        reconnectAttempts++
        if (reconnectAttempts < 3) {
          if (sseRecoveryTimer) {
            clearInterval(sseRecoveryTimer)
            sseRecoveryTimer = null
          }
          const delay = Math.min(1000 * Math.pow(2, reconnectAttempts - 1), 30000)
          reconnectTimer = setTimeout(connect, delay)
        } else {
          reconnectTimer = setInterval(async () => {
            await loadMessages(conversationId)
            await loadConversations()
          }, 2000)
          if (!sseRecoveryTimer) {
            sseRecoveryTimer = setInterval(() => {
              clearInterval(reconnectTimer)
              reconnectTimer = null
              reconnectAttempts = 0
              connect()
            }, 30000)
          }
        }
      }
    }
    connect()
  }

  function disconnectSse() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      clearInterval(reconnectTimer)
      reconnectTimer = null
    }
    if (sseRecoveryTimer) {
      clearInterval(sseRecoveryTimer)
      sseRecoveryTimer = null
    }
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    reconnectAttempts = 0
  }

  return {
    conversations,
    messages,
    activeConversationId,
    loading,
    error,
    loadConversations,
    loadMessages,
    postMessage,
    openConversation,
    connectSse,
    disconnectSse,
  }
})
