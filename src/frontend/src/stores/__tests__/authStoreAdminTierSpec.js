// S-28: the auth store carries the limited-admin UI hint (login response + skp cookie).
import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useAuthStore } from 'src/stores/auth.store'
import { useAdminTier } from 'src/composables/useAdminTier'

function setSkp(payload) {
  document.cookie = `skp=${encodeURIComponent(JSON.stringify(payload))}; path=/`
}

describe('auth store - admin tier (S-28)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    document.cookie = 'skp=; Max-Age=0; path=/'
  })

  it('setUser records limitedAdmin from the login response and clearUser resets it', () => {
    const store = useAuthStore()
    store.setUser({ userId: '1', role: 'ADMIN', displayName: 'A', limitedAdmin: true })
    expect(store.isAdmin).toBe(true)
    expect(store.limitedAdmin).toBe(true)
    expect(store.isFullAdmin).toBe(false)

    store.clearUser()
    expect(store.limitedAdmin).toBe(false)
  })

  it('a plain ADMIN login is a full admin', () => {
    const store = useAuthStore()
    store.setUser({ userId: '1', role: 'ADMIN', displayName: 'A' })
    expect(store.limitedAdmin).toBe(false)
    expect(store.isFullAdmin).toBe(true)
  })

  it('hydrateFromCookie reads the limitedAdmin flag from skp', () => {
    setSkp({ id: '7', role: 'ADMIN', limitedAdmin: true })
    const store = useAuthStore()
    store.hydrateFromCookie()
    expect(store.limitedAdmin).toBe(true)
  })

  it('useAdminTier exposes the flag, and is full-admin when no Pinia is active', () => {
    const store = useAuthStore()
    store.setUser({ userId: '1', role: 'ADMIN', limitedAdmin: true })
    expect(useAdminTier().isLimitedAdmin.value).toBe(true)
    store.setUser({ userId: '1', role: 'ADMIN' })
    expect(useAdminTier().isLimitedAdmin.value).toBe(false)
  })
})
