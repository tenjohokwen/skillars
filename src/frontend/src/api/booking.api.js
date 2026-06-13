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

export const getPlayerPacks = (playerId, coachId) =>
  api.get(`/api/bookings/players/${playerId}/packs`, { params: coachId ? { coachId } : {} })

export const purchaseSessionPack = (playerId, request) =>
  api.post(`/api/bookings/players/${playerId}/packs/purchase`, request)
