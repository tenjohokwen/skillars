// feat/admin-moderation-and-enforcement A1: AdminModerationPage.vue — summary counts, typed alert
// queue (per-type actions), held-message approve/block and reported-conversation unblock.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    getModerationQueue: vi.fn(),
    getModerationSummary: vi.fn(),
    getMessageDetail: vi.fn(),
    approveMessage: vi.fn(),
    blockMessage: vi.fn(),
    getConversationDetail: vi.fn(),
    unblockConversation: vi.fn(),
    getReviewQueue: vi.fn(),
    resolveSafetyHoldAlert: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminModerationPage from 'src/pages/admin/AdminModerationPage.vue'

const SUMMARY = {
  messageReports: 2,
  conversationReports: 1,
  reviewFlags: 0,
  strikeAlerts: 1,
  disputes: 1,
  moderationHolds: 0,
  gdprErasureDeadlines: 0,
  subscriptionOrphaned: 0,
  safetyHoldBookings: 0,
  total: 5,
}

const ALERTS = [
  {
    alertId: 'a1',
    type: 'MESSAGE_REPORT',
    referenceId: '11',
    referenceType: 'MESSAGE',
    status: 'OPEN',
    createdAt: '2026-01-01T10:00:00Z',
    summary: 'rude text',
  },
  {
    alertId: 'a2',
    type: 'CONVERSATION_REPORT',
    referenceId: '22',
    referenceType: 'CONVERSATION',
    status: 'OPEN',
    createdAt: '2026-01-01T10:00:00Z',
    summary: 'HARASSMENT',
  },
  {
    alertId: 'a3',
    type: 'DISPUTE_RAISED',
    referenceId: 'b1',
    referenceType: 'BOOKING',
    status: 'OPEN',
    createdAt: '2026-01-01T10:00:00Z',
    summary: '',
  },
  {
    alertId: 'a4',
    type: 'STRIKE_THRESHOLD',
    referenceId: 'c1',
    referenceType: 'COACH',
    status: 'OPEN',
    createdAt: '2026-01-01T10:00:00Z',
    summary: '',
  },
  {
    alertId: 'a5',
    type: 'REVIEW_FLAG',
    referenceId: 'r1',
    referenceType: 'REVIEW',
    status: 'OPEN',
    createdAt: '2026-01-01T10:00:00Z',
    summary: '2 flags',
  },
]

const MESSAGE = {
  messageId: 11,
  conversationId: 22,
  senderId: 5,
  senderRole: 'COACH',
  content: 'rude text',
  moderationStatus: 'UNDER_REVIEW',
  createdAt: '2026-01-01T09:00:00Z',
  contextBefore: [],
  contextAfter: [],
  reports: [{ reason: 'ABUSE', details: 'not ok', createdAt: '2026-01-01T09:30:00Z' }],
}

const CONVERSATION = {
  conversationId: 22,
  status: 'BLOCKED',
  messages: [],
  reports: [{ reason: 'HARASSMENT', details: null, createdAt: '2026-01-01T09:30:00Z' }],
}

