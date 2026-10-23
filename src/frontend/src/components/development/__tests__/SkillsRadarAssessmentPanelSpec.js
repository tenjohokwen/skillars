// skillars-12-9 AC5: every 403 used to be shown as "requires Instructor tier". A write can also be
// denied because the booking relationship is not in the narrow ACTIVE status list, and telling an
// Instructor coach to upgrade then is wrong. Only errorKey === 'security.featureGated' is a tier gate.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { useDevelopmentStore } from 'src/stores/development.store'

const notifySpy = vi.fn()
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifySpy }) }
})

import SkillsRadarAssessmentPanel from '../SkillsRadarAssessmentPanel.vue'

const mounted = []
async function submitWithRejection(err) {
  const pinia = createTestingPinia({ createSpy: vi.fn })
  const store = useDevelopmentStore()
  store.submitRadarAssessment = vi.fn().mockRejectedValue(err)
  const wrapper = mount(SkillsRadarAssessmentPanel, {
    props: {
      modelValue: true,
      playerId: '7',
      skillDefinitions: [{ code: 'PAC', displayName: 'Pace' }],
    },
    global: { plugins: [pinia], stubs: { QDialog: { template: '<div><slot /></div>' } } },
  })
  mounted.push(wrapper)
  await wrapper.find('input[type="number"]').setValue('70')
  await wrapper
    .findAll('button')
    .find((b) => b.text() === 'development.radar.submitLabel')
    .trigger('click')
  await flushPromises()
  return wrapper
}

const body = (errorKey, message) => ({
  response: { status: 403, data: { helpCode: 'log-ref-1', errorMsg: { errorKey, message } } },
})

describe('SkillsRadarAssessmentPanel.vue (skillars-12-9)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('shows the server message, not the tier upsell, for a relationship 403', async () => {
    await submitWithRejection(body('security.opForbidden', 'Relationship not active'))
    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message: 'Relationship not active' })
  })

  it('shows the tier message for a security.featureGated 403', async () => {
    await submitWithRejection(body('security.featureGated', 'Feature gated'))
    expect(notifySpy).toHaveBeenCalledWith({
      type: 'negative',
      message: 'development.radar.submitErrorFeatureGated',
    })
  })

  it('does not treat helpCode as the discriminator', async () => {
    await submitWithRejection({
      response: {
        status: 403,
        data: {
          helpCode: 'security.featureGated',
          errorMsg: { errorKey: 'security.opForbidden', message: 'Denied' },
        },
      },
    })
    expect(notifySpy).toHaveBeenCalledWith({ type: 'negative', message: 'Denied' })
  })
})
