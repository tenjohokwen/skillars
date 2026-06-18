import { api } from 'src/boot/axios'

export const sessionApi = {
  refresh() {
    return api.get('/refresh')
  },

  getDrills(library, params = {}) {
    return api.get('/api/session/drills', { params: { ...params, library } })
  },

  cloneDrill(drillId) {
    return api.post(`/api/session/drills/${drillId}/clone`)
  },

  addTag(drillId, tag) {
    return api.post(`/api/session/drills/${drillId}/tags`, { tag })
  },

  removeTag(drillId, tag) {
    return api.delete(`/api/session/drills/${drillId}/tags/${encodeURIComponent(tag)}`)
  },

  getTagSuggestions() {
    return api.get('/api/session/drills/tags/suggestions')
  },

  checkVideoUploadEligibility() {
    return api.get('/api/session/drills/video-upload/eligible')
  },

  initiateVideoUpload(drillId, payload) {
    return api.post(`/api/session/drills/${drillId}/video/initiate`, payload)
  },

  deleteVideo(drillId) {
    return api.delete(`/api/session/drills/${drillId}/video`)
  },

  createSessionPlan(payload) {
    return api.post('/api/session/sessions', payload)
  },

  updateSessionPlan(sessionId, payload) {
    return api.put(`/api/session/sessions/${sessionId}`, payload)
  },

  getSessionPlan(sessionId) {
    return api.get(`/api/session/sessions/${sessionId}`)
  },

  getSessionPlanByBooking(bookingId) {
    return api.get(`/api/session/sessions/by-booking/${bookingId}`)
  },

  getSuggestions(sessionId, limit = 10) {
    return api.get('/api/session/drills/suggestions', { params: { sessionId, limit } })
  },

  listTemplates() {
    return api.get('/api/session/templates')
  },

  createTemplate(payload) {
    return api.post('/api/session/templates', payload)
  },

  renameTemplate(templateId, payload) {
    return api.put(`/api/session/templates/${templateId}`, payload)
  },

  deleteTemplate(templateId) {
    return api.delete(`/api/session/templates/${templateId}`)
  },

  deployTemplate(templateId, bookingId) {
    return api.post(`/api/session/templates/${templateId}/deploy`, null, { params: { bookingId } })
  },
}
