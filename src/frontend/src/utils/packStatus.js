// fix/pack-status-badges: the family-side presentation of a session pack's server-computed status
// (SessionPackPaymentService.computeStatus — precedence REVOKED > EXHAUSTED > FROZEN > PAUSED > EXPIRED >
// ACTIVE). Only ACTIVE is usable; a pause scheduled for a later date leaves the pack ACTIVE until it starts.

const BADGE_COLORS = {
  ACTIVE: 'positive',
  PAUSED: 'warning',
  FROZEN: 'deep-orange',
  REVOKED: 'grey-8',
  EXPIRED: 'negative',
  EXHAUSTED: 'grey',
}

export function packBadgeColor(pack) {
  return BADGE_COLORS[pack?.status] ?? 'grey'
}

export function packBadgeLabel(pack, t) {
  switch (pack?.status) {
    case 'ACTIVE':
      return t('booking.packs.activeStatus')
    case 'PAUSED':
      return t('booking.packs.pausedStatus')
    case 'FROZEN':
      return t('booking.packs.frozenStatus')
    case 'REVOKED':
      return t('booking.packs.revokedStatus')
    case 'EXPIRED':
      return t('booking.packs.expiredLabel')
    case 'EXHAUSTED':
      return t('booking.packs.exhaustedLabel')
    default:
      return pack?.status ?? ''
  }
}

// Why a REVOKED pack was withdrawn (SessionPackPurchase.REVOKED_*). A lost chargeback or an outside
// refund already returned the money to the payer; a coach suspension refunds the unused sessions to the
// payer's wallet (PackSessionService.refundUnusedSessionsForSuspendedCoach).
export function packRevokedReasonText(pack, t) {
  switch (pack?.revokedReason) {
    case 'CHARGEBACK_LOST':
      return t('booking.packs.revokedChargebackLost')
    case 'EXTERNAL_REFUND':
      return t('booking.packs.revokedExternalRefund')
    case 'COACH_SUSPENDED':
      return t('booking.packs.revokedCoachSuspended')
    default:
      return t('booking.packs.revokedOther')
  }
}

// A pause whose start is still in the future — the pack stays ACTIVE (or FROZEN) until it starts.
export function isPauseScheduled(pack, now = new Date()) {
  return !!pack?.pausedFrom && new Date(pack.pausedFrom) > now
}
