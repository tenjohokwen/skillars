// skillars-deferred-140 AC1.1 (Option B) — ProfileBuilderStep4.vue.
//
// The per-window TimezoneSelect picker is removed from the happy path: saveStep4 now always
// overwrites every window's zone with the coach's profile zone, so offering an independent choice
// there implied one the backend silently discards. The zone is fetched from getOwnCoachProfile()
// (the authoritative source) and displayed read-only, falling back to the Pinia store's
// session-only value if that call fails.
//
// skillars-deferred-140 code review: the picker returns as a RECOVERY path. Read-only with no
// fallback dead-ended the step — Next is bound to `:disable="!canonicalTimezone"`, the mount failure
// was silently swallowed, and there was no control to supply a value. Two reachable routes into
// that state are covered below: (a) profile fetch fails on a fresh session, so the store has nothing
// either; (b) the fetch succeeds but returns a legacy zone this server no longer accepts (a fixed
// offset predating the 2026-08-25 @IanaTimezone tightening), which would be stamped onto every
// window and 400 on submit with no way to change it.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'

vi.mock('src/api/marketplace.api', () => ({
  getOwnCoachProfile: vi.fn(),
}))

import { getOwnCoachProfile } from 'src/api/marketplace.api'
import { useProfileBuilderStore } from 'src/stores/profileBuilder.store'
import ProfileBuilderStep4 from 'src/components/profileBuilder/ProfileBuilderStep4.vue'

const SUPPORTED = ['Europe/Berlin', 'Europe/Paris', 'America/New_York', 'Asia/Tokyo', 'Etc/UTC']

// TimezoneSelect preselects the browser's own zone when it mounts with no value and the server knows
// that zone — correct self-healing behaviour, but it hides the no-value state these tests need to
// observe. This list deliberately contains nothing that could be the test environment's zone (local
// dev runs at +02:00, CI at UTC), so no preselect fires and the stuck state stays visible.
const NO_PRESELECT = ['Asia/Tokyo', 'Pacific/Kiritimati']

const mounted = []
function mountStep4(props = {}, initialState = {}, supported = SUPPORTED) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      profileBuilder: { selectedTimezone: null, supportedTimezones: supported, ...initialState },
    },
  })
  // The store action is a stub under createTestingPinia, so it must resolve to a real array —
  // TimezoneSelect calls zones.includes(...) on the result when it mounts in the recovery path.
  const store = useProfileBuilderStore(pinia)
  store.loadSupportedTimezones.mockResolvedValue(supported)

  const wrapper = mount(ProfileBuilderStep4, {
    props,
    global: { plugins: [pinia], stubs: { 'q-btn': true } },
  })
  mounted.push(wrapper)
  return wrapper
}

const findTimezoneSelect = (wrapper) => wrapper.findComponent({ name: 'TimezoneSelect' })

