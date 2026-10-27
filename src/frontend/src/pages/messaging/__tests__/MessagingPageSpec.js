// skillars-deferred-165 AC6: MessagingPage.vue — delete visibility (own messages only), delete
// happy path, message report happy path, conversation report happy path.
//
// Also pins the AC6 fix: myUserId used to read `authStore.user?.id`, a property that never existed
// on auth.store.js (only a flat `userId` ref) — so `msg.senderId === myUserId` never matched and
// the Delete action could never appear for anyone. Mutation check: revert `myUserId` to
// `authStore.user?.id` -> the "own message" visibility test goes RED.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

vi.mock('src/api/messaging.api', () => ({
  fetchConversations: vi.fn(),
  fetchMessages: vi.fn(),
  fetchPlayerConversations: vi.fn(),
  fetchPlayerConversationMessages: vi.fn(),
  sendMessage: vi.fn(),
  initiateConversation: vi.fn(),
  subscribeToEvents: vi.fn(),
  softDeleteMessage: vi.fn(),
  reportMessage: vi.fn(),
  reportConversation: vi.fn(),
}))

// feat/messaging-completion: the page reads `?conversation=<id>` and keeps it in sync.
const { routeState, routerMock } = vi.hoisted(() => ({
  routeState: { query: {} },
  routerMock: { replace: vi.fn(), push: vi.fn() },
}))
vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => routerMock,
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { softDeleteMessage, reportMessage, reportConversation } from 'src/api/messaging.api'
import MessagingPage from 'src/pages/messaging/MessagingPage.vue'
import { useMessagingStore } from 'src/stores/messaging.store'

const MY_MESSAGE = { messageId: 1, senderId: 'user-1', content: 'Hi there', senderRole: 'PARENT' }
const OTHER_MESSAGE = { messageId: 2, senderId: 'user-2', content: 'Hello', senderRole: 'COACH' }

const mounted = []
// Fresh copies per mount: a test mutating a message (e.g. locallyDeleted) must not leak into the next.
function mountPage(messages = [MY_MESSAGE, OTHER_MESSAGE], { messaging = {}, extra = {} } = {}) {
  messages = structuredClone(messages)
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { userId: 'user-1', role: 'PARENT' },
      messaging: {
        conversations: [{ conversationId: 'conv-1', otherPartyName: 'Coach A', unreadCount: 0 }],
        messages: { 'conv-1': messages },
        activeConversationId: 'conv-1',
        ...messaging,
      },
      ...extra,
    },
  })
  const wrapper = mount(MessagingPage, {
    shallow: true,
    global: { plugins: [pinia], renderStubDefaultSlot: true },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('MessagingPage.vue (skillars-deferred-165 AC6)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it("shows the delete action only on the viewer's own message", () => {
    const wrapper = mountPage()

    const myKebab = wrapper.find('[data-testid="message-kebab-1"]')
    const otherKebab = wrapper.find('[data-testid="message-kebab-2"]')

    expect(myKebab.find('[data-testid="delete-message-action"]').exists()).toBe(true)
    expect(otherKebab.find('[data-testid="delete-message-action"]').exists()).toBe(false)
    // Report is available on every message, including the viewer's own.
    expect(myKebab.find('[data-testid="report-message-action"]').exists()).toBe(true)
    expect(otherKebab.find('[data-testid="report-message-action"]').exists()).toBe(true)
  })

  it('delete happy path: calls softDeleteMessage and locally marks the message deleted', async () => {
    softDeleteMessage.mockResolvedValue()
    const wrapper = mountPage()

    // The store's own message object (what the template renders), not the module fixture.
    await wrapper.vm.handleDeleteMessage(wrapper.vm.currentMessages[0])
    await flushPromises()

    expect(softDeleteMessage).toHaveBeenCalledWith('conv-1', 1)
    expect(wrapper.find('[data-testid="message-bubble-1"]').text()).toContain(
      'messaging.messageDeleted',
    )
    expect(wrapper.find('[data-testid="message-kebab-1"]').exists()).toBe(false)
    expect(MY_MESSAGE.locallyDeleted).toBeUndefined()
  })

  it.each(['UNDER_REVIEW', 'BLOCKED'])(
    'does not offer Delete on an own message whose moderation status is %s',
    async (moderationStatus) => {
      const wrapper = mountPage([{ ...MY_MESSAGE, moderationStatus }])
      await flushPromises()

      const myKebab = wrapper.find('[data-testid="message-kebab-1"]')
      expect(myKebab.find('[data-testid="delete-message-action"]').exists()).toBe(false)
      expect(myKebab.find('[data-testid="report-message-action"]').exists()).toBe(true)
    },
  )

  it('a moderation-pending delete refusal shows the mapped error', async () => {
    softDeleteMessage.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'messaging.moderationPending' } } },
    })
    const wrapper = mountPage()

    await wrapper.vm.handleDeleteMessage(wrapper.vm.currentMessages[0])

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'messaging.deleteModerationPending' }),
    )
  })

  it('message report happy path: calls reportMessage with the selected reason', async () => {
    reportMessage.mockResolvedValue({ reportId: 'r1' })
    const wrapper = mountPage()

    wrapper.vm.openReportDialog('message', 2)
    wrapper.vm.reportReason = 'SPAM'
    wrapper.vm.reportDetails = 'Unwanted solicitation'
    await wrapper.vm.submitReport()
    await flushPromises()

    expect(reportMessage).toHaveBeenCalledWith('conv-1', 2, {
      reason: 'SPAM',
      details: 'Unwanted solicitation',
    })
    expect(wrapper.vm.reportDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('conversation report happy path: calls reportConversation with the selected reason', async () => {
    reportConversation.mockResolvedValue({ reportId: 'r2' })
    const wrapper = mountPage()

    wrapper.vm.openReportDialog('conversation', 'conv-1')
    wrapper.vm.reportReason = 'HARASSMENT'
    wrapper.vm.reportDetails = ''
    await wrapper.vm.submitReport()
    await flushPromises()

    expect(reportConversation).toHaveBeenCalledWith('conv-1', { reason: 'HARASSMENT', details: '' })
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('an already-reported 403 shows the mapped error', async () => {
    reportMessage.mockRejectedValue({
      response: { data: { errorMsg: { errorKey: 'messaging.alreadyReported' } } },
    })
    const wrapper = mountPage()

    wrapper.vm.openReportDialog('message', 2)
    wrapper.vm.reportReason = 'OTHER'
    await wrapper.vm.submitReport()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'messaging.alreadyReported' }),
    )
  })
})

