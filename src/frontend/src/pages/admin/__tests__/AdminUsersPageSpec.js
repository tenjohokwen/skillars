// feat/admin-moderation-and-enforcement D7 / A10: AdminUsersPage.vue — user search, account
// detail, suspend / reactivate (reason required), login unlock, and the backend refusal mapping.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({
  adminApi: {
    searchUsers: vi.fn(),
    getUser: vi.fn(),
    suspendUser: vi.fn(),
    reactivateUser: vi.fn(),
    unlockUserLogin: vi.fn(),
  },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminUsersPage from 'src/pages/admin/AdminUsersPage.vue'

const ROW = {
  userId: '9171000010',
  email: 'paula@example.com',
  firstName: 'Paula',
  lastName: 'Parent',
  role: 'PARENT',
  activated: true,
  suspended: false,
  verificationStatus: 'BASIC_VERIFIED',
  createdAt: '2026-01-01T10:00:00Z',
}

const DETAIL = {
  ...ROW,
  login: 'paula@example.com',
  privileged: false,
  recentFailedLoginAttempts: 4,
  history: [],
}

const mounted = []
async function mountPage() {
  const wrapper = mount(AdminUsersPage, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminUsersPage.vue', () => {
  beforeEach(() => {
    adminApi.searchUsers.mockResolvedValue({ content: [ROW], totalPages: 1 })
    adminApi.getUser.mockResolvedValue(DETAIL)
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('lists users on mount with an empty query', async () => {
    const wrapper = await mountPage()

    expect(adminApi.searchUsers).toHaveBeenCalledWith('', 0)
    expect(wrapper.vm.rows).toEqual([ROW])
  })

  it('searches with the trimmed query from page 0', async () => {
    const wrapper = await mountPage()

    wrapper.vm.query = '  paula '
    await wrapper.vm.search()

    expect(adminApi.searchUsers).toHaveBeenLastCalledWith('paula', 0)
  })

  it('opens the account detail including the failed-login count', async () => {
    const wrapper = await mountPage()

    await wrapper.vm.openDetail('9171000010')

    expect(adminApi.getUser).toHaveBeenCalledWith('9171000010')
    expect(wrapper.find('[data-testid="user-failed-logins"]').text()).toContain('4')
    expect(wrapper.find('[data-testid="user-suspend-open-btn"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="user-reactivate-open-btn"]').exists()).toBe(false)
  })

  it('offers no account action for a privileged (admin) account', async () => {
    adminApi.getUser.mockResolvedValue({
      ...DETAIL,
      role: 'ADMIN',
      privileged: true,
      suspended: true,
    })
    const wrapper = await mountPage()

    await wrapper.vm.openDetail('1')

    expect(wrapper.find('[data-testid="user-suspend-open-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="user-reactivate-open-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="user-unlock-open-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="user-privileged-banner"]').exists()).toBe(true)
  })

  it('suspend sends the trimmed reason and refreshes detail and list', async () => {
    adminApi.suspendUser.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    wrapper.vm.openAction('suspend')
    wrapper.vm.actionReason = '  Abusive messages '
    await wrapper.vm.submitAction()
    await flushPromises()

    expect(adminApi.suspendUser).toHaveBeenCalledWith('9171000010', 'Abusive messages', true)
    expect(wrapper.vm.actionDialogOpen).toBe(false)
    expect(adminApi.getUser).toHaveBeenCalledTimes(2)
    expect(adminApi.searchUsers).toHaveBeenCalledTimes(2)
  })

  it('suspending a coach offers the cancel-upcoming-sessions opt-out (C2)', async () => {
    adminApi.getUser.mockResolvedValue({ ...DETAIL, role: 'COACH' })
    adminApi.suspendUser.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    wrapper.vm.openAction('suspend')
    await flushPromises()
    expect(wrapper.find('[data-testid="user-cancel-upcoming-checkbox"]').exists()).toBe(true)
    wrapper.vm.actionReason = 'Policy'
    wrapper.vm.cancelUpcomingBookings = false
    await wrapper.vm.submitAction()

    expect(adminApi.suspendUser).toHaveBeenCalledWith('9171000010', 'Policy', false)
  })

  it('suspending a non-coach shows no session-cancellation option', async () => {
    adminApi.getUser.mockResolvedValue({ ...DETAIL, role: 'PARENT' })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    wrapper.vm.openAction('suspend')
    await flushPromises()
    expect(wrapper.find('[data-testid="user-cancel-upcoming-checkbox"]').exists()).toBe(false)
  })

  it('a suspended account offers reactivate, which sends the reason', async () => {
    adminApi.getUser.mockResolvedValue({ ...DETAIL, suspended: true })
    adminApi.reactivateUser.mockResolvedValue(undefined)
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    expect(wrapper.find('[data-testid="user-reactivate-open-btn"]').exists()).toBe(true)
    wrapper.vm.openAction('reactivate')
    wrapper.vm.actionReason = 'Appeal accepted'
    await wrapper.vm.submitAction()

    expect(adminApi.reactivateUser).toHaveBeenCalledWith('9171000010', 'Appeal accepted')
  })

  it('unlock login needs no reason and reports the cleared count', async () => {
    adminApi.unlockUserLogin.mockResolvedValue({ clearedAttempts: 4 })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    wrapper.vm.openAction('unlock')
    await wrapper.vm.submitAction()

    expect(adminApi.unlockUserLogin).toHaveBeenCalledWith('9171000010')
    expect(notifySpy).toHaveBeenCalledWith(expect.objectContaining({ type: 'positive' }))
  })

  it('maps the backend admin-target refusal to its own message', async () => {
    adminApi.suspendUser.mockRejectedValue({
      response: {
        status: 409,
        data: {
          errorMsg: { errorKey: 'generic.requestError', message: 'admin.users.cannotSuspendAdmin' },
        },
      },
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    wrapper.vm.openAction('suspend')
    wrapper.vm.actionReason = 'x'
    await wrapper.vm.submitAction()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'admin.users.cannotSuspendAdmin' }),
    )
    expect(wrapper.vm.actionDialogOpen).toBe(true)
  })

  it('maps the GDPR-erased reactivation refusal to its own message', async () => {
    adminApi.getUser.mockResolvedValue({ ...DETAIL, suspended: true })
    adminApi.reactivateUser.mockRejectedValue({
      response: { status: 409, data: { errorMsg: { message: 'admin.users.erased' } } },
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    wrapper.vm.openAction('reactivate')
    wrapper.vm.actionReason = 'undo'
    await wrapper.vm.submitAction()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'admin.users.erased' }),
    )
  })

  it('maps the child-safety-hold reactivation refusal to its own message', async () => {
    adminApi.getUser.mockResolvedValue({ ...DETAIL, suspended: true })
    adminApi.reactivateUser.mockRejectedValue({
      response: { status: 409, data: { errorMsg: { message: 'admin.users.safetyHold' } } },
    })
    const wrapper = await mountPage()
    await wrapper.vm.openDetail('9171000010')

    wrapper.vm.openAction('reactivate')
    wrapper.vm.actionReason = 'undo'
    await wrapper.vm.submitAction()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'admin.users.safetyHold' }),
    )
  })

  it('a failed search shows the error banner', async () => {
    adminApi.searchUsers.mockRejectedValue(new Error('network'))
    const wrapper = await mountPage()

    expect(wrapper.vm.loadError).toBe(true)
  })
})
