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

  // -------------------------------------------------------------------------
  // skillars-deferred-109 AC8 (deferred-work.md:1884-1890).
  // -------------------------------------------------------------------------
  describe('AC8.1 — a touched-but-invalid pack row blocks submit instead of being silently dropped', () => {
    const cases = [
      ['sessionCount only', { sessionCount: 5, totalPrice: null, label: '' }],
      ['sessionCount with zero price', { sessionCount: 5, totalPrice: 0, label: '' }],
      ['negative sessionCount', { sessionCount: -1, totalPrice: 20, label: '' }],
      ['label only', { sessionCount: null, totalPrice: null, label: 'Starter' }],
    ]
    for (const [name, row] of cases) {
      it(`blocks and shows a message: ${name}`, () => {
        const wrapper = mountStep3()
        wrapper.vm.form.perSessionPrice = 40
        wrapper.vm.form.sessionPacks = [row]

        wrapper.vm.submit()

        expect(wrapper.emitted('submit')).toBeUndefined()
        expect(wrapper.vm.packError).toBe('auth.coach.step3PackInvalid')
      })
    }

    // skillars-deferred-109 code review: `v-model.number` on a cleared type="number" input yields
    // '' — not null — so the original `!= null` test counted a visually EMPTY row as touched and
    // blocked submit with no way to recover by editing.
    it.each([
      [
        'sessionCount cleared to an empty string',
        { sessionCount: '', totalPrice: null, label: '' },
      ],
      ['totalPrice cleared to an empty string', { sessionCount: null, totalPrice: '', label: '' }],
      ['both cleared to empty strings', { sessionCount: '', totalPrice: '', label: '' }],
    ])('a row whose numeric fields were cleared is NOT touched: %s', (_name, row) => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.form.sessionPacks = [{ sessionCount: 5, totalPrice: 180, label: 'Starter' }, row]

      wrapper.vm.submit()

      expect(wrapper.emitted('submit')).toBeDefined()
      expect(wrapper.vm.packError).toBe('')
      // Mutation: revert packRowTouched to `p.sessionCount != null || p.totalPrice != null` → '' is
      // counted as touched, submit is blocked and packError is set → RED.
    })

    it('removePack clears a standing packError', () => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.form.sessionPacks = [{ sessionCount: 5, totalPrice: null, label: '' }]

      wrapper.vm.submit()
      expect(wrapper.vm.packError).toBe('auth.coach.step3PackInvalid')

      wrapper.vm.removePack(0)

      expect(wrapper.vm.packError).toBe('')
      // Mutation: drop `packError.value = ''` from removePack → the message survives the removal of
      // the only offending row → RED.
    })

    it('a fully-empty row alongside a valid one is filtered silently — submit still emits', () => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.form.sessionPacks = [
        { sessionCount: 5, totalPrice: 180, label: 'Starter' },
        { sessionCount: null, totalPrice: null, label: '' },
      ]

      wrapper.vm.submit()

      const emitted = wrapper.emitted('submit')
      expect(emitted).toHaveLength(1)
      expect(emitted[0][0].sessionPacks).toEqual([
        { sessionCount: 5, totalPrice: 180, label: 'Starter' },
      ])
    })
    // Mutation: remove the `if (hasTouchedInvalidPack.value) { … return }` guard from submit() →
    // the four touched-but-invalid cases emit with the rows silently absent → RED.
  })

  it('AC8.2 — durationOptions coerces a string current value: no duplicate synthetic 60', async () => {
    const wrapper = mountStep3()
    wrapper.vm.form.sessionDurationMinutes = '60' // e.g. a hypothetical future hydration path
    await wrapper.vm.$nextTick()

    const sixties = wrapper.vm.durationOptions.filter((o) => o.value === 60 || o.value === '60')
    expect(sixties).toHaveLength(1)
    expect(sixties[0].value).toBe(60) // the canonical numeric choice, not a synthetic '60'
    // Mutation: revert `const current = Number(form.sessionDurationMinutes)` to the raw value →
    // `!DURATION_CHOICES.includes('60')` is true, a `{ value: '60', label: '60' }` synthetic is
    // appended, and this assertion sees two 60-ish options → RED.
  })
})
