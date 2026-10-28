// feat/admin-operations A9: AdminConfigPage.vue — platform config editor: list with type and
// allowed range, search, client-side range/type validation and the server's 400 reason per field.

import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/admin.api', () => ({
  adminApi: { getConfigValues: vi.fn(), updateConfigValue: vi.fn() },
}))

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import { adminApi } from 'src/api/admin.api'
import AdminConfigPage from 'src/pages/admin/AdminConfigPage.vue'

const BOUNDED = {
  key: 'disputes.submissionWindowDays',
  value: '7',
  valueType: 'LONG',
  description: 'Days to file a dispute',
  updatedAt: '2026-10-01T10:00:00Z',
  min: 1,
  max: 90,
  note: '0 → no disputes can be filed',
  failFast: true,
  stored: true,
  codeDefault: 7,
}
const FLAG = {
  key: 'feature.sessionBuilder.enabled.SCOUT',
  value: 'false',
  valueType: 'STRING',
  description: null,
  updatedAt: '2026-10-01T10:00:00Z',
  min: null,
  max: null,
  note: null,
  failFast: false,
  stored: true,
  codeDefault: null,
}
const UNSEEDED = {
  key: 'platform.radar_composite_lock_timeout_seconds',
  value: null,
  valueType: 'LONG',
  description: null,
  updatedAt: null,
  min: 1,
  max: 2 ** 63, // Long.MAX_VALUE as it arrives after JSON.parse
  note: 'lock timeout',
  failFast: false,
  stored: false,
  codeDefault: 5,
}

const mounted = []
async function mountPage() {
  const wrapper = mount(AdminConfigPage, {
    global: { stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('AdminConfigPage.vue', () => {
  beforeEach(() => {
    adminApi.getConfigValues.mockResolvedValue([BOUNDED, FLAG, UNSEEDED])
  })

  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('lists every setting, marking unseeded ones with their code default', async () => {
    const wrapper = await mountPage()

    expect(adminApi.getConfigValues).toHaveBeenCalled()
    expect(wrapper.vm.filteredRows).toHaveLength(3)
    expect(wrapper.find('[data-testid="config-code-default"]').exists()).toBe(true)
  })

  it('filters by key or description', async () => {
    const wrapper = await mountPage()
    wrapper.vm.search = 'dispute'

    expect(wrapper.vm.filteredRows.map((r) => r.key)).toEqual([BOUNDED.key])
  })

  it('shows a Long.MAX_VALUE upper bound as "at least"', async () => {
    const wrapper = await mountPage()

    expect(wrapper.vm.rangeLabel(UNSEEDED)).toBe('admin.config.rangeAtLeast')
    expect(wrapper.vm.rangeLabel(BOUNDED)).toBe('1 – 90')
    expect(wrapper.vm.rangeLabel(FLAG)).toBe('—')
  })

  it('validates integer type and range before submitting', async () => {
    const wrapper = await mountPage()
    wrapper.vm.openEdit(BOUNDED)

    wrapper.vm.draft = ''
    expect(wrapper.vm.fieldError).toBe('admin.config.errorRequired')
    wrapper.vm.draft = 'abc'
    expect(wrapper.vm.fieldError).toBe('admin.config.errorInteger')
    wrapper.vm.draft = '91'
    expect(wrapper.vm.fieldError).toBe('admin.config.errorRange')
    expect(wrapper.vm.canSave).toBe(false)
    wrapper.vm.draft = '14'
    expect(wrapper.vm.fieldError).toBe('')
    expect(wrapper.vm.canSave).toBe(true)
  })

  it('does not range-check an unbounded upper limit client-side', async () => {
    const wrapper = await mountPage()
    wrapper.vm.openEdit(UNSEEDED)

    expect(wrapper.vm.draft).toBe('5')
    wrapper.vm.draft = '99999999999999999999'
    expect(wrapper.vm.fieldError).toBe('')
    wrapper.vm.draft = '0'
    expect(wrapper.vm.fieldError).toBe('admin.config.errorRange')
  })

  it('accepts any non-blank text for an unbounded STRING setting', async () => {
    const wrapper = await mountPage()
    wrapper.vm.openEdit(FLAG)

    expect(wrapper.vm.canSave).toBe(false) // unchanged
    wrapper.vm.draft = 'true'
    expect(wrapper.vm.canSave).toBe(true)
  })

  it('warns that a fail-fast setting blocks startup', async () => {
    const wrapper = await mountPage()
    wrapper.vm.openEdit(BOUNDED)
    await flushPromises()

    expect(wrapper.find('[data-testid="config-failfast-warning"]').exists()).toBe(true)
  })

  it('saves the trimmed value and updates the row in place', async () => {
    adminApi.updateConfigValue.mockResolvedValue({
      key: BOUNDED.key,
      value: '14',
      valueType: 'LONG',
      updatedAt: '2026-10-28T10:00:00Z',
    })
    const wrapper = await mountPage()
    wrapper.vm.openEdit(BOUNDED)
    wrapper.vm.draft = ' 14 '

    await wrapper.vm.save()

    expect(adminApi.updateConfigValue).toHaveBeenCalledWith(BOUNDED.key, '14')
    const row = wrapper.vm.filteredRows.find((r) => r.key === BOUNDED.key)
    expect(row.value).toBe('14')
    expect(row.updatedAt).toBe('2026-10-28T10:00:00Z')
    expect(wrapper.vm.editOpen).toBe(false)
  })

  it('saving an unseeded setting marks it stored', async () => {
    adminApi.updateConfigValue.mockResolvedValue({
      key: UNSEEDED.key,
      value: '10',
      valueType: 'LONG',
      updatedAt: '2026-10-28T10:00:00Z',
    })
    const wrapper = await mountPage()
    wrapper.vm.openEdit(UNSEEDED)
    wrapper.vm.draft = '10'

    await wrapper.vm.save()

    expect(wrapper.vm.filteredRows.find((r) => r.key === UNSEEDED.key).stored).toBe(true)
  })

  it("shows the server's 400 reason on the field and keeps the dialog open", async () => {
    adminApi.updateConfigValue.mockRejectedValue({
      response: {
        status: 400,
        data: { errorMsg: { errorKey: 'generic.requestError', message: 'cooldown too short' } },
      },
    })
    const wrapper = await mountPage()
    wrapper.vm.openEdit(BOUNDED)
    wrapper.vm.draft = '14'

    await wrapper.vm.save()

    expect(wrapper.vm.fieldError).toBe('cooldown too short')
    expect(wrapper.vm.editOpen).toBe(true)

    // Typing clears the server error.
    await flushPromises()
    await wrapper.find('input[data-testid="config-value-input"]').setValue('15')
    expect(wrapper.vm.serverError).toBe('')
  })

  it('notifies on an unexpected failure', async () => {
    adminApi.updateConfigValue.mockRejectedValue({ response: { status: 500 } })
    const wrapper = await mountPage()
    wrapper.vm.openEdit(BOUNDED)
    wrapper.vm.draft = '14'

    await wrapper.vm.save()

    expect(notifySpy).toHaveBeenCalledWith(
      expect.objectContaining({ message: 'admin.config.saveFailed', type: 'negative' }),
    )
  })

  it('tolerates a stored row with a null value', async () => {
    const wrapper = await mountPage()
    wrapper.vm.openEdit({ ...FLAG, value: null })

    expect(wrapper.vm.draft).toBe('')
    expect(wrapper.vm.canSave).toBe(false)
    expect(wrapper.vm.fieldError).toBe('admin.config.errorRequired')
  })
})
