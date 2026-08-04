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
  getStripeConfig,
  getSavedPaymentMethod,
} from 'src/api/payment.api'

// AC 6 / Task 4: each async action owns its own loading/error key instead of a single shared
// pair, so a faster action completing doesn't clear a slower one's spinner or overwrite its
// error. Keys match the action name (minus the "fetch"/"get" prefix).
const LOADING_KEYS = [
  'stripeStatus',
  'creditBalance',
  'sessionPackTiers',
  'coachStrikes',
  'coachSubscription',
  'coachTiers',
  'playerSubscription',
  'playerTiers',
  'revenueSummary',
  'transactions',
  'creditStatement',
  'stripeConfig',
  'savedPaymentMethod',
]

function emptyKeyedState() {
  return Object.fromEntries(LOADING_KEYS.map((key) => [key, false]))
}

function emptyKeyedErrorState() {
  return Object.fromEntries(LOADING_KEYS.map((key) => [key, null]))
}

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
    stripeConfig: null,
    savedPaymentMethod: null,
    loading: emptyKeyedState(),
    error: emptyKeyedErrorState(),
  }),
  actions: {
    async fetchStripeStatus() {
      this.loading.stripeStatus = true
      this.error.stripeStatus = null
      try {
        this.stripeStatus = await getStripeStatus()
      } catch (err) {
        this.error.stripeStatus = err
      } finally {
        this.loading.stripeStatus = false
      }
    },
    async fetchCreditBalance() {
      this.loading.creditBalance = true
      this.error.creditBalance = null
      try {
        this.creditBalance = await fetchCreditBalance()
      } catch (err) {
        this.error.creditBalance = err
      } finally {
        this.loading.creditBalance = false
      }
    },
    async fetchSessionPackTiers() {
      this.loading.sessionPackTiers = true
      this.error.sessionPackTiers = null
      try {
        this.sessionPackTiers = await fetchMySessionPackTiers()
      } catch (err) {
        this.error.sessionPackTiers = err
      } finally {
        this.loading.sessionPackTiers = false
      }
    },
    async fetchCoachStrikes() {
      this.loading.coachStrikes = true
      this.error.coachStrikes = null
      try {
        this.coachStrikes = await fetchMyStrikes()
      } catch (err) {
        this.error.coachStrikes = err
      } finally {
        this.loading.coachStrikes = false
      }
    },
    async acknowledgeStrike(strikeId) {
      await acknowledgeStrike(strikeId)
      const strike = this.coachStrikes.find((s) => s.strikeId === strikeId)
      if (strike) strike.acknowledged = true
    },

    // Coach subscription actions
    async fetchCoachSubscription() {
      this.loading.coachSubscription = true
      this.error.coachSubscription = null
      try {
        this.coachSubscription = await fetchMyCoachSubscription()
      } catch (err) {
        this.error.coachSubscription = err
      } finally {
        this.loading.coachSubscription = false
      }
    },
    async fetchCoachTiers() {
      this.loading.coachTiers = true
      this.error.coachTiers = null
      try {
        this.coachTiers = await fetchCoachTiers()
      } catch (err) {
        this.error.coachTiers = err
      } finally {
        this.loading.coachTiers = false
      }
    },
    async subscribeCoach(payload) {
      const data = await subscribeCoach(payload)
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
      this.loading.playerSubscription = true
      this.error.playerSubscription = null
      try {
        this.playerSubscription = await fetchMyPlayerSubscription(playerId)
      } catch (err) {
        this.error.playerSubscription = err
      } finally {
        this.loading.playerSubscription = false
      }
    },
    async fetchPlayerTiers() {
      this.loading.playerTiers = true
      this.error.playerTiers = null
      try {
        this.playerTiers = await fetchPlayerTiers()
      } catch (err) {
        this.error.playerTiers = err
      } finally {
        this.loading.playerTiers = false
      }
    },
    async subscribePlayer(payload) {
      const data = await subscribePlayer(payload)
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
      this.loading.revenueSummary = true
      this.error.revenueSummary = null
      try {
        this.revenueSummary = await fetchCoachRevenueSummary(from, to)
      } catch (err) {
        this.error.revenueSummary = err
      } finally {
        this.loading.revenueSummary = false
      }
    },
    async fetchTransactions(from, to, page = 0) {
      this.loading.transactions = true
      this.error.transactions = null
      try {
        const res = await fetchCoachTransactions(from, to, page)
        this.transactionPage = res
        this.transactions = res.content
      } catch (err) {
        this.error.transactions = err
      } finally {
        this.loading.transactions = false
      }
    },
    async fetchCreditStatement(from, to, page = 0) {
      this.loading.creditStatement = true
      this.error.creditStatement = null
      try {
        const res = await fetchCreditStatement(from, to, page)
        this.creditStatementPage = res
        this.creditStatement = res.content
      } catch (err) {
        this.error.creditStatement = err
      } finally {
        this.loading.creditStatement = false
      }
    },

    // Card collection (Deferred-11)
    async fetchStripeConfig() {
      this.loading.stripeConfig = true
      this.error.stripeConfig = null
      try {
        this.stripeConfig = await getStripeConfig()
      } catch (err) {
        this.error.stripeConfig = err
      } finally {
        this.loading.stripeConfig = false
      }
    },
    async fetchSavedPaymentMethod() {
      this.loading.savedPaymentMethod = true
      this.error.savedPaymentMethod = null
      try {
        this.savedPaymentMethod = await getSavedPaymentMethod()
      } catch (err) {
        this.error.savedPaymentMethod = err
      } finally {
        this.loading.savedPaymentMethod = false
      }
    },
  },
})