describe('MessagingPage.vue — messaging completion (feat/messaging-completion)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
    routeState.query = {}
  })

  describe('send errors', () => {
    it.each([
      [
        'messaging.playerDirectMessagingRestricted',
        'messaging.errors.playerDirectMessagingRestricted',
      ],
      [
        'messaging.parentMessagingRestrictedForAdult',
        'messaging.errors.parentMessagingRestrictedForAdult',
      ],
      ['messaging.conversationBlocked', 'messaging.errors.conversationBlocked'],
      ['messaging.invalidContent', 'messaging.errors.invalidContent'],
      ['messaging.noBookingRelationship', 'messaging.errors.noBookingRelationship'],
      ['something.unknown', 'messaging.errors.generic'],
    ])('%s -> notifies %s and keeps the draft', async (code, i18nKey) => {
      const wrapper = mountPage()
      await flushPromises() // let the mount-time re-open of conv-1 settle before typing a draft
      const store = useMessagingStore()
      store.postMessage.mockRejectedValue({ response: { data: { errorMsg: { errorKey: code } } } })

      wrapper.vm.newMessage = 'my draft'
      await wrapper.vm.submitMessage()

      expect(store.postMessage).toHaveBeenCalledWith('conv-1', 'my draft')
      expect(notifySpy).toHaveBeenCalledWith(
        expect.objectContaining({ type: 'negative', message: i18nKey }),
      )
      expect(wrapper.vm.newMessage).toBe('my draft')
    })

    it('a network failure (no response) also keeps the draft with the generic message', async () => {
      const wrapper = mountPage()
      await flushPromises() // let the mount-time re-open of conv-1 settle before typing a draft
      const store = useMessagingStore()
      store.postMessage.mockRejectedValue(new Error('Network Error'))

      wrapper.vm.newMessage = 'draft'
      await wrapper.vm.submitMessage()

      expect(notifySpy).toHaveBeenCalledWith(
        expect.objectContaining({ message: 'messaging.errors.generic' }),
      )
      expect(wrapper.vm.newMessage).toBe('draft')
    })

    it('clears the draft on success', async () => {
      const wrapper = mountPage()
      await flushPromises() // let the mount-time re-open of conv-1 settle before typing a draft
      const store = useMessagingStore()
      store.postMessage.mockResolvedValue({ messageId: 3 })

      wrapper.vm.newMessage = 'sent'
      await wrapper.vm.submitMessage()

      expect(wrapper.vm.newMessage).toBe('')
      expect(notifySpy).not.toHaveBeenCalled()
    })
  })

  describe('moderation indicators', () => {
    it.each([
      ['own UNDER_REVIEW', 'user-1', 'UNDER_REVIEW', 'messaging.moderation.pendingReviewOwn'],
      ["other's UNDER_REVIEW", 'user-2', 'UNDER_REVIEW', 'messaging.moderation.underReview'],
      ['own BLOCKED', 'user-1', 'BLOCKED', 'messaging.moderation.blockedOwn'],
      ["other's BLOCKED", 'user-2', 'BLOCKED', 'messaging.moderation.blocked'],
    ])('%s shows %s instead of [Content removed]', (_label, senderId, moderationStatus, key) => {
      const wrapper = mountPage([{ messageId: 9, senderId, content: null, moderationStatus }])

      const notice = wrapper.find('[data-testid="moderation-notice-9"]')
      expect(notice.exists()).toBe(true)
      expect(notice.text()).toContain(key)
      expect(wrapper.find('[data-testid="message-bubble-9"]').text()).not.toContain(
        'messaging.contentRemoved',
      )
    })

    it('an APPROVED message renders its content with no notice', () => {
      const wrapper = mountPage([{ ...MY_MESSAGE, moderationStatus: 'APPROVED' }])
      expect(wrapper.find('[data-testid="moderation-notice-1"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="message-bubble-1"]').text()).toContain('Hi there')
    })

    it('matches "mine" across a numeric auth id and a string senderId', () => {
      const wrapper = mountPage(
        [{ messageId: 9, senderId: '42', content: null, moderationStatus: 'UNDER_REVIEW' }],
        { extra: { auth: { userId: 42, role: 'PARENT' } } },
      )
      expect(wrapper.find('[data-testid="moderation-notice-9"]').text()).toContain(
        'messaging.moderation.pendingReviewOwn',
      )
    })
  })

  describe('composer gating', () => {
    it('a BLOCKED conversation shows the blocked banner instead of the composer', () => {
      const wrapper = mountPage(undefined, {
        messaging: {
          conversations: [{ conversationId: 'conv-1', status: 'BLOCKED', canSend: false }],
        },
      })
      expect(wrapper.find('[data-testid="composer-locked-banner"]').text()).toContain(
        'messaging.blockedBanner',
      )
      expect(wrapper.find('[data-testid="message-input"]').exists()).toBe(false)
    })

    it('an age-tier send restriction shows the cannot-send banner', () => {
      const wrapper = mountPage(undefined, {
        messaging: {
          conversations: [{ conversationId: 'conv-1', status: 'ACTIVE', canSend: false }],
        },
      })
      expect(wrapper.find('[data-testid="composer-locked-banner"]').text()).toContain(
        'messaging.cannotSend',
      )
    })

    it('a sendable conversation shows the composer', () => {
      const wrapper = mountPage(undefined, {
        messaging: {
          conversations: [{ conversationId: 'conv-1', status: 'ACTIVE', canSend: true }],
        },
      })
      expect(wrapper.find('[data-testid="message-input"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="composer-locked-banner"]').exists()).toBe(false)
    })

    it('reporting a conversation marks it blocked locally (report is the block action, story 8.4)', async () => {
      reportConversation.mockResolvedValue({ reportId: 'r' })
      const wrapper = mountPage()
      const store = useMessagingStore()

      wrapper.vm.openReportDialog('conversation', 'conv-1')
      wrapper.vm.reportReason = 'HARASSMENT'
      await wrapper.vm.submitReport()

      expect(store.markConversationBlocked).toHaveBeenCalledWith('conv-1')
    })
  })

  describe('older history', () => {
    it('shows Load older only while the server reports more pages, and loads the next page', async () => {
      const wrapper = mountPage(undefined, {
        messaging: {
          pagination: { 'conv-1': { nextPage: 1, hasMore: true, loadingOlder: false } },
        },
      })
      const store = useMessagingStore()
      store.loadOlderMessages.mockResolvedValue(true)

      const btn = wrapper.find('[data-testid="load-older-btn"]')
      expect(btn.exists()).toBe(true)
      await wrapper.vm.loadOlder()

      expect(store.loadOlderMessages).toHaveBeenCalledWith('conv-1')
    })

    it('hides Load older when the history is fully loaded', () => {
      const wrapper = mountPage(undefined, {
        messaging: {
          pagination: { 'conv-1': { nextPage: 2, hasMore: false, loadingOlder: false } },
        },
      })
      expect(wrapper.find('[data-testid="load-older-btn"]').exists()).toBe(false)
    })
  })

  describe('deep link', () => {
    it('?conversation=<id> selects that conversation on mount', async () => {
      routeState.query = { conversation: 'conv-7' }
      mountPage(undefined, { messaging: { activeConversationId: null } })
      const store = useMessagingStore()
      store.setActiveConversation.mockImplementation((id) => (store.activeConversationId = id))
      await flushPromises()

      expect(store.setActiveConversation).toHaveBeenCalledWith('conv-7')
      expect(store.loadMessages).toHaveBeenCalledWith('conv-7', { reset: true })
      expect(store.connectSse).toHaveBeenCalledWith('conv-7')
    })
  })

  describe('remount', () => {
    it('re-fetches and re-subscribes a thread still selected from an earlier visit', async () => {
      mountPage()
      const store = useMessagingStore()
      store.setActiveConversation.mockImplementation((id) => (store.activeConversationId = id))
      await flushPromises()

      expect(store.loadMessages).toHaveBeenCalledWith('conv-1', { reset: true })
      expect(store.connectSse).toHaveBeenCalledWith('conv-1')
    })
  })

  describe('switching conversations', () => {
    it('a selection superseded while its messages were loading does not take over the SSE stream', async () => {
      const wrapper = mountPage()
      const store = useMessagingStore()
      await flushPromises()
      store.connectSse.mockClear()
      store.setActiveConversation.mockImplementation((id) => (store.activeConversationId = id))
      let releaseFirst
      store.loadMessages
        .mockImplementationOnce(() => new Promise((r) => (releaseFirst = r)))
        .mockResolvedValueOnce()

      const first = wrapper.vm.selectConversation('conv-a')
      await wrapper.vm.selectConversation('conv-b')
      releaseFirst()
      await first

      expect(store.connectSse).toHaveBeenCalledTimes(1)
      expect(store.connectSse).toHaveBeenCalledWith('conv-b')
    })
  })

  describe('parent oversight', () => {
    const CHILDREN = [
      { id: '77', name: 'Kid Teen', ageTier: 'AGE_13_17' },
      { id: '78', name: 'Grown Up', ageTier: 'ADULT' },
    ]

    it('offers a child selector listing only non-adult children', () => {
      const wrapper = mountPage(undefined, { extra: { player: { players: CHILDREN } } })
      const select = wrapper.findComponent('[data-testid="oversight-child-select"]')
      expect(select.exists()).toBe(true)
      const values = select.props('options').map((o) => o.value)
      expect(values).toContain('77')
      expect(values).not.toContain('78')
    })

    it('no selector for a coach', () => {
      const wrapper = mountPage(undefined, {
        extra: { auth: { userId: 'user-1', role: 'COACH' }, player: { players: CHILDREN } },
      })
      expect(wrapper.find('[data-testid="oversight-child-select"]').exists()).toBe(false)
    })

    it('selecting a child switches the store to that child; "my conversations" switches back', async () => {
      const wrapper = mountPage(undefined, { extra: { player: { players: CHILDREN } } })
      const store = useMessagingStore()

      await wrapper.vm.changeOversightPlayer('77')
      expect(store.setOversightPlayer).toHaveBeenLastCalledWith('77')

      await wrapper.vm.changeOversightPlayer('__own__')
      expect(store.setOversightPlayer).toHaveBeenLastCalledWith(null)
    })

    it('oversight mode is read-only: banner, no composer, reply hand-off when the parent may send', async () => {
      const wrapper = mountPage(undefined, {
        messaging: {
          oversightPlayerId: '77',
          conversations: [
            { conversationId: 'conv-1', status: 'ACTIVE', canSend: true, parentObserver: true },
          ],
        },
        extra: { player: { players: CHILDREN } },
      })
      const store = useMessagingStore()

      expect(wrapper.find('[data-testid="oversight-banner"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="message-input"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="oversight-role-chip"]').attributes('label')).toBe(
        'messaging.oversight.observerChip',
      )

      await wrapper.vm.replyFromOversight()
      expect(store.setOversightPlayer).toHaveBeenCalledWith(null)
      expect(store.setActiveConversation).toHaveBeenCalledWith('conv-1')
    })

    it('no reply hand-off for a BLOCKED conversation in oversight', () => {
      const wrapper = mountPage(undefined, {
        messaging: {
          oversightPlayerId: '77',
          conversations: [{ conversationId: 'conv-1', status: 'BLOCKED', canSend: false }],
        },
        extra: { player: { players: CHILDREN } },
      })
      expect(wrapper.find('[data-testid="oversight-reply-btn"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="conversation-blocked-chip"]').exists()).toBe(true)
    })
  })
})
