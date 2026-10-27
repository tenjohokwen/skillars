// skillars-deferred-165 AC7: SessionTemplateVault.vue's rename dialog name input must match the
// backend's RenameTemplateRequest @Size(max=200) with a client-side maxlength.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import SessionTemplateVault from 'src/pages/coach/SessionTemplateVault.vue'

const TEMPLATE = { id: 't1', name: 'My Template', drillCount: 3, deployCount: 1 }

const mounted = []
async function mountPage() {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: { sessionTemplate: { templates: [TEMPLATE] } },
  })
  const wrapper = mount(SessionTemplateVault, {
    global: {
      plugins: [pinia],
      stubs: {
        SessionDNAChart: true,
        QDialog: { template: '<div><slot /></div>' },
      },
    },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('SessionTemplateVault.vue (skillars-deferred-165 AC7)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('the rename dialog name input carries maxlength=200', async () => {
    const wrapper = await mountPage()

    wrapper.vm.startRename(TEMPLATE)
    await flushPromises()

    const input = wrapper.find('input[maxlength="200"]')
    expect(input.exists()).toBe(true)
  })
})
