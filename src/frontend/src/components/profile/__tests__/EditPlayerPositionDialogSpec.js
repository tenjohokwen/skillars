// skillars-deferred-139 AC4/AC5 — EditPlayerPositionDialog.vue.
//
// One component serving two call sites: a Player editing their own position (AC4, no playerId
// prop -> playerProfileApi.updateOwnPosition) and a Parent editing one specific child's position
// (AC5, playerId prop set -> playerProfileApi.updateChildPosition(playerId, …)). Covers prefill
// from currentPosition and that submit dispatches to the correct API call for each mode.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('src/api/playerProfile.api', () => ({
  playerProfileApi: {
    updateOwnPosition: vi.fn(),
    updateChildPosition: vi.fn(),
  },
}))

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, useQuasar: () => ({ notify: notifyMock }) }
})

import { playerProfileApi } from 'src/api/playerProfile.api'
import EditPlayerPositionDialog from 'src/components/profile/EditPlayerPositionDialog.vue'

const mounted = []
function mountDialog(props = {}) {
  const wrapper = mount(EditPlayerPositionDialog, {
    props: { modelValue: true, currentPosition: 'DEFENDER', ...props },
    global: { stubs: { 'q-banner': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('EditPlayerPositionDialog.vue (deferred-139 AC4/AC5)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('prefills position from currentPosition', () => {
    const wrapper = mountDialog()
    expect(wrapper.vm.position).toBe('DEFENDER')
  })

  it('without a playerId prop, submit calls updateOwnPosition (AC4)', async () => {
    playerProfileApi.updateOwnPosition.mockResolvedValue({})
    const wrapper = mountDialog()
    wrapper.vm.position = 'FORWARD'

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(playerProfileApi.updateOwnPosition).toHaveBeenCalledWith('FORWARD')
    expect(playerProfileApi.updateChildPosition).not.toHaveBeenCalled()
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('with a playerId prop, submit calls updateChildPosition(playerId, …) instead (AC5)', async () => {
    playerProfileApi.updateChildPosition.mockResolvedValue({})
    const wrapper = mountDialog({ playerId: 77 })
    wrapper.vm.position = 'MIDFIELDER'

    await wrapper.vm.handleSubmit()
    await flushPromises()

    expect(playerProfileApi.updateChildPosition).toHaveBeenCalledWith(77, 'MIDFIELDER')
    expect(playerProfileApi.updateOwnPosition).not.toHaveBeenCalled()
    expect(wrapper.emitted('updated')).toHaveLength(1)
  })

  it('submit is a no-op without a position selected', async () => {
    const wrapper = mountDialog({ currentPosition: null })
    wrapper.vm.position = null

    await wrapper.vm.handleSubmit()

    expect(playerProfileApi.updateOwnPosition).not.toHaveBeenCalled()
    expect(playerProfileApi.updateChildPosition).not.toHaveBeenCalled()
  })

  // skillars-deferred-139 review Patch 7
  it('a cancelled edit does not survive a close/reopen of the shared dialog instance', async () => {
    const wrapper = mountDialog({ currentPosition: 'DEFENDER' })
    wrapper.vm.position = 'FORWARD' // edited, not saved

    await wrapper.setProps({ modelValue: false }) // cancel
    await wrapper.setProps({ modelValue: true }) // reopen for the same (or another) row

    expect(wrapper.vm.position).toBe('DEFENDER')
  })
})
