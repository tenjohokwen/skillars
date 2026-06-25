import { api } from 'src/boot/axios'
import { loadStripe } from '@stripe/stripe-js'

export const getStripeOnboardingUrl = () => api.get('/api/payment/coaches/me/stripe/onboard')
export const getStripeStatus = () => api.get('/api/payment/coaches/me/stripe/status')

// Credit wallet
export const fetchCreditBalance = () => api.get('/api/payment/credits/balance')
export const cashOut = (amount) => api.post('/api/payment/credits/cashout', { amount })

// Session pack — parent
export const purchaseSessionPack = async (packTierId, paymentMethodId) => {
  const { data } = await api.post('/api/payment/session-packs/purchase', { packTierId, paymentMethodId })
  return data
}
export const extendSessionPack = (purchaseId) =>
  api.post(`/api/payment/session-packs/${purchaseId}/extend`)

// Session pack — coach
export const fetchCoachSessionPackTiers = (coachId) =>
  api.get(`/api/payment/coaches/${coachId}/session-pack-tiers`)
export const fetchMySessionPackTiers = () => api.get('/api/payment/coaches/me/session-pack-tiers')
export const createSessionPackTier = (data) =>
  api.post('/api/payment/coaches/me/session-pack-tiers', data)
export const deactivateSessionPackTier = (tierId) =>
  api.patch(`/api/payment/coaches/me/session-pack-tiers/${tierId}/deactivate`)

// Card setup for future payments (SetupIntent flow)
export const createSetupIntent = () => api.post('/api/payment/setup-intent')
export const savePaymentMethod = (paymentMethodId) =>
  api.post('/api/payment/save-payment-method', { paymentMethodId })

/**
 * Collect card for session pack purchase (immediate capture).
 * Uses stripe.confirmCardPayment — called only from this file, never from stores or components.
 */
export const confirmPackPayment = async (stripePublishableKey, clientSecret) => {
  const stripe = await loadStripe(stripePublishableKey)
  return stripe.confirmCardPayment(clientSecret)
}

/**
 * Collect card for future payments (SetupIntent flow — no immediate charge).
 * Returns the setupIntent after confirmation; caller must then call savePaymentMethod().
 */
export const confirmCardSetup = async (stripePublishableKey, clientSecret) => {
  const stripe = await loadStripe(stripePublishableKey)
  return stripe.confirmCardSetup(clientSecret)
}

// Coach reliability strikes
export const fetchMyStrikes = () => api.get('/api/payment/coaches/me/strikes')
export const acknowledgeStrike = (strikeId) =>
  api.put(`/api/payment/coaches/strikes/${strikeId}/acknowledge`)
