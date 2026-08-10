import { api } from 'src/boot/axios'

export const getProfileBuilderStatus = () =>
  api.get('/api/marketplace/coaches/me/profile/status')

export const saveProfileBuilderStep = (stepNumber, data) =>
  api.put(`/api/marketplace/coaches/me/profile/steps/${stepNumber}`, data)

// The timezone options the profile builder may offer. Sourced from the SERVER's zone set, never
// from Intl.supportedValuesOf(): the browser's tzdata is exactly what used to lock a coach out of
// Steps 1 and 4 when it was newer than the deployed JVM's.
export const getSupportedTimezones = () =>
  api.get('/api/marketplace/coaches/me/profile/timezones')

export const publishProfile = () =>
  api.post('/api/marketplace/coaches/me/profile/publish')

export const searchCoaches = (params = {}) =>
  api.get('/api/marketplace/coaches', { params })

export const getCoachProfile = (coachId) =>
  api.get(`/api/marketplace/coaches/${coachId}`)

export const signUpload = (payload) =>
  api.post('/api/storage/sign/upload', payload)

export const confirmUpload = (key, payload) =>
  api.post(`/api/storage/confirm/${key}`, payload)

export const sanitizePreview = (text, signal) =>
  api.post('/api/util/sanitize-preview', { text }, { signal })
