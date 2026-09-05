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
  // skillars-deferred-92 AC28: the OTP resend. Distinct from resendVerification above, which
  // re-sends the EMAIL verification link. skillars-deferred-89 AC7 shipped this endpoint
  // (permitAll, rate-limited 3/30min per role plus a per-user guard) and nothing ever called
  // it, so a player whose OTP email was lost had no self-service recovery while a parent did.
  resendOtp(userId) {
    return api.post('/api/security/player/resend-otp', { userId })
  },
  createProfile(data) {
    return api.post('/api/security/players/me', data)
  },
  getMyProfile() {
    return api.get('/api/security/players/me')
  },
}
