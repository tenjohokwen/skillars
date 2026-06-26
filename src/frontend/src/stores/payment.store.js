import { defineStore } from 'pinia'
import {
  getStripeStatus,
  fetchCreditBalance,
  fetchMySessionPackTiers,
  fetchMyStrikes,
  acknowledgeStrike,
  fetchCoachTiers,
  fetchMyCoachSubscription,
  subscribeCoach,
  changeCoachTier,
  cancelCoachSubscription,
  fetchPlayerTiers,
  fetchMyPlayerSubscription,
  subscribePlayer,
  changePlayerTier,
  cancelPlayerSubscription,
  fetchCoachRevenueSummary,
  fetchCoachTransactions,
  fetchCreditStatement,
} from 'src/api/payment.api'

export const usePaymentStore = defineStore('payment', {
  state: () => ({
    stripeStatus: null,
    creditBalance: null,
    sessionPackTiers: [],
    coachStrikes: [],
    coachSubscription: null,
    coachTiers: [],
    playerSubscription: null,
    playerTiers: [],
    revenueSummary: null,
    transactions: [],
    transactionPage: null,
    creditStatement: [],
    creditStatementPage: null,
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

    // Coach subscription actions
    async fetchCoachSubscription() {
      this.loading = true
      this.error = null
      try {
        this.coachSubscription = (await fetchMyCoachSubscription()).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async fetchCoachTiers() {
      this.loading = true
      this.error = null
      try {
        this.coachTiers = (await fetchCoachTiers()).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async subscribeCoach(payload) {
      const { data } = await subscribeCoach(payload)
      this.coachSubscription = data
      return data
    },
    async changeCoachTier(newTier) {
      await changeCoachTier({ newTier })
      await this.fetchCoachSubscription()
    },
    async cancelCoachSubscription() {
      await cancelCoachSubscription()
      await this.fetchCoachSubscription()
    },

    // Player subscription actions
    async fetchPlayerSubscription(playerId) {
      this.loading = true
      this.error = null
      try {
        this.playerSubscription = (await fetchMyPlayerSubscription(playerId)).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async fetchPlayerTiers() {
      this.loading = true
      this.error = null
      try {
        this.playerTiers = (await fetchPlayerTiers()).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async subscribePlayer(payload) {
      const { data } = await subscribePlayer(payload)
      this.playerSubscription = data
      return data
    },
    async changePlayerTier(payload) {
      await changePlayerTier(payload)
      await this.fetchPlayerSubscription(payload.playerId)
    },
    async cancelPlayerSubscription(playerId) {
      await cancelPlayerSubscription(playerId)
      await this.fetchPlayerSubscription(playerId)
    },

    async fetchRevenueSummary(from, to) {
      this.loading = true
      this.error = null
      try {
        this.revenueSummary = (await fetchCoachRevenueSummary(from, to)).data
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async fetchTransactions(from, to, page = 0) {
      this.loading = true
      this.error = null
      try {
        const res = await fetchCoachTransactions(from, to, page)
        this.transactionPage = res.data
        this.transactions = res.data.content
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
    async fetchCreditStatement(from, to, page = 0) {
      this.loading = true
      this.error = null
      try {
        const res = await fetchCreditStatement(from, to, page)
        this.creditStatementPage = res.data
        this.creditStatement = res.data.content
      } catch (err) {
        this.error = err
      } finally {
        this.loading = false
      }
    },
  },
})
