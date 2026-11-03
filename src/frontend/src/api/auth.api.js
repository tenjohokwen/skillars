import { api } from 'src/boot/axios'

// SPA authentication: POST /api/auth/login|logout. The legacy two-step /authenticate -> /otp flow
// and the /api/logout, /api/auth/refresh and /v1/account/authenticate calls had no caller in the
// SPA and were removed from this module.
export const authApi = {
  async skillarsLogin(email, password) {
    return api.post('/api/auth/login', { email, password })
  },

  async skillarsLogout() {
    await api.post('/api/auth/logout')
  },
}
