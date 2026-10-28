// feat/messaging-completion: messaging.store.js — paged history (prepend, de-dup, out-of-order
// guard), send-failure propagation, MESSAGE_BLOCKED / per-conversation SSE routing, parent oversight
// endpoints and the openConversation meta fallback.

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('src/api/messaging.api', () => ({
  fetchConversations: vi.fn(),
  fetchMessages: vi.fn(),
  fetchPlayerConversations: vi.fn(),
  fetchPlayerConversationMessages: vi.fn(),
  sendMessage: vi.fn(),
  initiateConversation: vi.fn(),
  subscribeToEvents: vi.fn(),
}))

import {
  fetchConversations,
  fetchMessages,
  fetchPlayerConversations,
  fetchPlayerConversationMessages,
  sendMessage,
  initiateConversation,
  subscribeToEvents,
} from 'src/api/messaging.api'
import { useMessagingStore, mergeMessages } from 'src/stores/messaging.store'

// API pages are newest-first (MessageRepository ORDER BY createdAt DESC, id DESC).
const msg = (id, minute, extra = {}) => ({
  messageId: String(id),
  senderId: '1',
  content: `m${id}`,
  moderationStatus: 'APPROVED',
  createdAt: `2026-10-01T10:${String(minute).padStart(2, '0')}:00Z`,
  ...extra,
})
const page = (content, last) => ({ content, last })

function deferred() {
  let resolve
  const promise = new Promise((r) => (resolve = r))
  return { promise, resolve }
}

function fakeEventSource() {
  const handlers = {}
  return {
    handlers,
    addEventListener: vi.fn((name, fn) => (handlers[name] = fn)),
    close: vi.fn(),
  }
}

