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

export const getRadarDisplay = (playerId) =>
  api.get(`/api/development/players/${playerId}/radar/display`)

export const getRadarPreferences = (playerId) =>
  api.get(`/api/development/players/${playerId}/radar/preferences`)

export const putRadarPreferences = (playerId, selectedSkillCodes) =>
  api.put(`/api/development/players/${playerId}/radar/preferences`, { selectedSkillCodes })

export const getCorrelationInsights = (playerId) =>
  api.get(`/api/development/players/${playerId}/radar/correlation`)

export const generateReport = (playerId, nextSteps) =>
  api.post(`/api/development/players/${playerId}/reports`, { nextSteps })

export const listReports = (playerId) =>
  api.get(`/api/development/players/${playerId}/reports`)

export const getTimeline = (playerId) =>
  api.get(`/api/development/players/${playerId}/timeline`)

export const getCoachBranding = () =>
  api.get('/api/development/coaches/me/branding')

export const saveCoachBranding = (logoKey, brandColour) =>
  api.put('/api/development/coaches/me/branding', { logoKey, brandColour })

export const getCoachContributions = (playerId, days = 30) =>
  api.get(`/api/development/players/${playerId}/slu/coach-contributions`, { params: { days } })
