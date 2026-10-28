import { api } from 'src/boot/axios'

// GDPR self-service (FR-TSC-007). Self scope acts on the caller's own account; the players/{id}
// variants let a parent act for a parent-owned child profile that has no account of its own.

export const listMyGdprRequests = () => api.get('/api/gdpr/requests')
export const requestMyExport = () => api.post('/api/gdpr/export')
export const requestMyErasure = () => api.post('/api/gdpr/erasure')

export const listChildGdprRequests = (playerId) => api.get(`/api/gdpr/players/${playerId}/requests`)
export const requestChildExport = (playerId) => api.post(`/api/gdpr/players/${playerId}/export`)
export const requestChildErasure = (playerId) => api.post(`/api/gdpr/players/${playerId}/erasure`)
