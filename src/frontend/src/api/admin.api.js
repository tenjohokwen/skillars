import { api } from 'src/boot/axios'

export const adminApi = {
  /**
   * Get the Spring Boot Actuator health response.
   * Admin JWT required to see component details (components field absent for non-admin).
   * Returns: { status: 'UP'|'DOWN', components?: { [name]: { status, details? } } }
   */
  getHealth() {
    // Actuator is on a different port (8367) than the main app (9990).
    const actuatorBase = `${window.location.protocol}//${window.location.hostname}:8367`
    return api.get(`${actuatorBase}/manage/health`)
  },

  // skillars-deferred-165 AC1/AC3: admin disputes worklist, mirroring AdminModerationResource's
  // own /queue shape. Only status=OPEN is supported by the backend in this version.
  getDisputeQueue(page = 0) {
    return api.get('/api/admin/disputes/queue', { params: { page } })
  },

  getDisputeDetail(disputeId) {
    return api.get(`/api/admin/disputes/${disputeId}`)
  },

  resolveDispute(disputeId, payload) {
    return api.post(`/api/admin/disputes/${disputeId}/resolve`, payload)
  },

  dismissDispute(disputeId, payload) {
    return api.post(`/api/admin/disputes/${disputeId}/dismiss`, payload)
  },
}
