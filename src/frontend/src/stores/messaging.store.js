import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchConversations,
  fetchMessages,
  fetchPlayerConversations,
  fetchPlayerConversationMessages,
  sendMessage,
  initiateConversation,
  subscribeToEvents,
} from 'src/api/messaging.api'

export const MESSAGE_PAGE_SIZE = 20

// Long ids arrive as JSON strings project-wide, but SSE payloads and route queries are not guaranteed
// to share that shape — always compare ids through this.
export const sameId = (a, b) => a != null && b != null && String(a) === String(b)

function byChronology(a, b) {
  const ta = new Date(a.createdAt ?? 0).getTime()
  const tb = new Date(b.createdAt ?? 0).getTime()
  if (ta !== tb) return ta - tb
  // Message ids are TSIDs (time-ordered) — the backend's own tie-breaker (MessageRepository). They
  // exceed Number.MAX_SAFE_INTEGER, hence the string-length-then-lexicographic numeric compare.
  const ia = String(a.messageId ?? '')
  const ib = String(b.messageId ?? '')
  if (ia.length !== ib.length) return ia.length - ib.length
  return ia < ib ? -1 : ia > ib ? 1 : 0
}

/**
 * Upserts `incoming` into `existing` by messageId and returns the result oldest-first.
 *
 * Server rows replace the cached copy (a moderation status can change between fetches) but the local
 * `locallyDeleted` flag survives — the server simply omits a soft-deleted row, it does not report it.
 * Offset pages shift when new messages arrive, so an "older" page can repeat rows already held: the
 * de-duplication here is what makes that harmless.
 */
export function mergeMessages(existing, incoming) {
  const byId = new Map()
  for (const m of existing ?? []) byId.set(String(m.messageId), m)
  for (const m of incoming ?? []) {
    const prev = byId.get(String(m.messageId))
    byId.set(String(m.messageId), prev?.locallyDeleted ? { ...m, locallyDeleted: true } : m)
  }
  return [...byId.values()].sort(byChronology)
}

/**
 * Applies a fresh NEWEST page on top of the cache: like mergeMessages, but a cached server row that
 * falls inside the window the page covers and is missing from it was soft-deleted by its sender (the
 * API omits deleted rows rather than reporting them), so it is evicted. Rows older than the page's
 * oldest row are outside that window and kept. The viewer's own locally-deleted rows stay, as their
 * "Message deleted" placeholder.
 */
export function applyNewestPage(existing, page) {
  const content = page?.content ?? []
  const seen = new Set(content.map((m) => String(m.messageId)))
  const oldest = content.length ? [...content].sort(byChronology)[0] : null
  const kept = (existing ?? []).filter((m) => {
    if (m.locallyDeleted || seen.has(String(m.messageId))) return true
    // The page holds the entire history (`last`) — anything else cached is gone server-side.
    if (page?.last === true) return false
    return oldest != null && byChronology(m, oldest) < 0
  })
  return mergeMessages(kept, content)
}

function parseEventData(evt) {
  try {
    return evt?.data ? JSON.parse(evt.data) : null
  } catch {
    return null
  }
}

