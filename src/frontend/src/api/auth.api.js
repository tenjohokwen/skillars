import { api } from 'src/boot/axios'

// SPA authentication: POST /api/auth/login|logout|refresh. The legacy two-step /authenticate -> /otp
// flow and the /api/logout and /v1/account/authenticate calls had no caller in the SPA and were
// removed from this module. /api/auth/refresh was in that list until skillars-deferred-167 gave the
// SPA a reason to call it again — see rotateRefreshToken below.
export const authApi = {
  async skillarsLogin(email, password) {
    return api.post('/api/auth/login', { email, password })
  },

  async skillarsLogout() {
    await api.post('/api/auth/logout')
  },

  /**
   * Rotates the refresh-token chain (and re-issues the access token with it).
   *
   * Distinct from sessionApi.refresh(), which is GET /refresh: that only slides the 15-minute access
   * token's TTL and never touches the refresh chain. skillars-deferred-167 made the chain's life an
   * absolute bound on the session, and because nothing in the SPA rotated it, a web session's chain
   * was the single row created at login — so a continuously active user was hard-logged-out exactly
   * REFRESH_TOKEN_TTL (7 days) after signing in, mid-task and with no way to extend. Rotating on the
   * session-extension path keeps the chain alive for as long as the session genuinely is.
   *
   * Best-effort by design: the caller swallows failures. A 401 here carries errorKey
   * 'security.badCreds', which the axios interceptor deliberately does NOT treat as a session
   * teardown, and nothing consumes the BadCredentialsEvent it publishes, so a failed rotation cannot
   * log the user out or count toward a lockout. It is also NOT rate-limited server-side, which is
   * why this is called on an infrequent path rather than per request.
   */
  async rotateRefreshToken() {
    return api.post('/api/auth/refresh')
  },
}
