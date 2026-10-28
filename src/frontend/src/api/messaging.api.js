import { api } from 'src/boot/axios'

// Idempotent on the backend (unique (coach_id, player_id) + re-query on conflict): calling it for an
// existing pair returns that conversation. `coachId` may be omitted by a COACH caller — the backend
// then defaults it to the caller's own coach profile (roster / player-detail entry points know only
// the player). `playerId` is always a player-PROFILE id.
export const initiateConversation = (coachId, playerId) =>
  api.post('/api/messaging/conversations', coachId ? { coachId, playerId } : { playerId })

export const fetchConversations = () => api.get('/api/messaging/conversations')

export const sendMessage = (conversationId, content) =>
  api.post(`/api/messaging/conversations/${conversationId}/messages`, { content })

export const fetchMessages = (conversationId, page = 0, size = 20) =>
  api.get(`/api/messaging/conversations/${conversationId}/messages`, {
    params: { page, size },
  })

export const subscribeToEvents = (conversationId) =>
  new EventSource(`/api/messaging/conversations/${conversationId}/events`, {
    withCredentials: true,
  })

export const fetchPlayerConversations = (playerId) =>
  api.get(`/api/messaging/players/${playerId}/conversations`)

export const fetchPlayerConversationMessages = (playerId, conversationId, page = 0, size = 20) =>
  api.get(`/api/messaging/players/${playerId}/conversations/${conversationId}/messages`, {
    params: { page, size },
  })

// skillars-deferred-165 AC6: delete-own-message and report-message/report-conversation. `role` is
// optional on the backend (MessagingResource#resolveRole) — only needed to disambiguate a caller
// who holds more than one messaging-eligible role; omit it when the caller is unambiguous.
export const softDeleteMessage = (conversationId, messageId) =>
  api.delete(`/api/messaging/conversations/${conversationId}/messages/${messageId}`)

export const reportMessage = (conversationId, messageId, payload, role) =>
  api.post(`/api/messaging/conversations/${conversationId}/messages/${messageId}/report`, payload, {
    params: role ? { role } : {},
  })

export const reportConversation = (conversationId, payload, role) =>
  api.post(`/api/messaging/conversations/${conversationId}/report`, payload, {
    params: role ? { role } : {},
  })
