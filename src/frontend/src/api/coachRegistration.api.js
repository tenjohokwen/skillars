import axios from 'axios'

export const coachRegistrationApi = {
  register(data) {
    return axios.post('/api/security/coach/register', data)
  },
  verifyEmail(token) {
    return axios.get('/api/security/coach/verify-email', { params: { token } })
  },
  verifyPhone(data) {
    return axios.post('/api/security/coach/verify-phone', data)
  },
  resendVerification(email) {
    return axios.post('/api/security/coach/resend-verification', { email })
  },
  // skillars-deferred-92 AC28: the OTP resend. Distinct from resendVerification above, which
  // re-sends the EMAIL verification link. skillars-deferred-89 AC7 shipped this endpoint
  // (permitAll, rate-limited 3/30min per role plus a per-user guard) and nothing ever called
  // it, so a coach whose OTP email was lost had no self-service recovery while a parent did.
  resendOtp(userId) {
    return axios.post('/api/security/coach/resend-otp', { userId })
  },
}
