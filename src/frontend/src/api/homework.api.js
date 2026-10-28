import { api } from 'src/boot/axios'

export const homeworkApi = {
  getLockerRoomDrills(playerId) {
    return api.get(`/api/session/players/${playerId}/homework`)
  },
  markComplete(assignmentId) {
    return api.post(`/api/session/homework/${assignmentId}/complete`)
  },
  // Coach view: the homework THIS coach assigned the player, with completion state.
  getCoachPlayerHomework(playerId) {
    return api.get(`/api/session/coach/players/${playerId}/homework`)
  },
}
