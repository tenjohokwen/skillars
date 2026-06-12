import { api } from 'src/boot/axios'

export const getProfileBuilderStatus = () =>
  api.get('/api/marketplace/coaches/me/profile/status')

export const saveProfileBuilderStep = (stepNumber, data) =>
  api.put(`/api/marketplace/coaches/me/profile/steps/${stepNumber}`, data)

export const publishProfile = () =>
  api.post('/api/marketplace/coaches/me/profile/publish')

export const signUpload = (payload) =>
  api.post('/api/storage/sign/upload', payload)

export const confirmUpload = (key, payload) =>
  api.post(`/api/storage/confirm/${key}`, payload)
