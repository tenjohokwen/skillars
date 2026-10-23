// skillars-12-7 AC15 (D1a): the report dialog states plainly that recaps reach the player and parent.
// First spec for this component, so it also pins the submit behaviour the notice sits beside.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDevelopmentStore } from 'src/stores/development.store'

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import GenerateReportDialog from '../GenerateReportDialog.vue'

const mounted = []
function mountDialog() {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const wrapper = mount(GenerateReportDialog, {
    props: { modelValue: true, playerId: 7, playerName: 'Alex' },
    global: { plugins: [pinia], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  return { wrapper, store: useDevelopmentStore() }
}

describe('GenerateReportDialog.vue (skillars-12-7)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('shows the recap-visibility notice', () => {
    const { wrapper } = mountDialog()

    const notice = wrapper.find('[data-testid="recap-visibility-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('development.report.recapNotice')
  })

  it('keeps Generate disabled until next steps are entered, then submits them trimmed', async () => {
    const { wrapper, store } = mountDialog()
    store.generateReport = vi.fn().mockResolvedValue({})
    const generate = () =>
      wrapper.findAll('button').find((b) => b.text() === 'development.report.generate')
    expect(generate().element.disabled).toBe(true)

    await wrapper.find('textarea').setValue('  Work on weak foot  ')
    expect(generate().element.disabled).toBe(false)
    await generate().trigger('click')
    await flushPromises()

    expect(store.generateReport).toHaveBeenCalledWith(7, 'Work on weak foot')
  })

  // skillars-12-9 AC5: generateReport never sets store.error, so notifying it showed a generic or a
  // stale unrelated message. The thrown error carries the real reason (e.g. a relationship 403).
  it("notifies the thrown error's message on failure, not a stale store.error", async () => {
    const { wrapper, store } = mountDialog()
    store.error = 'stale unrelated error'
    store.generateReport = vi.fn().mockRejectedValue({
      response: {
        status: 403,
        data: {
          errorMsg: { errorKey: 'security.opForbidden', message: 'Relationship not active' },
        },
      },
    })
    await wrapper.find('textarea').setValue('Work on weak foot')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'development.report.generate')
      .trigger('click')
    await flushPromises()

    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message: 'Relationship not active' })
  })
})
