import { api } from 'src/boot/axios'

// S-27: Stripe Identity age verification of a self-registered player. Both calls act on the caller only.
export const ageVerificationApi = {
  getStatus() {
    return api.get('/api/security/age-verification')
  },
  // Starts (or resumes) a session; resolves { state, url } — url is the Stripe-hosted page to send the player to.
  start() {
    return api.post('/api/security/age-verification/session')
  },
}
