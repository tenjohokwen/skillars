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

  it('submit emits the selected sessionDurationMinutes verbatim and the one pack', () => {
    const wrapper = mountStep3()
    wrapper.vm.form.perSessionPrice = 40
    wrapper.vm.form.sessionDurationMinutes = 90
    wrapper.vm.addPack()
    Object.assign(wrapper.vm.form.sessionPacks[0], {
      sessionCount: 5,
      totalPrice: 180,
      label: 'Starter',
    })

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
  // skillars-deferred-168: the pack is the coach's one purchasable offer.
  // -------------------------------------------------------------------------
  describe('deferred-168 — one pack, sent only when the coach touched it', () => {
    it('an untouched pack section sends sessionPacks: null, so revisiting step 3 never withdraws an offer', () => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40

      wrapper.vm.submit()

      expect(wrapper.emitted('submit')[0][0].sessionPacks).toBeNull()
      // Mutation: send packsForRequest(form.sessionPacks) unconditionally → [] → RED.
    })

    it('adding then removing the pack sends null, never a withdrawal (V2)', () => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.addPack()
      wrapper.vm.removePack(0)

      wrapper.vm.submit()

      expect(wrapper.emitted('submit')[0][0].sessionPacks).toBeNull()
    })

    it('an added but empty pack is not sent, does not block and does not withdraw an offer (V2)', () => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.addPack()

      wrapper.vm.submit()

      expect(wrapper.emitted('submit')[0][0].sessionPacks).toBeNull()
      expect(wrapper.vm.packError).toBe('')
    })

    it('offers "Add pack" only while there is no pack', async () => {
      const wrapper = mount(ProfileBuilderStep3, { props: {} })
      mounted.push(wrapper)
      const addLabel = 'auth.coach.step3AddPack'
      expect(wrapper.text()).toContain(addLabel)

      wrapper.vm.addPack()
      await wrapper.vm.$nextTick()

      expect(wrapper.text()).not.toContain(addLabel)
    })

    const blocked = [
      [
        'sessionCount only',
        { sessionCount: 5, totalPrice: null, label: '' },
        'auth.coach.step3PackInvalid',
      ],
      [
        'label only',
        { sessionCount: null, totalPrice: null, label: 'Starter' },
        'auth.coach.step3PackInvalid',
      ],
      [
        'zero price',
        { sessionCount: 5, totalPrice: 0, label: '' },
        'auth.coach.step3PackPriceError',
      ],
      [
        'negative sessionCount',
        { sessionCount: -1, totalPrice: 20, label: '' },
        'auth.coach.step3PackSessionCountError',
      ],
      [
        'fractional sessionCount',
        { sessionCount: 2.5, totalPrice: 20, label: '' },
        'auth.coach.step3PackSessionCountError',
      ],
      [
        'more than 100 sessions',
        { sessionCount: 101, totalPrice: 2000, label: '' },
        'auth.coach.step3PackSessionCountError',
      ],
      [
        'three decimals',
        { sessionCount: 5, totalPrice: 10.005, label: '' },
        'auth.coach.step3PackPriceError',
      ],
      [
        'over 50,000',
        { sessionCount: 5, totalPrice: 50000.01, label: '' },
        'auth.coach.step3PackPriceError',
      ],
      [
        'per-session share rounds to 0.00',
        { sessionCount: 100, totalPrice: 0.49, label: '' },
        'auth.coach.step3PackPerSessionError',
      ],
      [
        'label over 200 characters',
        { sessionCount: 5, totalPrice: 200, label: 'x'.repeat(201) },
        'auth.coach.step3PackLabelTooLong',
      ],
    ]
    for (const [name, pack, key] of blocked) {
      it(`blocks with a specific message: ${name}`, () => {
        const wrapper = mountStep3()
        wrapper.vm.form.perSessionPrice = 40
        wrapper.vm.addPack()
        Object.assign(wrapper.vm.form.sessionPacks[0], pack)

        wrapper.vm.submit()

        expect(wrapper.emitted('submit')).toBeUndefined()
        expect(wrapper.vm.packError).toBe(key)
      })
    }

    it('the tier bounds themselves are accepted', () => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.addPack()
      Object.assign(wrapper.vm.form.sessionPacks[0], {
        sessionCount: 100,
        totalPrice: 50000,
        label: 'x'.repeat(200),
      })

      wrapper.vm.submit()

      expect(wrapper.emitted('submit')).toHaveLength(1)
    })

    // skillars-deferred-109 code review: `v-model.number` on a cleared type="number" input yields
    // '' — not null — so a visually EMPTY pack must not count as touched.
    it.each([
      [
        'sessionCount cleared to an empty string',
        { sessionCount: '', totalPrice: null, label: '' },
      ],
      ['totalPrice cleared to an empty string', { sessionCount: null, totalPrice: '', label: '' }],
      ['both cleared to empty strings', { sessionCount: '', totalPrice: '', label: '' }],
    ])('a pack whose numeric fields were cleared is NOT touched: %s', (_name, pack) => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.addPack()
      Object.assign(wrapper.vm.form.sessionPacks[0], pack)

      wrapper.vm.submit()

      expect(wrapper.emitted('submit')[0][0].sessionPacks).toBeNull()
      expect(wrapper.vm.packError).toBe('')
    })

    it('removePack clears a standing packError', () => {
      const wrapper = mountStep3()
      wrapper.vm.form.perSessionPrice = 40
      wrapper.vm.addPack()
      wrapper.vm.form.sessionPacks[0].sessionCount = 5

      wrapper.vm.submit()
      expect(wrapper.vm.packError).toBe('auth.coach.step3PackInvalid')

      wrapper.vm.removePack(0)

      expect(wrapper.vm.packError).toBe('')
    })
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
