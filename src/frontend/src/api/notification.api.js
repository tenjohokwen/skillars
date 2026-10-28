import { api } from 'src/boot/axios'

// Notification centre (UI-gap chunk C9). The axios response interceptor already unwraps
// `response.data`, so every method resolves to the response body itself — never `.data` again.
export const notificationApi = {
  list(page = 0, size = 20) {
    return api.get('/api/notifications', { params: { page, size } })
  },

  unreadCount() {
    return api.get('/api/notifications/unread-count')
  },

  markRead(id) {
    return api.post(`/api/notifications/${id}/read`)
  },

  markAllRead() {
    return api.post('/api/notifications/read-all')
  },

  getPreferences() {
    return api.get('/api/notifications/preferences')
  },

  updatePreference(category, emailEnabled) {
    return api.put(`/api/notifications/preferences/${category}`, { emailEnabled })
  },

  // Public (no login): the signed token is the credential. POST only — the backend has no GET
  // route, so a link scanner prefetching the email link can never unsubscribe anyone.
  unsubscribe(token) {
    return api.post('/api/notifications/unsubscribe', null, { params: { token } })
  },
}
