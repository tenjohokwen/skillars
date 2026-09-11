// skillars-deferred-108 AC4 — playerStore.js self-id cache (closes deferred-43 D-frontend, test-coverage half).
//
// Ledger (deferred-work.md:1001): "No automated test coverage added for playerStore.js's new
// fetchSelfPlayerId()/resetSelfPlayerId() caching logic … the cache carrying a cross-account
// booking-misattribution risk."
//
// Real Pinia store (setActivePinia + createPinia) — selfPlayerIdRequest / selfPlayerIdGeneration
// live inside the setup fn, so a fresh createPinia() per test is real isolation.
//
// Mutation check (run both ways, see Dev Agent Record): remove the
//   `requestGeneration === selfPlayerIdGeneration`
// guard on the write at playerStore.js:39 → the "cached ref stays null after resetSelfPlayerId"
// test fails (the superseded request's id lands in selfPlayerId.value).
//
// skillars-deferred-109 AC4.1: the "superseded call's returned promise" residual is now FIXED —
// the return is generation-gated (return null when superseded), and the former characterization
// test below is flipped to assert `null` while driving the B-repopulates-the-ref sequence so it
// also catches a `return selfPlayerId.value` regression.
// AC4.2 adds a real getMyProfile() rejection case (the documented 404) — previously every failure
// path went through `{ id: null }`, which takes the .then-throws branch, not a rejected promise.

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

vi.mock('src/api/playerRegistration.api', () => ({
  playerRegistrationApi: { getMyProfile: vi.fn() },
}))
vi.mock('src/api/playerProfile.api', () => ({
  playerProfileApi: { listProfiles: vi.fn() },
}))

import { playerRegistrationApi } from 'src/api/playerRegistration.api'
import { usePlayerStore } from 'src/stores/playerStore'

const getMyProfile = playerRegistrationApi.getMyProfile

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('playerStore — self player-id cache (deferred-108 AC4)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('cache hit: a second fetchSelfPlayerId() returns the id without a second API call', async () => {
    const store = usePlayerStore()
    getMyProfile.mockResolvedValue({ id: 7 })

    expect(await store.fetchSelfPlayerId()).toBe(7)
    expect(await store.fetchSelfPlayerId()).toBe(7)
    expect(getMyProfile).toHaveBeenCalledTimes(1)
    expect(store.selfPlayerId).toBe(7)
  })

  it('in-flight de-dup: two concurrent calls share one getMyProfile', async () => {
    const store = usePlayerStore()
    const d = deferred()
    getMyProfile.mockReturnValue(d.promise)

    const p1 = store.fetchSelfPlayerId()
    const p2 = store.fetchSelfPlayerId()
    d.resolve({ id: 12 })

    expect(await p1).toBe(12)
    expect(await p2).toBe(12)
    expect(getMyProfile).toHaveBeenCalledTimes(1)
  })

  it('cross-account guard (cached ref): resetSelfPlayerId() before a slow resolve keeps selfPlayerId null', async () => {
    const store = usePlayerStore()
    const d1 = deferred()
    getMyProfile.mockReturnValueOnce(d1.promise)

    const p1 = store.fetchSelfPlayerId() // request in flight, generation captured
    store.resetSelfPlayerId() // generation bumped
    d1.resolve({ id: 999 }) // a DIFFERENT player's id lands late
    await p1

    expect(store.selfPlayerId).toBeNull()

    // A subsequent call starts a fresh request rather than trusting the stale one.
    const d2 = deferred()
    getMyProfile.mockReturnValueOnce(d2.promise)
    const p2 = store.fetchSelfPlayerId()
    d2.resolve({ id: 5 })
    await p2

    expect(getMyProfile).toHaveBeenCalledTimes(2)
    expect(store.selfPlayerId).toBe(5)
  })

  it('cross-account guard (returned promise): a superseded call resolves with exactly null (deferred-109 AC4.1)', async () => {
    const store = usePlayerStore()
    const d1 = deferred()
    const d2 = deferred()
    getMyProfile.mockReturnValueOnce(d1.promise).mockReturnValueOnce(d2.promise)

    const p1 = store.fetchSelfPlayerId() // account A's call, generation captured
    store.resetSelfPlayerId() // account A logs out → generation bumped

    // Account B logs in and resolves FIRST, repopulating selfPlayerId.value with B's id.
    const p2 = store.fetchSelfPlayerId()
    d2.resolve({ id: 5 })
    await p2
    expect(store.selfPlayerId).toBe(5)

    // Now account A's slow chain finally settles. It must NOT resolve with 999 (A's stale id) and
    // must NOT resolve with 5 (B's id, which `return selfPlayerId.value` would leak) — only null.
    d1.resolve({ id: 999 })
    await expect(p1).resolves.toBeNull()
    expect(store.selfPlayerId).toBe(5) // B's write is untouched
    // Mutation: restore `return profile.id` → p1 resolves to 999 → RED.
    // Mutation: change the fix to `: selfPlayerId.value` → p1 resolves to 5 → RED.
  })

  it('reject path: a real getMyProfile() rejection rejects fetchSelfPlayerId and clears the in-flight ref (deferred-109 AC4.2)', async () => {
    const store = usePlayerStore()
    // A genuine rejection (network error / 404) — skips .then entirely, only .finally runs.
    getMyProfile.mockRejectedValueOnce(
      Object.assign(new Error('not found'), { response: { status: 404 } }),
    )

    await expect(store.fetchSelfPlayerId()).rejects.toThrow(/not found/i)
    expect(store.selfPlayerId).toBeNull()

    // The .finally must have cleared selfPlayerIdRequest, so the next call issues a FRESH request
    // rather than returning the rejected promise forever.
    getMyProfile.mockResolvedValueOnce({ id: 8 })
    expect(await store.fetchSelfPlayerId()).toBe(8)
    expect(getMyProfile).toHaveBeenCalledTimes(2)
    // Mutation: delete `if (selfPlayerIdRequest === request) selfPlayerIdRequest = null` at
    // playerStore.js:53 → the rejected promise stays pinned, the retry never fires → RED.
  })

  it('finally-reference safety: a stale request settling does not null the newer in-flight request', async () => {
    const store = usePlayerStore()
    const d1 = deferred()
    const d2 = deferred()
    getMyProfile.mockReturnValueOnce(d1.promise).mockReturnValueOnce(d2.promise)

    const pA = store.fetchSelfPlayerId() // request A
    store.resetSelfPlayerId() // clears selfPlayerIdRequest out of band, generation++
    const pB = store.fetchSelfPlayerId() // request B installed

    d1.resolve({ id: 111 }) // A settles; its .finally must not null request B
    await pA

    // B is still in flight and selfPlayerId is still null → this call must reuse B, not start a 3rd.
    const pB2 = store.fetchSelfPlayerId()
    expect(getMyProfile).toHaveBeenCalledTimes(2)

    d2.resolve({ id: 222 })
    expect(await pB).toBe(222)
    expect(await pB2).toBe(222)
    expect(store.selfPlayerId).toBe(222)
  })

  it('id-null rejection: a profile with no id rejects fetchSelfPlayerId()', async () => {
    const store = usePlayerStore()
    getMyProfile.mockResolvedValueOnce({ id: null })

    await expect(store.fetchSelfPlayerId()).rejects.toThrow(/no id/i)
    expect(store.selfPlayerId).toBeNull()
  })
})
