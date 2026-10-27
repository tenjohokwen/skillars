import { api } from 'src/boot/axios'

export const raiseDispute = (bookingId, reason, details) =>
  api.post('/api/disputes', { bookingId, reason, details })

export const getDispute = (disputeId) => api.get(`/api/disputes/${disputeId}`)
