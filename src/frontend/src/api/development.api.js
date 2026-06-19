import { api } from 'src/boot/axios'

export const getSkillDefinitions = () => api.get('/api/development/skill-definitions')

export const getSkillExposure = (playerId, weeks = 8) =>
  api.get(`/api/development/players/${playerId}/exposure`, { params: { weeks } })

export const getNarrativeSummary = (playerId) =>
  api.get(`/api/development/players/${playerId}/narrative`)

export const getNeglectedSkills = (playerId) =>
  api.get(`/api/development/players/${playerId}/neglected-skills`)

export const getMyTargets = (playerId) => api.get(`/api/development/players/${playerId}/targets`)

export const setMyTargets = (playerId, targets) =>
  api.put(`/api/development/players/${playerId}/targets`, targets)

export const postRadarAssessment = (playerId, assessment) =>
  api.post(`/api/development/players/${playerId}/radar/entries`, assessment)

export const getMyRadarEntries = (playerId) =>
  api.get(`/api/development/players/${playerId}/radar/entries`)
