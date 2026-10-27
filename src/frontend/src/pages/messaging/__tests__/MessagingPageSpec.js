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
  sendMessage: vi.fn(),
  initiateConversation: vi.fn(),
  subscribeToEvents: vi.fn(),
  softDeleteMessage: vi.fn(),
  reportMessage: vi.fn(),
  reportConversation: vi.fn(),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { softDeleteMessage, reportMessage, reportConversation } from 'src/api/messaging.api'
import MessagingPage from 'src/pages/messaging/MessagingPage.vue'

const MY_MESSAGE = { messageId: 1, senderId: 'user-1', content: 'Hi there', senderRole: 'PARENT' }
const OTHER_MESSAGE = { messageId: 2, senderId: 'user-2', content: 'Hello', senderRole: 'COACH' }

const mounted = []
// Fresh copies per mount: a test mutating a message (e.g. locallyDeleted) must not leak into the next.
function mountPage(messages = [MY_MESSAGE, OTHER_MESSAGE]) {
  messages = structuredClone(messages)
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      auth: { userId: 'user-1', role: 'PARENT' },
      messaging: {
        conversations: [{ conversationId: 'conv-1', otherPartyName: 'Coach A', unreadCount: 0 }],
        messages: { 'conv-1': messages },
        activeConversationId: 'conv-1',
      },
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
