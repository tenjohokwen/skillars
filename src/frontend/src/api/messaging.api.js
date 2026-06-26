import { api } from 'src/boot/axios'

export const initiateConversation = (coachId, playerId) =>
  api.post('/api/messaging/conversations', { coachId, playerId })

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
  api.get(`/api/messaging/players/${playerId}/conversations`).then((r) => r.data)

export const fetchPlayerConversationMessages = (playerId, conversationId, page = 0, size = 20) =>
  api
    .get(`/api/messaging/players/${playerId}/conversations/${conversationId}/messages`, {
      params: { page, size },
    })
    .then((r) => r.data)
