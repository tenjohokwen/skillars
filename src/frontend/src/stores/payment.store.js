import { defineStore } from 'pinia'
import {
  getStripeStatus,
  fetchCreditBalance,
  fetchMySessionPackTiers,
  fetchMyStrikes,
  acknowledgeStrike,
} from 'src/api/payment.api'

export const usePaymentStore = defineStore('payment', {
  state: () => ({
    stripeStatus: null,
    creditBalance: null,
    sessionPackTiers: [],
    coachStrikes: [],
    loading: false,
    error: null,
  }),
  actions: {
    async fetchStripeStatus() {
      this.loading = true
      this.error = null
      try {
        this.stripeStatus = (await getStripeStatus()).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async fetchCreditBalance() {
      this.loading = true
      this.error = null
      try {
        this.creditBalance = (await fetchCreditBalance()).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async fetchSessionPackTiers() {
      this.loading = true
      this.error = null
      try {
        this.sessionPackTiers = (await fetchMySessionPackTiers()).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async fetchCoachStrikes() {
      this.loading = true
      this.error = null
      try {
        this.coachStrikes = (await fetchMyStrikes()).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async acknowledgeStrike(strikeId) {
      await acknowledgeStrike(strikeId)
      const strike = this.coachStrikes.find((s) => s.strikeId === strikeId)
      if (strike) strike.acknowledged = true
    },
  },
})