describe('ProfileBuilderStep4.vue (skillars-deferred-140 AC1.1)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('renders no editable timezone control once a usable profile zone resolves', async () => {
    getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: 'Europe/Berlin' })
    const wrapper = mountStep4()
    await flushPromises()

    expect(findTimezoneSelect(wrapper).exists()).toBe(false)
  })

  it('fetches and displays the profile zone from getOwnCoachProfile on mount', async () => {
    getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: 'Europe/Berlin' })
    const wrapper = mountStep4()
    await flushPromises()

    expect(wrapper.text()).toContain('Europe/Berlin')
    expect(wrapper.vm.canonicalTimezone).toBe('Europe/Berlin')
  })

  it('pairs the read-only value with a hint saying where it comes from', async () => {
    getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: 'Europe/Berlin' })
    const wrapper = mountStep4()
    await flushPromises()

    // EditCoachAvailabilityDialog.vue pairs its own read-only value with an equivalent hint; without
    // one here a coach who previously had a picker sees a bare value and no way to know why.
    expect(wrapper.text()).toContain('auth.coach.step4TimezoneReadonlyHint')
  })

  it('falls back to the store-remembered zone if getOwnCoachProfile fails', async () => {
    getOwnCoachProfile.mockRejectedValue(new Error('network error'))
    const wrapper = mountStep4({}, { selectedTimezone: 'Europe/Paris' })
    await flushPromises()

    expect(wrapper.vm.canonicalTimezone).toBe('Europe/Paris')
    expect(findTimezoneSelect(wrapper).exists()).toBe(false)
  })

  it('submit stamps the fetched profile zone onto every window', async () => {
    getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: 'America/New_York' })
    const wrapper = mountStep4()
    await flushPromises()
    wrapper.vm.form.windows = [
      { dayOfWeek: 1, startTime: '09:00', endTime: '11:00' },
      { dayOfWeek: 2, startTime: '10:00', endTime: '12:00' },
    ]

    wrapper.vm.submit()

    const emitted = wrapper.emitted('submit')
    expect(emitted).toHaveLength(1)
    expect(emitted[0][0].windows).toEqual([
      { dayOfWeek: 1, startTime: '09:00', endTime: '11:00', canonicalTimezone: 'America/New_York' },
      { dayOfWeek: 2, startTime: '10:00', endTime: '12:00', canonicalTimezone: 'America/New_York' },
    ])
  })

  it('submit is a no-op with no windows', async () => {
    getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: 'Europe/Berlin' })
    const wrapper = mountStep4()
    await flushPromises()

    wrapper.vm.submit()

    expect(wrapper.emitted('submit')).toBeUndefined()
  })

  describe('recovery when no usable zone is available (code review)', () => {
    it('offers the picker instead of dead-ending when the fetch fails and the store is empty', async () => {
      getOwnCoachProfile.mockRejectedValue(new Error('network error'))
      // selectedTimezone: null — fresh session, store resets on reload
      const wrapper = mountStep4({}, {}, NO_PRESELECT)
      await flushPromises()

      expect(wrapper.vm.canonicalTimezone).toBeNull()
      expect(wrapper.vm.needsManualTimezone).toBe(true)
      expect(findTimezoneSelect(wrapper).exists()).toBe(true)
      expect(wrapper.text()).toContain('auth.coach.step4TimezoneUnavailable')
    })

    it('offers the picker when the stored zone is a legacy value the server no longer accepts', async () => {
      // A fixed offset accepted before the 2026-08-25 @IanaTimezone tightening. Truthy, so the old
      // code displayed it read-only and stamped it onto every window — guaranteeing a 400 on submit.
      getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: '+01:00' })
      const wrapper = mountStep4()
      await flushPromises()

      expect(wrapper.vm.needsManualTimezone).toBe(true)
      expect(findTimezoneSelect(wrapper).exists()).toBe(true)
    })

    it('does not second-guess a stored zone when the supported list itself failed to load', async () => {
      getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: 'Europe/Berlin' })
      const wrapper = mountStep4({}, {}, []) // empty list => nothing to validate against
      await flushPromises()

      expect(wrapper.vm.needsManualTimezone).toBe(false)
      expect(findTimezoneSelect(wrapper).exists()).toBe(false)
    })

    it('does not flash the picker before the mount requests resolve', () => {
      getOwnCoachProfile.mockResolvedValue({ canonicalTimezone: 'Europe/Berlin' })
      const wrapper = mountStep4() // deliberately not awaited

      expect(wrapper.vm.needsManualTimezone).toBe(false)
      expect(findTimezoneSelect(wrapper).exists()).toBe(false)
    })

    it('a zone chosen through the recovery picker is stamped onto every window', async () => {
      getOwnCoachProfile.mockRejectedValue(new Error('network error'))
      const wrapper = mountStep4({}, {}, NO_PRESELECT)
      await flushPromises()

      findTimezoneSelect(wrapper).vm.$emit('update:modelValue', 'Asia/Tokyo')
      await flushPromises()
      wrapper.vm.form.windows = [{ dayOfWeek: 1, startTime: '09:00', endTime: '11:00' }]

      wrapper.vm.submit()

      const emitted = wrapper.emitted('submit')
      expect(emitted).toHaveLength(1)
      expect(emitted[0][0].windows[0].canonicalTimezone).toBe('Asia/Tokyo')
    })
  })
})
