import { api } from 'src/boot/axios'

export const getSkillDefinitions = () => api.get('/api/development/skill-definitions')

export const getSkillExposure = (playerId, weeks = 8) =>
  api.get(`/api/development/players/${playerId}/exposure`, { params: { weeks } })

export const getNarrativeSummary = (playerId) =>
  api.get(`/api/development/players/${playerId}/narrative`)

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

// skillars-deferred-163 AC4.1: the pagehide flush needs a request that survives page teardown —
// a normal axios PUT can be cancelled mid-flight by the browser on unload. `fetch(..., {keepalive:
// true})` is the only API that promises this; `navigator.sendBeacon` cannot be used (it only ever
// sends POST, and this endpoint is @PutMapping). Deliberately bypasses the shared axios instance:
// its interceptors (loading spinner, 401 teardown, etc.) make no sense for a fire-and-forget call
// issued as the page is being torn down. Auth is cookie-based and CSRF is disabled for this app
// (boot/axios.js), so `credentials: 'include'` is the only auth-equivalent needed.
export const putRadarPreferencesKeepAlive = (playerId, selectedSkillCodes) =>
  fetch(`/api/development/players/${playerId}/radar/preferences`, {
    method: 'PUT',
    keepalive: true,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ selectedSkillCodes }),
  })

export const getCorrelationInsights = (playerId) =>
  api.get(`/api/development/players/${playerId}/radar/correlation`)

export const generateReport = (playerId, nextSteps) =>
  api.post(`/api/development/players/${playerId}/reports`, { nextSteps })

export const listReports = (playerId) => api.get(`/api/development/players/${playerId}/reports`)

export const getTimeline = (playerId, page = 0, size = 50) =>
  api.get(`/api/development/players/${playerId}/timeline`, { params: { page, size } })

export const getCoachBranding = () => api.get('/api/development/coaches/me/branding')

export const saveCoachBranding = (logoKey, brandColour) =>
  api.put('/api/development/coaches/me/branding', { logoKey, brandColour })

export const getCoachContributions = (playerId, days = 30) =>
  api.get(`/api/development/players/${playerId}/slu/coach-contributions`, { params: { days } })

// skillars-deferred-98 AC1b: per-skill weekly SLU trend (improving / flat / declining), independent
// of the neglected-skill gating signal. Backend: SkillExposureResource#getSkillTrends.
export const getSkillTrends = (playerId, weeks = 8) =>
  api.get(`/api/development/players/${playerId}/slu/skill-trends`, { params: { weeks } })

// skillars-12-1 AC1: the coach's own attention-ranked player roster.
export const getCoachRoster = () => api.get('/api/development/coaches/me/roster')

// skillars-12-3 AC3: the Session Prep Card's backing endpoint.
export const getSessionPrep = (bookingId) => api.get(`/api/development/bookings/${bookingId}/prep`)

// skillars-12-9 AC7 (D12.9-A): open neglect flags (skillCode + detectedAt) for the coach page.
export const getNeglectedSkills = (playerId) =>
  api.get(`/api/development/players/${playerId}/neglected-skills`)
