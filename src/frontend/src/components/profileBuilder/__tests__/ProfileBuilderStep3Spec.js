// skillars-deferred-108 AC2 — ProfileBuilderStep3.vue duration select (closes deferred-18 D6).
//
// Ledger (deferred-work.md:929, uat-2/deferred-18 D6): "ProfileBuilderStep3.vue duration select".
// The q-select is emit-value + map-options bound to a fixed DURATION_CHOICES list, with a
// defensive synthetic option (deferred-63 AC7) when a saved value is out of range.
//
// The option list is a teleported q-menu popup, so assert the backing `durationOptions` computed
// and the select's own props rather than querying rendered <q-item>s.
//
// Mutation check (run both ways, see Dev Agent Record): drop `emit-value` from the q-select — the
// props assertion (`emitValue === true`) turns red; the select would then v-model the whole
// { value, label } option object instead of the primitive minutes.

import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import ProfileBuilderStep3 from 'src/components/profileBuilder/ProfileBuilderStep3.vue'

// skillars-deferred-108 code review (P12): this copy is DELIBERATE, unlike the derived
// LEGACY_SESSION_TTL in sessionManagerCoverageSpec. DURATION_CHOICES is a product decision, not an
// implementation detail, so a change to the offered durations SHOULD break this assertion and be
// re-confirmed rather than silently absorbed. Drift here fails loudly; it cannot go vacuous.
const DURATION_CHOICES = [30, 45, 60, 90, 120]

const mounted = []

function mountStep3(props = {}) {
  const wrapper = mount(ProfileBuilderStep3, {
    props,
    global: { stubs: { 'q-btn': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

describe('ProfileBuilderStep3.vue — duration select (deferred-108 AC2)', () => {
  // P14: this file previously had no hooks at all, so every mounted instance stayed live.
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('durationOptions is the platform-default entry plus the fixed DURATION_CHOICES', () => {
    const wrapper = mountStep3()
    const values = wrapper.vm.durationOptions.map((o) => o.value)
    expect(values).toEqual([null, ...DURATION_CHOICES])
  })

  it('the q-select is emit-value + map-options and bound to durationOptions', () => {
    const wrapper = mountStep3()
    const select = wrapper.findComponent({ name: 'QSelect' })
    expect(select.exists()).toBe(true)
    expect(select.props('emitValue')).toBe(true)
    expect(select.props('mapOptions')).toBe(true)
    expect(select.props('options')).toEqual(wrapper.vm.durationOptions)
  })

  it('an out-of-range saved value gets a synthetic option rather than rendering unselected (deferred-63 AC7)', async () => {
    const wrapper = mountStep3()
    wrapper.vm.form.sessionDurationMinutes = 75
    await wrapper.vm.$nextTick()

    const synthetic = wrapper.vm.durationOptions.find((o) => o.value === 75)
    expect(synthetic).toEqual({ value: 75, label: '75' })
  })

  it('submit emits the selected sessionDurationMinutes verbatim and filters incomplete packs', () => {
    const wrapper = mountStep3()
    wrapper.vm.form.perSessionPrice = 40
    wrapper.vm.form.sessionDurationMinutes = 90
    wrapper.vm.form.sessionPacks = [
      { sessionCount: 5, totalPrice: 180, label: 'Starter' },
      { sessionCount: null, totalPrice: null, label: '' }, // incomplete → dropped
    ]

    wrapper.vm.submit()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toHaveLength(1)
    expect(emitted[0][0].sessionDurationMinutes).toBe(90)
    expect(emitted[0][0].sessionPacks).toEqual([
      { sessionCount: 5, totalPrice: 180, label: 'Starter' },
    ])
  })

  it('submit is a no-op without a positive perSessionPrice', () => {
    const wrapper = mountStep3()
    wrapper.vm.form.perSessionPrice = 0
    wrapper.vm.submit()
    expect(wrapper.emitted('submit')).toBeUndefined()
  })
})
