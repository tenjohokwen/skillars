import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQuasar } from 'quasar'
import { useI18n } from 'vue-i18n'
import { useMessagingStore } from 'src/stores/messaging.store'
import { messagingErrorI18nKey } from 'src/utils/messagingErrors'

// Mirrors MessagingService.CONFIRMED_STATES: the booking statuses that establish the coach/player
// relationship POST /api/messaging/conversations requires (else messaging.noBookingRelationship).
export const MESSAGING_BOOKING_STATES = ['CONFIRMED', 'UPCOMING', 'IN_PROGRESS', 'COMPLETED']

export function establishesMessagingRelationship(booking) {
  return MESSAGING_BOOKING_STATES.includes(booking?.status)
}

const keyOf = (coachId, playerId) => `${coachId ?? ''}:${playerId}`

/**
 * Shared "Message coach / Message player" entry-point behaviour: open (or create) the coach/player
 * conversation, then land on /messaging with it selected (`?conversation=<id>`).
 *
 * `coachId` may be null for a COACH caller (the backend defaults it to their own profile). The
 * backend is idempotent per (coach, player) pair; while one request is in flight further clicks are
 * ignored, and `isStarting(coachId, playerId)` lets only the clicked row's button show the spinner.
 */
export function useStartConversation() {
  const router = useRouter()
  const $q = useQuasar()
  const { t } = useI18n()
  const store = useMessagingStore()
  const startingKey = ref(null)

  const isStarting = (coachId, playerId) => startingKey.value === keyOf(coachId, playerId)

  async function startConversation(coachId, playerId) {
    if (startingKey.value != null || playerId == null) return null
    startingKey.value = keyOf(coachId, playerId)
    try {
      const res = await store.openConversation(coachId, playerId)
      await router.push({ name: 'messaging', query: { conversation: String(res.conversationId) } })
      return res
    } catch (err) {
      $q.notify({ type: 'negative', message: t(messagingErrorI18nKey(err)) })
      return null
    } finally {
      startingKey.value = null
    }
  }

  return { isStarting, startConversation }
}