describe('messaging.store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    fetchConversations.mockResolvedValue([])
  })

  describe('paged history', () => {
    it('stores the newest page oldest-first and derives hasMore from `last`', async () => {
      fetchMessages.mockResolvedValue(page([msg(3, 3), msg(2, 2)], false))
      const store = useMessagingStore()
      store.setActiveConversation('c1')

      await store.loadMessages('c1')

      expect(fetchMessages).toHaveBeenCalledWith('c1', 0, 20)
      expect(store.messages.c1.map((m) => m.messageId)).toEqual(['2', '3'])
      expect(store.pagination.c1).toMatchObject({ nextPage: 1, hasMore: true })
    })

    it('loadOlderMessages prepends the next page without duplicating rows a shifted page repeats', async () => {
      fetchMessages
        .mockResolvedValueOnce(page([msg(4, 4), msg(3, 3)], false))
        // A new message arrived meanwhile, so page 1 now starts with the old page-0 tail (3).
        .mockResolvedValueOnce(page([msg(3, 3), msg(2, 2), msg(1, 1)], true))
      const store = useMessagingStore()
      store.setActiveConversation('c1')
      await store.loadMessages('c1')

      const loaded = await store.loadOlderMessages('c1')

      expect(loaded).toBe(true)
      expect(fetchMessages).toHaveBeenLastCalledWith('c1', 1, 20)
      expect(store.messages.c1.map((m) => m.messageId)).toEqual(['1', '2', '3', '4'])
      expect(store.pagination.c1).toMatchObject({ nextPage: 2, hasMore: false })
      // Nothing more to load: no further request.
      expect(await store.loadOlderMessages('c1')).toBe(false)
      expect(fetchMessages).toHaveBeenCalledTimes(2)
    })

    it('a newest-page refresh (SSE) merges instead of discarding the older pages already loaded', async () => {
      fetchMessages
        .mockResolvedValueOnce(page([msg(3, 3), msg(2, 2)], false))
        .mockResolvedValueOnce(page([msg(1, 1)], true))
        .mockResolvedValueOnce(page([msg(4, 4), msg(3, 3)], false))
      const store = useMessagingStore()
      store.setActiveConversation('c1')
      await store.loadMessages('c1')
      await store.loadOlderMessages('c1')

      await store.loadMessages('c1')

      expect(store.messages.c1.map((m) => m.messageId)).toEqual(['1', '2', '3', '4'])
      // Refresh must not reset the older-page cursor.
      expect(store.pagination.c1).toMatchObject({ nextPage: 2, hasMore: false })
    })

    it('drops a message page that resolves after the user switched conversation', async () => {
      const slow = deferred()
      fetchMessages.mockReturnValueOnce(slow.promise)
      const store = useMessagingStore()
      store.setActiveConversation('a')
      const pending = store.loadMessages('a')

      store.setActiveConversation('b')
      slow.resolve(page([msg(9, 9)], true))
      await pending

      expect(store.messages.a).toBeUndefined()
      expect(store.pagination.a).toBeUndefined()
    })

    it('a refresh evicts a cached row the other party soft-deleted inside the refreshed window', async () => {
      fetchMessages
        .mockResolvedValueOnce(page([msg(3, 3), msg(2, 2)], false))
        .mockResolvedValueOnce(page([msg(1, 1)], true))
        // Message 3 was deleted by its sender; page 0 now covers 4 and 2 (oldest = 2).
        .mockResolvedValueOnce(page([msg(4, 4), msg(2, 2)], false))
      const store = useMessagingStore()
      store.setActiveConversation('c1')
      await store.loadMessages('c1')
      await store.loadOlderMessages('c1')

      await store.loadMessages('c1')

      // 3 evicted; 1 is older than the refreshed window and kept.
      expect(store.messages.c1.map((m) => m.messageId)).toEqual(['1', '2', '4'])
    })

    it('reopening a conversation (reset) replaces a stale cache and resets the older-page cursor', async () => {
      fetchMessages
        .mockResolvedValueOnce(page([msg(2, 2), msg(1, 1)], true))
        .mockResolvedValueOnce(page([msg(40, 40), msg(39, 39)], false))
      const store = useMessagingStore()
      store.setActiveConversation('c1')
      await store.loadMessages('c1')
      expect(store.pagination.c1.hasMore).toBe(false)

      await store.loadMessages('c1', { reset: true })

      expect(store.messages.c1.map((m) => m.messageId)).toEqual(['39', '40'])
      expect(store.pagination.c1).toMatchObject({ nextPage: 1, hasMore: true })
    })

    it('keeps the locallyDeleted flag when the server copy of that row comes back', () => {
      const merged = mergeMessages([{ ...msg(1, 1), locallyDeleted: true }], [msg(1, 1)])
      expect(merged[0].locallyDeleted).toBe(true)
    })

    it('orders same-timestamp rows by TSID numerically, not lexically', () => {
      const merged = mergeMessages([], [msg('100', 1), msg('99', 1)])
      expect(merged.map((m) => m.messageId)).toEqual(['99', '100'])
    })
  })

  describe('postMessage', () => {
    it('appends the accepted message at the end of the thread', async () => {
      fetchMessages.mockResolvedValue(page([msg(1, 1)], true))
      sendMessage.mockResolvedValue(msg(2, 2, { moderationStatus: 'PENDING' }))
      const store = useMessagingStore()
      store.setActiveConversation('c1')
      await store.loadMessages('c1')

      await store.postMessage('c1', 'hello')

      expect(store.messages.c1.map((m) => m.messageId)).toEqual(['1', '2'])
    })

    it('rethrows a refused send (so the page can keep the draft) and closes a now-BLOCKED conversation', async () => {
      fetchConversations.mockResolvedValue([
        { conversationId: 'c1', status: 'ACTIVE', canSend: true },
      ])
      const err = {
        response: { data: { errorMsg: { errorKey: 'messaging.conversationBlocked' } } },
      }
      sendMessage.mockRejectedValue(err)
      const store = useMessagingStore()
      await store.loadConversations()

      await expect(store.postMessage('c1', 'hello')).rejects.toBe(err)

      expect(store.conversations[0]).toMatchObject({ status: 'BLOCKED', canSend: false })
    })
  })

  describe('markConversationBlocked', () => {
    it('keeps the conversation BLOCKED after the list refresh drops it (list endpoints exclude BLOCKED)', async () => {
      fetchConversations
        .mockResolvedValueOnce([{ conversationId: 'c1', status: 'ACTIVE', canSend: true }])
        .mockResolvedValueOnce([])
      const store = useMessagingStore()
      await store.loadConversations()
      store.setActiveConversation('c1')

      store.markConversationBlocked('c1')
      await store.loadConversations()

      expect(store.activeConversation).toMatchObject({ status: 'BLOCKED', canSend: false })
    })
  })

  describe('SSE', () => {
    it('MESSAGE_BLOCKED marks the sender’s message BLOCKED and clears its content', async () => {
      fetchMessages.mockResolvedValue(page([msg(5, 5, { moderationStatus: 'PENDING' })], true))
      const es = fakeEventSource()
      subscribeToEvents.mockReturnValue(es)
      const store = useMessagingStore()
      store.setActiveConversation('c1')
      await store.loadMessages('c1')
      store.connectSse('c1')
      fetchMessages.mockClear()

      await es.handlers.MESSAGE_BLOCKED({
        data: JSON.stringify({ type: 'MESSAGE_BLOCKED', messageId: 5, conversationId: 'c1' }),
      })

      expect(store.messages.c1[0]).toMatchObject({ moderationStatus: 'BLOCKED', content: null })
      expect(fetchMessages).not.toHaveBeenCalled()
      store.disconnectSse()
    })

    it('NEW_MESSAGE for a different conversation refreshes the list but not the open thread', async () => {
      fetchMessages.mockResolvedValue(page([], true))
      const es = fakeEventSource()
      subscribeToEvents.mockReturnValue(es)
      const store = useMessagingStore()
      store.setActiveConversation('c1')
      store.connectSse('c1')

      await es.handlers.NEW_MESSAGE({
        data: JSON.stringify({ type: 'NEW_MESSAGE', messageId: 7, conversationId: 'other' }),
      })

      expect(fetchMessages).not.toHaveBeenCalled()
      expect(fetchConversations).toHaveBeenCalledTimes(1)

      await es.handlers.NEW_MESSAGE({
        data: JSON.stringify({ type: 'NEW_MESSAGE', messageId: 8, conversationId: 'c1' }),
      })
      expect(fetchMessages).toHaveBeenCalledWith('c1', 0, 20)
      store.disconnectSse()
    })
  })

  describe('parent oversight', () => {
    it('reads the child’s conversations and messages through the oversight endpoints', async () => {
      fetchPlayerConversations.mockResolvedValue([{ conversationId: 'c9', parentObserver: true }])
      fetchPlayerConversationMessages.mockResolvedValue(page([msg(1, 1)], true))
      const store = useMessagingStore()
      store.messages.c9 = [msg(99, 0)] // cache from own mode — must not leak into oversight

      await store.setOversightPlayer('77')
      store.setActiveConversation('c9')
      await store.loadMessages('c9')

      expect(fetchPlayerConversations).toHaveBeenCalledWith('77')
      expect(fetchConversations).not.toHaveBeenCalled()
      expect(fetchPlayerConversationMessages).toHaveBeenCalledWith('77', 'c9', 0, 20)
      expect(store.messages.c9.map((m) => m.messageId)).toEqual(['1'])

      await store.setOversightPlayer(null)
      expect(fetchConversations).toHaveBeenCalledTimes(1)
      expect(store.activeConversationId).toBeNull()
    })

    it('a slow list response for the previous mode does not overwrite the current list', async () => {
      const slowOwn = deferred()
      fetchConversations.mockReturnValueOnce(slowOwn.promise)
      fetchPlayerConversations.mockResolvedValue([{ conversationId: 'child-conv' }])
      const store = useMessagingStore()
      const ownLoad = store.loadConversations()

      await store.setOversightPlayer('77')
      slowOwn.resolve([{ conversationId: 'own-conv' }])
      await ownLoad

      expect(store.conversations.map((c) => c.conversationId)).toEqual(['child-conv'])
    })
  })

  describe('openConversation', () => {
    it('returns the (idempotent) conversation and exposes it as active even when the list excludes it', async () => {
      initiateConversation.mockResolvedValue({
        conversationId: '42',
        status: 'BLOCKED',
        canSend: false,
      })
      const store = useMessagingStore()

      const res = await store.openConversation('coach-1', '77')
      store.setActiveConversation('42')

      expect(initiateConversation).toHaveBeenCalledWith('coach-1', '77')
      expect(res.conversationId).toBe('42')
      expect(store.activeConversation).toMatchObject({ status: 'BLOCKED', canSend: false })
    })
  })
})