export const useMessagingStore = defineStore('messaging', () => {
  const conversations = ref([])
  // conversationId -> messages, OLDEST FIRST (the API pages newest-first; see mergeMessages).
  const messages = ref({})
  // conversationId -> { nextPage, hasMore, loadingOlder }
  const pagination = ref({})
  // conversationId -> ConversationSummaryDto returned by openConversation, for a thread that is not in
  // the list (e.g. a BLOCKED conversation, which the list endpoint excludes).
  const conversationMeta = ref({})
  const activeConversationId = ref(null)
  // Parent oversight (story 8.2): when set, the list and message reads go through the
  // /players/{id}/... oversight endpoints for that child instead of the caller's own conversations.
  const oversightPlayerId = ref(null)
  const loading = ref(false)
  const error = ref(null)

  // Bumped whenever the active conversation (or the oversight child) changes. A message fetch that
  // resolves after the user has moved on is dropped instead of being applied to the new view.
  let viewGeneration = 0
  let conversationsRequestSeq = 0

  let eventSource = null
  let reconnectAttempts = 0
  let reconnectTimer = null
  let sseRecoveryTimer = null

  const activeConversation = computed(() => {
    const id = activeConversationId.value
    if (id == null) return null
    return (
      conversations.value.find((c) => sameId(c.conversationId, id)) ??
      conversationMeta.value[String(id)] ??
      null
    )
  })

  async function loadConversations() {
    const seq = ++conversationsRequestSeq
    const playerId = oversightPlayerId.value
    loading.value = true
    error.value = null
    try {
      const res =
        playerId != null ? await fetchPlayerConversations(playerId) : await fetchConversations()
      // A slower response for a previous mode (own list vs. a child's oversight list) must not
      // overwrite the list the user is now looking at.
      if (seq === conversationsRequestSeq) conversations.value = res ?? []
    } catch (e) {
      if (seq === conversationsRequestSeq) error.value = e
    } finally {
      if (seq === conversationsRequestSeq) loading.value = false
    }
  }

  function fetchPage(conversationId, page) {
    const playerId = oversightPlayerId.value
    return playerId != null
      ? fetchPlayerConversationMessages(playerId, conversationId, page, MESSAGE_PAGE_SIZE)
      : fetchMessages(conversationId, page, MESSAGE_PAGE_SIZE)
  }

  /**
   * Fetches the newest page. By default it is applied ON TOP of the cache (applyNewestPage) — the old
   * overwrite threw away every older page the user had loaded each time an SSE event arrived.
   * `{ reset: true }` (used when a conversation is (re)opened) replaces the cache and the older-page
   * cursor instead: a cache left behind while the user was elsewhere can have a gap the cursor no
   * longer describes.
   */
  async function loadMessages(conversationId, { reset = false } = {}) {
    const generation = viewGeneration
    loading.value = true
    error.value = null
    try {
      const res = await fetchPage(conversationId, 0)
      if (generation !== viewGeneration) return
      const key = String(conversationId)
      const fresh = reset || !messages.value[key] || !pagination.value[key]
      messages.value[key] = fresh
        ? mergeMessages([], res?.content)
        : applyNewestPage(messages.value[key], res)
      if (fresh) {
        pagination.value[key] = { nextPage: 1, hasMore: res?.last === false, loadingOlder: false }
      }
    } catch (e) {
      if (generation === viewGeneration) error.value = e
    } finally {
      if (generation === viewGeneration) loading.value = false
    }
  }

  async function loadOlderMessages(conversationId) {
    const key = String(conversationId)
    const state = pagination.value[key]
    if (!state || !state.hasMore || state.loadingOlder) return false
    const generation = viewGeneration
    state.loadingOlder = true
    try {
      const res = await fetchPage(conversationId, state.nextPage)
      if (generation !== viewGeneration) return false
      messages.value[key] = mergeMessages(messages.value[key], res?.content)
      state.nextPage += 1
      state.hasMore = res?.last === false
      return true
    } catch (e) {
      if (generation === viewGeneration) error.value = e
      return false
    } finally {
      state.loadingOlder = false
    }
  }

  function setActiveConversation(conversationId) {
    viewGeneration++
    activeConversationId.value = conversationId
  }

  async function setOversightPlayer(playerId) {
    disconnectSse()
    viewGeneration++
    oversightPlayerId.value = playerId
    activeConversationId.value = null
    // The two modes read the same conversation ids through different endpoints (the oversight read
    // does not mark messages read) — never reuse a cache filled by the other mode.
    messages.value = {}
    pagination.value = {}
    conversations.value = []
    await loadConversations()
  }

  /**
   * Sends a message. Rethrows on failure so the caller can keep the draft and show the mapped error;
   * a refused send because the conversation got BLOCKED also flips the cached status so the composer
   * closes without a reload.
   */
  async function postMessage(conversationId, content) {
    let res
    try {
      res = await sendMessage(conversationId, content)
    } catch (e) {
      if (e?.response?.data?.errorMsg?.errorKey === 'messaging.conversationBlocked') {
        markConversationBlocked(conversationId)
      }
      throw e
    }
    const key = String(conversationId)
    messages.value[key] = mergeMessages(messages.value[key], [res])
    // The send itself succeeded — a failed list refresh must not surface as a send failure.
    try {
      await loadConversations()
    } catch {
      /* loadConversations records its own error */
    }
    return res
  }

  /**
   * The own-conversation list endpoints exclude BLOCKED rows, so the next list refresh drops this
   * conversation — the BLOCKED summary is therefore also kept in conversationMeta, which
   * activeConversation falls back to, so the composer stays closed.
   */
  function markConversationBlocked(conversationId) {
    const key = String(conversationId)
    const listed = conversations.value.find((c) => sameId(c.conversationId, conversationId))
    if (listed) {
      listed.status = 'BLOCKED'
      listed.canSend = false
    }
    conversationMeta.value[key] = {
      ...(conversationMeta.value[key] ?? listed ?? { conversationId }),
      status: 'BLOCKED',
      canSend: false,
    }
  }

  /**
   * Opens (creating if needed) the coach/player conversation. Idempotent server-side, so a double
   * click resolves to the same conversationId.
   */
  async function openConversation(coachId, playerId) {
    const res = await initiateConversation(coachId, playerId)
    conversationMeta.value[String(res.conversationId)] = res
    return res
  }

  function applyModerationBlocked(conversationId, messageId) {
    const list = messages.value[String(conversationId)]
    const msg = list?.find((m) => sameId(m.messageId, messageId))
    if (msg) {
      msg.moderationStatus = 'BLOCKED'
      msg.content = null
      return true
    }
    return false
  }

  function connectSse(conversationId) {
    disconnectSse()
    const connect = () => {
      eventSource = subscribeToEvents(conversationId)
      // The emitter is per USER, not per conversation (MessagingEmitterRegistry), so an event can
      // belong to a different conversation than the one this stream was opened for — route by the
      // payload's conversationId and only fall back to the subscribed one when it is missing.
      eventSource.addEventListener('NEW_MESSAGE', async (evt) => {
        const data = parseEventData(evt)
        const target = data?.conversationId ?? conversationId
        if (sameId(target, activeConversationId.value)) {
          await loadMessages(activeConversationId.value)
        }
        await loadConversations()
      })
      // Story 8.3: sent to the SENDER when moderation (or an admin) blocks their message.
      eventSource.addEventListener('MESSAGE_BLOCKED', async (evt) => {
        const data = parseEventData(evt)
        const target = data?.conversationId ?? conversationId
        const applied = data?.messageId != null && applyModerationBlocked(target, data.messageId)
        if (!applied && sameId(target, activeConversationId.value)) {
          await loadMessages(activeConversationId.value)
        }
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
    pagination,
    conversationMeta,
    activeConversationId,
    activeConversation,
    oversightPlayerId,
    loading,
    error,
    loadConversations,
    loadMessages,
    loadOlderMessages,
    setActiveConversation,
    setOversightPlayer,
    postMessage,
    markConversationBlocked,
    openConversation,
    applyModerationBlocked,
    connectSse,
    disconnectSse,
  }
})
