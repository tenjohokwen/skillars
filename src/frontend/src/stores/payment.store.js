import { defineStore } from 'pinia'
import { getStripeStatus, fetchCreditBalance, fetchMySessionPackTiers } from 'src/api/payment.api'

export const usePaymentStore = defineStore('payment', {
  state: () => ({
    stripeStatus: null,
    creditBalance: null,
    sessionPackTiers: [],
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
  },
})
