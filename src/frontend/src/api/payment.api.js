import { api } from 'src/boot/axios'

export const getStripeOnboardingUrl = () => api.get('/api/payment/coaches/me/stripe/onboard')
export const getStripeStatus = () => api.get('/api/payment/coaches/me/stripe/status')
