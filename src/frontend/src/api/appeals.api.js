import { api } from 'src/boot/axios'

// FR-ADM-004 appeals, user side: one appeal per enforcement action, within 14 days of it.
export const submitAppeal = (payload) => api.post('/api/appeals', payload)
export const listMyAppeals = () => api.get('/api/appeals/me')
