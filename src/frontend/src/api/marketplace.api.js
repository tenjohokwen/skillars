import { api } from 'src/boot/axios'

export const getProfileBuilderStatus = () => api.get('/api/marketplace/coaches/me/profile/status')

// skillars-deferred-163 AC4.3: moved out of auth.store.js's direct api.get call (it bypassed this
// project's centralized-API-call convention) and given the same per-call timeout shape as
// booking.api.js's precedents, so a hung request settles instead of leaving the caller's
// tierSettled gate stuck forever.
export const getCoachTier = () => api.get('/api/marketplace/coaches/me/tier', { timeout: 20000 })

// AC1: the coach's own full profile (every builder-collected field, works pre-publish) — for "My
// Profile"'s edit dialogs. Distinct from getProfileBuilderStatus (no field values) and
// getCoachProfile below (public view, 404s for DRAFT, omits several builder-only fields).
export const getOwnCoachProfile = () => api.get('/api/marketplace/coaches/me/profile')

export const saveProfileBuilderStep = (stepNumber, data) =>
  api.put(`/api/marketplace/coaches/me/profile/steps/${stepNumber}`, data)

// AC3: clears a previously-set profile photo. saveProfileBuilderStep(5, ...) can only ever SET one.
export const deleteCoachPhoto = () => api.delete('/api/marketplace/coaches/me/profile/photo')

// The timezone options the profile builder may offer. Sourced from the SERVER's zone set, never
// from Intl.supportedValuesOf(): the browser's tzdata is exactly what used to lock a coach out of
// Steps 1 and 4 when it was newer than the deployed JVM's.
export const getSupportedTimezones = () => api.get('/api/marketplace/coaches/me/profile/timezones')

export const publishProfile = () => api.post('/api/marketplace/coaches/me/profile/publish')

export const searchCoaches = (params = {}) => api.get('/api/marketplace/coaches', { params })

export const getCoachProfile = (coachId) => api.get(`/api/marketplace/coaches/${coachId}`)

export const signUpload = (payload) => api.post('/api/storage/sign/upload', payload)

export const confirmUpload = (key, payload) => api.post(`/api/storage/confirm/${key}`, payload)

export const sanitizePreview = (text, signal) =>
  api.post('/api/util/sanitize-preview', { text }, { signal })

// Coach's own public-profile media gallery (images; max 6 — enforced server-side).
export const getCoachGallery = () => api.get('/api/marketplace/coaches/me/profile/gallery')

export const addCoachGalleryImage = (key) =>
  api.post('/api/marketplace/coaches/me/profile/gallery', { key })

export const deleteCoachGalleryItem = (mediaId) =>
  api.delete(`/api/marketplace/coaches/me/profile/gallery/${mediaId}`)

// Best-effort cleanup of a confirmed upload the gallery then refused (type/size/full): the storage
// DELETE is owner-checked server-side, so a coach can only remove their own object.
export const deleteStorageObject = (key) => api.delete(`/api/storage/${key}`)

// S-09: the coach's optional safeguarding check. The coach can only ask staff to inspect their certificate
// (optionally attaching it) and withdraw; only staff can put the badge on the public profile.
export const getSafeguardingStatus = () => api.get('/api/marketplace/coaches/me/safeguarding')

// file: optional File (PDF/JPG/PNG, max 5 MB). No file = staff inspect another way (e.g. a video call).
export const requestSafeguardingCheck = (file) => {
  const form = new FormData()
  if (file) form.append('file', file)
  return api.post('/api/marketplace/coaches/me/safeguarding', form)
}

export const withdrawSafeguardingCheck = () =>
  api.delete('/api/marketplace/coaches/me/safeguarding')
