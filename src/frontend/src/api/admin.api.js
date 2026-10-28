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

  // Moderation queue (AdminModerationResource). Only status=OPEN is supported by the backend.
  // type: 'ALL' or an AdminAlertType name.
  getModerationQueue(type = 'ALL', page = 0) {
    return api.get('/api/admin/queue', { params: { type, page } })
  },

  getModerationSummary() {
    return api.get('/api/admin/queue/summary')
  },

  getMessageDetail(messageId) {
    return api.get(`/api/admin/messages/${messageId}`)
  },

  approveMessage(messageId) {
    return api.post(`/api/admin/messages/${messageId}/approve`)
  },

  blockMessage(messageId, reason) {
    return api.post(`/api/admin/messages/${messageId}/block`, { reason })
  },

  getConversationDetail(conversationId) {
    return api.get(`/api/admin/conversations/${conversationId}`)
  },

  unblockConversation(conversationId) {
    return api.post(`/api/admin/conversations/${conversationId}/unblock`)
  },

  // Review moderation (AdminReviewResource). Only status=UNDER_REVIEW is supported.
  getReviewQueue(page = 0) {
    return api.get('/api/admin/reviews/queue', { params: { page } })
  },

  approveReview(reviewId) {
    return api.post(`/api/admin/reviews/${reviewId}/approve`)
  },

  blockReview(reviewId, reason) {
    return api.post(`/api/admin/reviews/${reviewId}/block`, { reason })
  },

  // Coach enforcement (AdminCoachEnforcementResource). status: 'ALL' (= PENDING_REVIEW +
  // SUSPENDED) or a single CoachProfileStatus name.
  getEnforcementCoaches(status = 'ALL', page = 0) {
    return api.get('/api/admin/coaches', { params: { status, page } })
  },

  getCoachEnforcementProfile(coachId) {
    return api.get(`/api/admin/coaches/${coachId}/enforcement`)
  },

  suspendCoach(coachId, payload) {
    return api.post(`/api/admin/coaches/${coachId}/suspend`, payload)
  },

  reinstateCoach(coachId, payload) {
    return api.post(`/api/admin/coaches/${coachId}/reinstate`, payload)
  },

  issueCoachStrike(coachId, payload) {
    return api.post(`/api/admin/coaches/${coachId}/strikes`, payload)
  },

  deleteCoachStrike(coachId, strikeId, reason) {
    return api.delete(`/api/admin/coaches/${coachId}/strikes/${strikeId}`, { params: { reason } })
  },

  setCoachVerificationTier(coachId, payload) {
    return api.patch(`/api/admin/coaches/${coachId}/verification-tier`, payload)
  },

  // Account enforcement (AdminUserResource).
  searchUsers(q = '', page = 0) {
    return api.get('/api/admin/users', { params: { q, page } })
  },

  getUser(userId) {
    return api.get(`/api/admin/users/${userId}`)
  },

  suspendUser(userId, reason) {
    return api.post(`/api/admin/users/${userId}/suspend`, { reason })
  },

  reactivateUser(userId, reason) {
    return api.post(`/api/admin/users/${userId}/reactivate`, { reason })
  },

  unlockUserLogin(userId) {
    return api.post(`/api/admin/users/${userId}/unlock-login`)
  },
}
