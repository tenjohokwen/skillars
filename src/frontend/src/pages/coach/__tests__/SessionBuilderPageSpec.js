// skillars-deferred-165 AC14: SessionBuilderPage.vue's first-ever page-level spec. Covers
// onMounted's actual sequencing (:307-315 at drafting time): fetchExistingPlan is always awaited
// first; fetchPrep + fetchDrills only fire, in parallel, once that resolves and the booking is not
// scout-gated; a gated booking never calls fetchPrep at all. Also folds in AC7's maxlength
// assertion for the "Save as Template" dialog's name input (no other spec exercises this page).
//
// The heavy dependency graph (draggable, drill library, DNA chart) is stubbed via `shallow: true`
// + `renderStubDefaultSlot: true` — real child components never mount, but the page's own
// onMounted/template-gating logic still runs unchanged.

import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createTestingPinia } from '@pinia/testing'
import { createRouter, createMemoryHistory } from 'vue-router'

vi.mock('src/boot/axios', () => ({
  api: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

const { notifyMock } = vi.hoisted(() => ({ notifyMock: vi.fn() }))
vi.mock('quasar', async (importOriginal) => {
  const actual = await importOriginal()
  const real = actual.useQuasar
  return {
    ...actual,
    useQuasar: () => {
      const $q = real()
      $q.notify = notifyMock
      return $q
    },
  }
})

import SessionBuilderPage from 'src/pages/coach/SessionBuilderPage.vue'
import { useSessionBuilderStore } from 'src/stores/sessionBuilder.store'
import { useSessionStore } from 'src/stores/session.store'

const mounted = []

function deferred() {
  let resolve
  const promise = new Promise((r) => (resolve = r))
  return { promise, resolve }
}

// createTestingPinia sets itself as the active Pinia synchronously (before this function
// returns), so `configureStores` can resolve the stubbed stores and attach custom
// mockImplementations BEFORE the component mounts and its onMounted fires.
async function mountPage({
  bookingId = 'b-1',
  initialState = {},
  configureStores,
  shallow = true,
} = {}) {
  const pinia = createTestingPinia({
    createSpy: vi.fn,
    initialState: {
      sessionBuilder: { isGated: false, sessionId: null, developmentFocus: [], saving: false },
      ...initialState,
    },
  })
  const builderStore = useSessionBuilderStore()
  const sessionStore = useSessionStore()
  configureStores?.({ builderStore, sessionStore })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/coach/session-builder/:bookingId', component: SessionBuilderPage }],
  })
  router.push(`/coach/session-builder/${bookingId}`)
  await router.isReady()

  const wrapper = mount(SessionBuilderPage, {
    shallow,
    global: {
      plugins: [pinia, router],
      renderStubDefaultSlot: true,
      stubs: shallow ? {} : { QDialog: { template: '<div><slot /></div>' } },
    },
  })
  mounted.push(wrapper)
  return { wrapper, builderStore, sessionStore }
}

describe('SessionBuilderPage.vue — mount ordering (skillars-deferred-165 AC14)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('always awaits fetchExistingPlan before fetchPrep/fetchDrills ever start', async () => {
    const calls = []
    const existingPlan = deferred()

    await mountPage({
      configureStores: ({ builderStore, sessionStore }) => {
        builderStore.fetchExistingPlan.mockImplementation(async () => {
          calls.push('fetchExistingPlan:start')
          await existingPlan.promise
          calls.push('fetchExistingPlan:end')
        })
        builderStore.fetchPrep.mockImplementation(async () => {
          calls.push('fetchPrep')
        })
        sessionStore.searchDrills.mockImplementation(async () => {
          calls.push('searchDrills')
        })
      },
    })

    // fetchExistingPlan has started but not resolved yet — fetchPrep/searchDrills must not have
    // started, proving the await (not a fire-and-forget) gates them.
    await flushPromises()
    expect(calls).toEqual(['fetchExistingPlan:start'])

    existingPlan.resolve()
    await flushPromises()

    expect(calls).toEqual([
      'fetchExistingPlan:start',
      'fetchExistingPlan:end',
      'fetchPrep',
      'searchDrills',
    ])
    // Mutation check: drop the `await` on fetchExistingPlan (fire-and-forget it) -> 'fetchPrep'/
    // 'searchDrills' would appear in `calls` BEFORE 'fetchExistingPlan:end', RED.
  })

  it('a scout-gated booking never calls fetchPrep (or fetchDrills) at all', async () => {
    const { builderStore, sessionStore } = await mountPage({
      initialState: { sessionBuilder: { isGated: true } },
    })
    await flushPromises()

    expect(builderStore.fetchExistingPlan).toHaveBeenCalledWith('b-1')
    expect(builderStore.fetchPrep).not.toHaveBeenCalled()
    expect(sessionStore.searchDrills).not.toHaveBeenCalled()
  })

  it('a non-gated booking calls fetchPrep and fetchDrills (via searchDrills) in parallel', async () => {
    // fetchPrep never resolves during the assertion: searchDrills must start anyway, which only
    // holds if both are in flight together (Promise.all), not awaited one after the other.
    const prep = deferred()
    const { builderStore, sessionStore } = await mountPage({
      configureStores: ({ builderStore }) => {
        builderStore.fetchPrep.mockImplementation(() => prep.promise)
      },
    })
    await flushPromises()

    expect(builderStore.fetchExistingPlan).toHaveBeenCalledWith('b-1')
    expect(builderStore.fetchPrep).toHaveBeenCalledWith('b-1')
    expect(sessionStore.searchDrills).toHaveBeenCalled()
    prep.resolve()
    // Mutation check (code review 2026-10-27): replace the Promise.all with
    // `await builderStore.fetchPrep(bookingId); await fetchDrills()` -> searchDrills is never called
    // while fetchPrep is pending, RED.
  })
})

