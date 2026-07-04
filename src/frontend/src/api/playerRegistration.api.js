import { api } from 'src/boot/axios'

export const playerRegistrationApi = {
  register(data) {
    return api.post('/api/security/player/register', data)
  },
  verifyEmail(token) {
    return api.get('/api/security/player/verify-email', { params: { token } })
  },
  verifyPhone(data) {
    return api.post('/api/security/player/verify-phone', data)
  },
  resendVerification(email) {
    return api.post('/api/security/player/resend-verification', { email })
  },
  createProfile(data) {
    return api.post('/api/security/players/me', data)
  },
  getMyProfile() {
    return api.get('/api/security/players/me')
  },
}
