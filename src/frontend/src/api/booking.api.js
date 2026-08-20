import { api } from 'src/boot/axios'

export const getCoachAvailability = (coachId, weekStart) =>
  api.get(`/api/bookings/coaches/${coachId}/availability`, { params: { weekStart } })

export const addAvailabilityWindow = (data) =>
  api.post('/api/bookings/coaches/me/availability/windows', data)

export const updateAvailabilityWindow = (id, data) =>
  api.put(`/api/bookings/coaches/me/availability/windows/${id}`, data)

export const deleteAvailabilityWindow = (id) =>
  api.delete(`/api/bookings/coaches/me/availability/windows/${id}`)

export const addAvailabilityBlock = (data) =>
  api.post('/api/bookings/coaches/me/availability/blocks', data)

export const deleteAvailabilityBlock = (id) =>
  api.delete(`/api/bookings/coaches/me/availability/blocks/${id}`)

export const createBookingRequest = (request) => api.post('/api/bookings/requests', request)

// skillars-deferred-40: scoped to these calls only (not the shared `api` instance) — matches
// getCoachBookingRequests's 20s timeout precedent. `undefined` is passed as the data arg so the
// config object lands in the config slot, not the request-body slot.
export const acceptBooking = (id) =>
  api.put(`/api/bookings/requests/${id}/accept`, undefined, { timeout: 20000 })

export const declineBooking = (id) =>
  api.put(`/api/bookings/requests/${id}/decline`, undefined, { timeout: 20000 })

export const getParentBookings = () => api.get('/api/bookings/requests')

export const getBookingRequestConfig = () => api.get('/api/bookings/requests/config')

// skillars-deferred-39: scoped to this call only (not the shared `api` instance) — 20s is generous
// enough to never fire under normal backend latency while still bounding a genuinely hung request.
export const getCoachBookingRequests = () =>
  api.get('/api/bookings/requests/coach', { timeout: 20000 })

export const getBookingById = (id) => api.get(`/api/bookings/${id}`)

export const getCoachSchedule = (weekStart) =>
  api.get('/api/bookings/coaches/me/schedule', { params: { weekStart } })

export const getParentSchedule = (playerId) =>
  api.get('/api/bookings/parents/me/schedule', { params: { playerId } })

export const startSession = (id) => api.post(`/api/bookings/${id}/start`)

export const endSession = (id) => api.post(`/api/bookings/${id}/end`)

export const pauseSession = (id) => api.post(`/api/bookings/${id}/pause`)

export const resumeSession = (id) => api.post(`/api/bookings/${id}/resume`)

export const submitWrapUp = (id, data) => api.post(`/api/bookings/${id}/complete`, data)

export const initiateQuickComplete = (id) => api.post(`/api/bookings/${id}/quick-complete`)

export const confirmCompletion = (id) => api.put(`/api/bookings/${id}/confirm-completion`)

export const getDrillSuggestions = (bookingId) =>
  api.get(`/api/bookings/session/${bookingId}/drills/suggestions`, { params: { limit: 2 } })

export const requestReschedule = (id, data) => api.post(`/api/bookings/${id}/reschedule`, data)
export const acceptReschedule = (id, rescheduleId) => api.put(`/api/bookings/${id}/reschedule/${rescheduleId}/accept`)
export const declineReschedule = (id, rescheduleId) => api.put(`/api/bookings/${id}/reschedule/${rescheduleId}/decline`)
export const duplicateNextWeek = (id) => api.post(`/api/bookings/${id}/duplicate-next-week`)
export const getBatchConfig = () => api.get('/api/bookings/batches/config')
export const createBatch = (data) => api.post('/api/bookings/batches', data)
// skillars-deferred-40: scoped to this call only, same rationale as acceptBooking/declineBooking above.
export const acceptAllBatch = (batchId) =>
  api.post(`/api/bookings/batches/${batchId}/accept-all`, undefined, { timeout: 20000 })

export const cancelBooking = (bookingId) => api.post(`/api/bookings/${bookingId}/cancel`)
export const coachCancelBooking = (bookingId, cancelReason) =>
  api.post(`/api/bookings/${bookingId}/coach-cancel`, { cancelReason })
export const recordNoShowPlayer = (bookingId) => api.post(`/api/bookings/${bookingId}/no-show-player`)
export const recordNoShowCoach = (bookingId) => api.post(`/api/bookings/${bookingId}/no-show-coach`)