// skillars-deferred-165 AC7: the "Save as Template" dialog's name input must carry maxlength=200,
// matching CreateTemplateRequest's backend @Size(max=200).
describe('SessionBuilderPage.vue — Save as Template maxlength (skillars-deferred-165 AC7)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('the "Save as Template" dialog name input carries maxlength=200', async () => {
    const { wrapper } = await mountPage({
      initialState: { sessionBuilder: { sessionId: 'session-1' } },
      shallow: false,
    })
    await flushPromises()

    wrapper.vm.saveAsTemplateDialog = true
    await flushPromises()

    const input = wrapper.find('input[maxlength="200"]')
    expect(input.exists()).toBe(true)
  })
})

// feat/coach-workflow-gaps A19: "Use template" deploys a saved template onto THIS booking, then loads
// the plan the deploy created. Offered only while the booking has no plan yet.
describe('SessionBuilderPage.vue — Use template (feat/coach-workflow-gaps A19)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
    vi.clearAllMocks()
  })

  it('offers "Use template" only while the booking has no plan', async () => {
    const { wrapper } = await mountPage({ shallow: false })
    await flushPromises()
    expect(wrapper.find('[data-test="use-template"]').exists()).toBe(true)

    const { wrapper: planned } = await mountPage({
      initialState: { sessionBuilder: { sessionId: 'session-1' } },
      shallow: false,
    })
    await flushPromises()
    expect(planned.find('[data-test="use-template"]').exists()).toBe(false)
  })

  it('deploys the chosen template onto the current booking and reloads the plan', async () => {
    const { wrapper, builderStore } = await mountPage({
      bookingId: 'b-42',
      initialState: {
        sessionTemplate: { templates: [{ id: 't-1', name: 'Rondo day', drillCount: 3 }] },
      },
      shallow: false,
    })
    await flushPromises()
    const { useSessionTemplateStore } = await import('src/stores/sessionTemplate.store')
    const templateStore = useSessionTemplateStore()
    templateStore.deployTemplate.mockResolvedValue({})
    builderStore.fetchExistingPlan.mockClear()

    await wrapper.vm.useTemplate({ id: 't-1', name: 'Rondo day' })
    await flushPromises()

    expect(templateStore.deployTemplate).toHaveBeenCalledWith('t-1', 'b-42')
    expect(builderStore.fetchExistingPlan).toHaveBeenCalledWith('b-42')
  })
})
