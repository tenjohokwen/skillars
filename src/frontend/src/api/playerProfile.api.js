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
}
