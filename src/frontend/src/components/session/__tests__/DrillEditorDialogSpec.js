// feat/coach-workflow-gaps D1 — DrillEditorDialog.vue: create / edit a coach's own PRIVATE drill.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import DrillEditorDialog from 'src/components/session/DrillEditorDialog.vue'
import { useSessionStore } from 'src/stores/session.store'

const mounted = []
function mountDialog(props = {}) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const wrapper = mount(DrillEditorDialog, {
    props: { modelValue: true, ...props },
    global: { plugins: [pinia], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  return { wrapper, store: useSessionStore(pinia) }
}

const DRILL = {
  id: 'd-1',
  name: 'Rondo 4v1',
  description: 'Keep it',
  libraryType: 'PRIVATE',
  metadata: {
    primarySkills: ['passing'],
    secondarySkills: ['positioning'],
    skillWeighting: { passing: 70, positioning: 30 },
    repDensity: 30,
    intensity: 6,
    pressureLevel: 7,
    cognitiveLoad: 5,
    matchRealism: 4,
    weakFootBias: true,
    difficultyTier: 'U14',
    equipmentRequired: ['cones'],
    recommendedGroupSize: '5',
    coachingPoints: ['Open body', 'Scan first'],
  },
}

afterEach(() => {
  while (mounted.length) mounted.pop().unmount()
  vi.clearAllMocks()
})

describe('DrillEditorDialog.vue (feat/coach-workflow-gaps D1)', () => {
  it('cannot save until a name and at least one primary skill are given', async () => {
    const { wrapper } = mountDialog()
    expect(wrapper.vm.canSave).toBe(false)
    wrapper.vm.form.name = 'My drill'
    expect(wrapper.vm.canSave).toBe(false)
    wrapper.vm.form.primarySkills = ['dribbling']
    expect(wrapper.vm.canSave).toBe(true)
  })

  it('creates a drill with a trimmed payload, coaching points split per line, and no skillWeighting', async () => {
    const { wrapper, store } = mountDialog()
    store.createDrill.mockResolvedValue({ id: 'new-1' })
    Object.assign(wrapper.vm.form, {
      name: '  My drill  ',
      primarySkills: ['dribbling'],
      coachingPointsText: 'Head up\n\n  Low touches  \n',
    })

    await wrapper.vm.save()
    await flushPromises()

    expect(store.createDrill).toHaveBeenCalledTimes(1)
    const payload = store.createDrill.mock.calls[0][0]
    expect(payload.name).toBe('My drill')
    expect(payload.coachingPoints).toEqual(['Head up', 'Low touches'])
    expect(payload).not.toHaveProperty('skillWeighting')
    expect(payload).not.toHaveProperty('setupDiagram')
    expect(wrapper.emitted('saved')[0][0]).toEqual({ id: 'new-1' })
    expect(wrapper.emitted('update:modelValue').at(-1)).toEqual([false])
  })

  it('edit mode pre-fills from the drill and calls updateDrill with its id', async () => {
    const { wrapper, store } = mountDialog({ drill: DRILL })
    store.updateDrill.mockResolvedValue({ ...DRILL, name: 'Rondo 5v2' })
    expect(wrapper.vm.form.name).toBe('Rondo 4v1')
    expect(wrapper.vm.form.coachingPointsText).toBe('Open body\nScan first')

    wrapper.vm.form.name = 'Rondo 5v2'
    await wrapper.vm.save()
    await flushPromises()

    expect(store.updateDrill).toHaveBeenCalledWith(
      'd-1',
      expect.objectContaining({ name: 'Rondo 5v2', difficultyTier: 'U14', weakFootBias: true }),
    )
    expect(store.createDrill).not.toHaveBeenCalled()
  })

  it('maps the duplicate-name constraint (session.drillNameTaken) onto the name field and stays open', async () => {
    const { wrapper, store } = mountDialog()
    store.createDrill.mockRejectedValue({
      response: { status: 409, data: { errorMsg: { errorKey: 'session.drillNameTaken' } } },
    })
    Object.assign(wrapper.vm.form, { name: 'Dup', primarySkills: ['passing'] })

    await wrapper.vm.save()
    await flushPromises()

    expect(wrapper.text()).toContain('session.drillEditor.nameTaken')
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it('refuses more than 8 coaching points client-side, matching DrillUpsertRequest', async () => {
    const { wrapper, store } = mountDialog()
    Object.assign(wrapper.vm.form, {
      name: 'Too many',
      primarySkills: ['passing'],
      coachingPointsText: Array.from({ length: 9 }, (_, i) => `Point ${i}`).join('\n'),
    })

    await wrapper.vm.save()

    expect(store.createDrill).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('session.drillEditor.coachingPointsTooMany')
  })
})
