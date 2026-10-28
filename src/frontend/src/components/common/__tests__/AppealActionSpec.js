// C6 D8: AppealAction.vue + appeal.store.js — one appeal per enforcement action, 14-day window,
// status chip once an appeal exists.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('src/api/appeals.api', () => ({
  listMyAppeals: vi.fn(),
  submitAppeal: vi.fn(),
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { listMyAppeals, submitAppeal } from 'src/api/appeals.api'
import { useAppealStore } from 'src/stores/appeal.store'
import AppealAction from 'src/components/common/AppealAction.vue'

const DAY = 24 * 60 * 60 * 1000
const mounted = []
let pinia

async function mountAction(props) {
  const wrapper = mount(AppealAction, {
    props: { subjectType: 'RELIABILITY_STRIKE', subjectId: 's1', ...props },
    global: { plugins: [pinia], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('appeal.store.js', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
  })
  afterEach(() => vi.clearAllMocks())

  it('dedupes concurrent loads into one request', async () => {
    listMyAppeals.mockResolvedValue([])
    const store = useAppealStore()
    await Promise.all([store.load(), store.load(), store.load()])
    expect(listMyAppeals).toHaveBeenCalledTimes(1)
    await store.load()
    expect(listMyAppeals).toHaveBeenCalledTimes(1)
    await store.load({ force: true })
    expect(listMyAppeals).toHaveBeenCalledTimes(2)
  })

  it('findFor matches by type and id; submit prepends the created appeal', async () => {
    listMyAppeals.mockResolvedValue([
      { appealId: 'a1', subjectType: 'REVIEW_BLOCK', subjectId: 's1', status: 'UPHELD' },
    ])
    const store = useAppealStore()
    await store.load()
    expect(store.findFor('RELIABILITY_STRIKE', 's1')).toBeNull()
    expect(store.findFor('REVIEW_BLOCK', 's1').appealId).toBe('a1')

    submitAppeal.mockResolvedValue({
      appealId: 'a2',
      subjectType: 'RELIABILITY_STRIKE',
      subjectId: 's1',
      status: 'OPEN',
    })
    await store.submit({ subjectType: 'RELIABILITY_STRIKE', subjectId: 's1', reason: 'x' })
    expect(store.findFor('RELIABILITY_STRIKE', 's1').appealId).toBe('a2')
  })
})

describe('AppealAction.vue', () => {
  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    listMyAppeals.mockResolvedValue([])
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('offers the appeal button inside the 14-day window', async () => {
    const wrapper = await mountAction({ enforcedAt: new Date(Date.now() - 2 * DAY).toISOString() })
    expect(wrapper.find('[data-testid="appeal-open-btn"]').exists()).toBe(true)
  })

  it('shows nothing once the window has closed', async () => {
    const wrapper = await mountAction({ enforcedAt: new Date(Date.now() - 15 * DAY).toISOString() })
    expect(wrapper.find('[data-testid="appeal-open-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="appeal-status-chip"]').exists()).toBe(false)
  })

  it('shows the status chip instead of the button when an appeal exists', async () => {
    listMyAppeals.mockResolvedValue([
      {
        appealId: 'a1',
        subjectType: 'RELIABILITY_STRIKE',
        subjectId: 's1',
        status: 'OVERTURNED',
        decisionNote: 'Strike removed',
      },
    ])
    const wrapper = await mountAction()
    expect(wrapper.find('[data-testid="appeal-status-chip"]').text()).toContain(
      'appeals.status.OVERTURNED',
    )
    expect(wrapper.find('[data-testid="appeal-open-btn"]').exists()).toBe(false)
  })

  it('submits the trimmed reason and flips to the pending chip', async () => {
    submitAppeal.mockResolvedValue({
      appealId: 'a9',
      subjectType: 'RELIABILITY_STRIKE',
      subjectId: 's1',
      status: 'OPEN',
    })
    const wrapper = await mountAction()

    wrapper.vm.$.setupState.openDialog()
    wrapper.vm.$.setupState.reason = '  It was a medical emergency  '
    await wrapper.vm.$.setupState.submitDialog()
    await flushPromises()

    expect(submitAppeal).toHaveBeenCalledWith({
      subjectType: 'RELIABILITY_STRIKE',
      subjectId: 's1',
      reason: 'It was a medical emergency',
    })
    expect(wrapper.find('[data-testid="appeal-status-chip"]').text()).toContain(
      'appeals.status.OPEN',
    )
  })

  it('on alreadyAppealed notifies and reloads the list', async () => {
    submitAppeal.mockRejectedValue({
      response: { data: { errorMsg: { message: 'appeals.alreadyAppealed' } } },
    })
    const wrapper = await mountAction()
    wrapper.vm.$.setupState.reason = 'again'

    await wrapper.vm.$.setupState.submitDialog()
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'negative', message: 'appeals.errors.alreadyAppealed' }),
    )
    expect(listMyAppeals).toHaveBeenCalledTimes(2)
  })

  it('maps windowExpired to its message', async () => {
    submitAppeal.mockRejectedValue({
      response: { data: { errorMsg: { message: 'appeals.windowExpired' } } },
    })
    const wrapper = await mountAction()
    wrapper.vm.$.setupState.reason = 'late'

    await wrapper.vm.$.setupState.submitDialog()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'appeals.errors.windowExpired' }),
    )
  })

  it('maps notAppealable (B6: a safety hold) to its message', async () => {
    submitAppeal.mockRejectedValue({
      response: { data: { errorMsg: { message: 'appeals.notAppealable' } } },
    })
    const wrapper = await mountAction()
    wrapper.vm.$.setupState.reason = 'please'

    await wrapper.vm.$.setupState.submitDialog()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'appeals.errors.notAppealable' }),
    )
  })
})
