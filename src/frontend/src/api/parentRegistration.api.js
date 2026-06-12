import axios from 'axios'

export const parentRegistrationApi = {
  register(data) {
    return axios.post('/api/security/parent/register', data)
  },
  verifyEmail(token) {
    return axios.get('/api/security/parent/verify-email', { params: { token } })
  },
  verifyPhone(data) {
    return axios.post('/api/security/parent/verify-phone', data)
  },
  resendVerification(email) {
    return axios.post('/api/security/parent/resend-verification', { email })
  },
  resendOtp(userId) {
    return axios.post('/api/security/parent/resend-otp', { userId })
  },
}
