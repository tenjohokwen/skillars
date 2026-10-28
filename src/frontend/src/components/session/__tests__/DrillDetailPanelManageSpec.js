// feat/coach-workflow-gaps D1 — DrillDetailPanel.vue edit/delete affordances for the coach's own
// PRIVATE drills, and the demo-video eligibility fetch when a PRIVATE drill is swapped in.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

const { dialogMock, notifyMock } = vi.hoisted(() => ({ dialogMock: vi.fn(), notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    useQuasar: () => ({ notify: notifyMock, dialog: dialogMock, screen: { lt: { sm: false } } }),
  }
})

import DrillDetailPanel from 'src/components/session/DrillDetailPanel.vue'
import { useSessionStore } from 'src/stores/session.store'

const PRIVATE_DRILL = { id: 'd-1', name: 'Mine', libraryType: 'PRIVATE', metadata: {} }
const PLATFORM_DRILL = { id: 'p-1', name: 'Platform', libraryType: 'PLATFORM', metadata: {} }

const mounted = []
function mountPanel(props) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const wrapper = mount(DrillDetailPanel, {
    props: { isOpen: true, ...props },
    global: {
      plugins: [pinia],
      stubs: {
        QDialog: { template: '<div><slot /></div>' },
        QScrollArea: { template: '<div><slot /></div>' },
      },
    },
  })
  mounted.push(wrapper)
  return { wrapper, store: useSessionStore(pinia) }
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('DrillDetailPanel.vue — manage own drill (feat/coach-workflow-gaps D1)', () => {
  it('shows Edit/Delete for a PRIVATE drill in the library, emitting edit with the drill', async () => {
    const { wrapper } = mountPanel({ drill: PRIVATE_DRILL })
    await flushPromises()
    await wrapper.find('[data-test="drill-edit"]').trigger('click')
    expect(wrapper.emitted('edit')[0][0]).toEqual(PRIVATE_DRILL)
    expect(wrapper.find('[data-test="drill-delete"]').exists()).toBe(true)
  })

  it('hides Edit/Delete for PLATFORM drills and inside the session builder', async () => {
    const { wrapper } = mountPanel({ drill: PLATFORM_DRILL })
    await flushPromises()
    expect(wrapper.find('[data-test="drill-edit"]').exists()).toBe(false)

    const { wrapper: builder } = mountPanel({ drill: PRIVATE_DRILL, context: 'session-builder' })
    await flushPromises()
    expect(builder.find('[data-test="drill-edit"]').exists()).toBe(false)
  })

  it('Delete confirms first, then calls deleteDrill and emits deleted', async () => {
    let onOk
    dialogMock.mockReturnValue({ onOk: (fn) => (onOk = fn) })
    const { wrapper, store } = mountPanel({ drill: PRIVATE_DRILL })
    store.deleteDrill.mockResolvedValue()
    await flushPromises()

    await wrapper.find('[data-test="drill-delete"]').trigger('click')
    expect(store.deleteDrill).not.toHaveBeenCalled()
    await onOk()
    await flushPromises()

    expect(store.deleteDrill).toHaveBeenCalledWith('d-1')
    expect(wrapper.emitted('deleted')[0][0]).toBe('d-1')
  })

  it('fetches demo-video eligibility when a PRIVATE drill is swapped into an already-mounted panel', async () => {
    const { wrapper, store } = mountPanel({ drill: null })
    await flushPromises()
    expect(store.fetchVideoUploadEligibility).not.toHaveBeenCalled()

    await wrapper.setProps({ drill: PRIVATE_DRILL })
    await flushPromises()
    expect(store.fetchVideoUploadEligibility).toHaveBeenCalledTimes(1)
  })
})
