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
// The "superseded call's returned promise" test is a CHARACTERIZATION test: playerStore.js:46
// returns `profile.id` unconditionally, so the promise still resolves with the other account's
// id even when the cached-ref write is suppressed. That residual is filed as a new ledger bullet
// under `## Deferred from: skillars-deferred-108` (AC10) — not fixed here.

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

  it('cross-account residual (returned promise): a superseded call still resolves with the other id', async () => {
    // Characterization only — playerStore.js:46 returns profile.id unconditionally. Filed as a new
    // ledger bullet by AC10; NOT fixed here.
    const store = usePlayerStore()
    const d1 = deferred()
    getMyProfile.mockReturnValueOnce(d1.promise)

    const p1 = store.fetchSelfPlayerId()
    store.resetSelfPlayerId()
    d1.resolve({ id: 999 })

    // Only the return value is characterized here — by design this assertion holds with or
    // without the generation guard (the guard gates the cached-ref write, not the return).
    await expect(p1).resolves.toBe(999)
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
