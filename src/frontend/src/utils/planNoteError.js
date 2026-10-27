// skillars-12-6 AC6: maps a failed PATCH /api/bookings/{id}/coach-plan-note to the message to show. Takes
// the caller's `t` and uses literal keys, because i18nTemplateKeyResolutionSpec cannot resolve a
// dynamically-built key. Shared by CoachPlanNotePanel (manual save) and PrepFeedForwardCard (apply), so both react the
// same way to the same failure.

const CONFLICT_ERROR_KEYS = new Set(['booking.concurrentModification', 'CONCURRENT_MODIFICATION'])
const LOCKED_ERROR_KEYS = new Set([
  'booking.notEditable',
  'BOOKING_NOT_EDITABLE',
  'booking.sessionAlreadyEnded',
  'SESSION_ALREADY_ENDED',
])

// skillars-deferred-165 AC8: exported so CoachPlanNotePanel/PrepFeedForwardCard can flip their
// own `locallyLocked` guard on the exact same condition this already maps to the locked message.
export function isLockedPlanNoteError(err) {
  const errorKey = err?.response?.data?.errorMsg?.errorKey
  return LOCKED_ERROR_KEYS.has(errorKey)
}

export function planNoteErrorMessage(err, t) {
  const errorKey = err?.response?.data?.errorMsg?.errorKey
  // The note write either flushes inside the service (403 + concurrent-modification key) or loses the
  // version check at commit (409 generic.conflict) - both mean the booking row changed underneath the
  // coach (a parent edit or the reminder scheduler). Sibling pages match either the dotted key or the
  // enum name, so accept both spellings.
  if (err?.response?.status === 409 || CONFLICT_ERROR_KEYS.has(errorKey)) {
    return t('coach.planNoteConflict')
  }
  if (isLockedPlanNoteError(err)) return t('coach.planNoteLocked')
  return t('coach.planNoteSaveError')
}