const mounted = []
async function mountPage() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }],
  })
  router.push('/admin/moderation')
  await router.isReady()
  const wrapper = mount(AdminModerationPage, {
    global: {
      plugins: [router],
      stubs: { QDialog: { template: '<div><slot /></div>' }, ReviewModerationPanel: true },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminModerationPage.vue', () => {
  beforeEach(() => {
    adminApi.getModerationSummary.mockResolvedValue(SUMMARY)
    adminApi.getModerationQueue.mockResolvedValue({ content: ALERTS, totalPages: 1 })
    adminApi.getMessageDetail.mockResolvedValue(MESSAGE)
    adminApi.getConversationDetail.mockResolvedValue(CONVERSATION)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('loads the summary counts and the open queue on mount', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getModerationSummary).toHaveBeenCalled()
    expect(adminApi.getModerationQueue).toHaveBeenCalledWith('ALL', 0)
    expect(wrapper.find('[data-testid="moderation-summary-total"]').text()).toContain('5')
    expect(wrapper.find('[data-testid="moderation-summary-messageReports"]').text()).toContain('2')
    expect(wrapper.vm.rows).toHaveLength(5)
  })

  it('renders a type-appropriate action for each alert type', async () => {
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="alert-open-message-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="alert-open-conversation-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="alert-open-disputes-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="alert-open-coach-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="alert-open-reviews-btn"]').exists()).toBe(true)
  })

  it('the disputes action links to the disputes page and the coach action to its profile', async () => {
    const wrapper = await mountPage()

    expect(wrapper.find('[data-testid="alert-open-disputes-btn"]').attributes('href')).toContain(
      '/admin/disputes',
    )
    expect(wrapper.find('[data-testid="alert-open-coach-btn"]').attributes('href')).toContain(
      '/admin/coaches?coachId=c1',
    )
  })

  it('an urgent safety-hold booking alert can be marked handled (C2)', async () => {
    const safetyAlert = {
      alertId: 'a6',
      type: 'SAFETY_HOLD_BOOKING',
      referenceId: 'b6',
      referenceType: 'BOOKING',
      status: 'OPEN',
      createdAt: '2026-01-01T10:00:00Z',
      summary: 'URGENT — contact the parent: p@example.com',
    }
    adminApi.getModerationQueue.mockResolvedValue({ content: [safetyAlert], totalPages: 1 })
    adminApi.resolveSafetyHoldAlert.mockResolvedValue(undefined)
    const wrapper = await mountPage()

    const btn = wrapper.find('[data-testid="alert-resolve-safety-hold-btn"]')
    expect(btn.exists()).toBe(true)
    await wrapper.vm.resolveSafetyHoldAlert('a6')
    await flushPromises()

    expect(adminApi.resolveSafetyHoldAlert).toHaveBeenCalledWith('a6')
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
    // Queue and summary are reloaded so the resolved alert drops out of both.
    expect(adminApi.getModerationQueue).toHaveBeenCalledTimes(2)
    expect(adminApi.getModerationSummary).toHaveBeenCalledTimes(2)
  })

  it('a review-flag alert switches to the reviews tab', async () => {
    const wrapper = await mountPage()

    await wrapper.find('[data-testid="alert-open-reviews-btn"]').trigger('click')

    expect(wrapper.vm.tab).toBe('reviews')
  })

  it('changing the type filter reloads the queue from page 0 with that type', async () => {
    const wrapper = await mountPage()

    wrapper.vm.typeFilter = 'DISPUTE_RAISED'
    await wrapper.vm.onTypeFilterChange()

    expect(adminApi.getModerationQueue).toHaveBeenLastCalledWith('DISPUTE_RAISED', 0)
  })

  it('opens a held message and approves it, refreshing queue and summary', async () => {
    adminApi.approveMessage.mockResolvedValue(undefined)
    const wrapper = await mountPage()

    await wrapper.vm.openMessage('11')
    await flushPromises()
    expect(adminApi.getMessageDetail).toHaveBeenCalledWith('11')
    expect(wrapper.find('[data-testid="message-detail-content"]').text()).toContain('rude text')

    await wrapper.vm.approveMessage()
    await flushPromises()

    expect(adminApi.approveMessage).toHaveBeenCalledWith(11)
    expect(wrapper.vm.messageDialogOpen).toBe(false)
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
    expect(adminApi.getModerationQueue).toHaveBeenCalledTimes(2)
    expect(adminApi.getModerationSummary).toHaveBeenCalledTimes(2)
  })

  it('blocks a held message with the trimmed reason', async () => {
    adminApi.blockMessage.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openMessage('11')
    await flushPromises()

    wrapper.vm.openBlockMessageDialog()
    wrapper.vm.blockReason = '  Abusive  '
    await wrapper.vm.blockMessage()

    expect(adminApi.blockMessage).toHaveBeenCalledWith(11, 'Abusive')
    expect(wrapper.vm.blockDialogOpen).toBe(false)
  })

  it('does not offer approve/block for a message that is no longer under review', async () => {
    adminApi.getMessageDetail.mockResolvedValue({ ...MESSAGE, moderationStatus: 'APPROVED' })
    const wrapper = await mountPage()
    await wrapper.vm.openMessage('11')
    await flushPromises()

    expect(wrapper.find('[data-testid="message-approve-btn"]').exists()).toBe(false)
  })

  it('opens a reported conversation and unblocks it', async () => {
    adminApi.unblockConversation.mockResolvedValue(undefined)
    const wrapper = await mountPage()

    await wrapper.vm.openConversation('22')
    await flushPromises()
    expect(wrapper.find('[data-testid="conversation-unblock-btn"]').exists()).toBe(true)

    await wrapper.vm.unblockConversation()

    expect(adminApi.unblockConversation).toHaveBeenCalledWith(22)
    expect(wrapper.vm.conversationDialogOpen).toBe(false)
  })

  it('a failed action shows an error and keeps the dialog open', async () => {
    adminApi.approveMessage.mockRejectedValue(new Error('boom'))
    const wrapper = await mountPage()
    await wrapper.vm.openMessage('11')
    await flushPromises()

    await wrapper.vm.approveMessage()

    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'negative' }))
    expect(wrapper.vm.messageDialogOpen).toBe(true)
  })

  it('a failed queue load shows the error banner', async () => {
    adminApi.getModerationQueue.mockRejectedValue(new Error('network'))
    const wrapper = await mountPage()

    expect(wrapper.vm.loadError).toBe(true)
  })
})
