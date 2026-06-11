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
}
