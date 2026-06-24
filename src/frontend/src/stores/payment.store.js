import { defineStore } from 'pinia'
import { getStripeStatus } from 'src/api/payment.api'

export const usePaymentStore = defineStore('payment', {
  state: () => ({ stripeStatus: null, loading: false, error: null }),
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
  },
})
