import axios from 'axios'

export const playerProfileApi = {
  async createProfile(data) {
    const res = await axios.post('/api/security/players', data)
    return res.data
  },
  async listProfiles() {
    const res = await axios.get('/api/security/players')
    return res.data
  },
  linkParent(playerId) {
    return axios.post(`/api/security/players/${playerId}/link-parent`)
  },
  // AC4: the missing update counterpart to createProfile's "me" create — createSelfOwnedPlayerProfile
  // is one-time-only server-side, so "My Profile" needs this separate route back to the field.
  async updateOwnPosition(position) {
    const res = await axios.patch('/api/security/players/me/position', { position })
    return res.data
  },
  // AC5: a parent editing a specific child's position, reusing the same ownership-guarded route.
  async updateChildPosition(playerId, position) {
    const res = await axios.patch(`/api/security/players/${playerId}/position`, { position })
    return res.data
  },
}
