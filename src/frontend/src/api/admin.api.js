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

  // C2: mark an urgent SAFETY_HOLD_BOOKING alert handled (404 if it is no longer open).
  resolveSafetyHoldAlert(alertId) {
    return api.post(`/api/admin/queue/safety-hold-alerts/${alertId}/resolve`)
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

  // payload: { reason, notifyCoach, cancelUpcomingBookings }. Resolves to
  // { newlySuspended, cancelledBookings, alertedBookings } (C2).
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

  // cancelUpcomingBookings only matters for a COACH account (C2): the suspension also takes the
  // coach's marketplace profile down and, by default, cancels + fully refunds their upcoming bookings.
  suspendUser(userId, reason, cancelUpcomingBookings = true) {
    return api.post(`/api/admin/users/${userId}/suspend`, { reason, cancelUpcomingBookings })
  },

  reactivateUser(userId, reason) {
    return api.post(`/api/admin/users/${userId}/reactivate`, { reason })
  },

  unlockUserLogin(userId) {
    return api.post(`/api/admin/users/${userId}/unlock-login`)
  },

  // FR-ADM-004 appeals queue (AdminAppealResource). status: OPEN | UPHELD | OVERTURNED.
  getAppealQueue(status = 'OPEN', page = 0) {
    return api.get('/api/admin/appeals/queue', { params: { status, page } })
  },

  getAppealDetail(appealId) {
    return api.get(`/api/admin/appeals/${appealId}`)
  },

  // payload: { outcome: 'UPHELD' | 'OVERTURNED', note }
  decideAppeal(appealId, payload) {
    return api.post(`/api/admin/appeals/${appealId}/decide`, payload)
  },

  // GDPR request tracker (AdminGdprResource). type: 'EXPORT' | 'ERASURE' | null (all);
  // status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | null (all). Newest first.
  getGdprRequests({ type = null, status = null, page = 0, size = 20 } = {}) {
    return api.get('/api/admin/gdpr/requests', {
      params: {
        type: type || undefined,
        status: status || undefined,
        page,
        size,
        sort: 'createdAt,desc',
      },
    })
  },

  // Finance overview (AdminFinanceResource). from / to: 'YYYY-MM-DD' (UTC, inclusive) or null —
  // the backend defaults to the current month.
  getFinanceOverview(from = null, to = null) {
    return api.get('/api/admin/payment/overview', {
      params: { from: from || undefined, to: to || undefined },
    })
  },

  getCoachRevenue(coachId, from = null, to = null) {
    return api.get(`/api/admin/payment/coaches/${coachId}/revenue`, {
      params: { from: from || undefined, to: to || undefined },
    })
  },

  // Stuck-booking force-complete (AdminBookingResource).
  getAdminBooking(bookingId) {
    return api.get(`/api/admin/bookings/${bookingId}`)
  },

  // Returns { outcome: 'FORCE_COMPLETED' | 'ALREADY_RESOLVED' | 'NOT_STUCK' }.
  forceCompleteBooking(bookingId, note) {
    return api.post(`/api/admin/bookings/${bookingId}/force-complete`, { note })
  },

  // C6: cancel one booking (full refund of anything paid, no coach strike, neutral parent notice).
  // Returns { outcome: 'CANCELLED' | 'LIVE_SESSION' | 'PAYMENT_IN_PROGRESS' | 'NOT_CANCELLABLE' }.
  cancelBookingAsAdmin(bookingId, note) {
    return api.post(`/api/admin/bookings/${bookingId}/cancel`, { note })
  },

  // Video operations (AdminVideoResource). filter: 'ATTENTION' | 'LOCKED' | 'FAILED' | 'BLOCKED'.
  getVideoReviewQueue(filter = 'ATTENTION', page = 0) {
    return api.get('/api/video/admin/videos', { params: { filter, page } })
  },

  getAdminVideo(videoId) {
    return api.get(`/api/video/admin/videos/${videoId}`)
  },

  // newAccessState: 'ACTIVE' | 'BLOCKED' | 'ARCHIVED'
  setVideoAccessState(videoId, newAccessState) {
    return api.patch(`/api/video/admin/videos/${videoId}/access-state`, { newAccessState })
  },

  deleteVideo(videoId) {
    return api.delete(`/api/video/admin/videos/${videoId}`)
  },

  getVideoUploadSessions(videoId) {
    return api.get(`/api/video/admin/videos/${videoId}/sessions`)
  },

  reconcileVideo(videoId) {
    return api.post(`/api/video/admin/videos/${videoId}/reconcile`)
  },

  // Platform config editor (ConfigResource).
  getConfigValues() {
    return api.get('/api/config/values')
  },

  updateConfigValue(key, value) {
    return api.put(`/api/config/values/${encodeURIComponent(key)}`, { value })
  },
}
