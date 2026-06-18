import { api } from 'src/boot/axios'

export const homeworkApi = {
  getLockerRoomDrills(playerId) {
    return api.get(`/api/session/players/${playerId}/homework`)
  },
  markComplete(assignmentId) {
    return api.post(`/api/session/homework/${assignmentId}/complete`)
  },
}
