// skillars-deferred-168: the coach's one purchasable session pack, as the profile builder's step 3
// (ProfileBuilderStep3.vue) and the profile's "Edit pricing" dialog (EditCoachPricingDialog.vue) edit it.
// The pack becomes the coach's active session pack tier, so these are the tier's own bounds
// (SessionPackLimits / CreateSessionPackTierRequest on the backend), checked here so the coach sees a
// specific message before the request is sent. The backend still enforces every one of them.

export const PACK_MAX_SESSION_COUNT = 100
export const PACK_MAX_TOTAL_PRICE = 50000
export const PACK_MAX_LABEL_LENGTH = 200

// `v-model.number` on a cleared type="number" q-input yields '' — not null — so '' counts as untouched
// (skillars-deferred-109 code review).
export function packFieldEntered(v) {
  return v != null && v !== ''
}

export function packTouched(p) {
  return (
    packFieldEntered(p.sessionCount) ||
    packFieldEntered(p.totalPrice) ||
    (p.label != null && p.label.trim() !== '')
  )
}

/**
 * The i18n key explaining why a touched pack cannot be saved, or null when it can. An untouched (fully
 * empty) pack is not an error: it is simply not sent.
 */
export function packErrorKey(p) {
  if (!packTouched(p)) return null
  if (!packFieldEntered(p.sessionCount) || !packFieldEntered(p.totalPrice)) {
    return 'auth.coach.step3PackInvalid'
  }
  const count = Number(p.sessionCount)
  const total = Number(p.totalPrice)
  if (!Number.isInteger(count) || count < 1 || count > PACK_MAX_SESSION_COUNT) {
    return 'auth.coach.step3PackSessionCountError'
  }
  if (
    !Number.isFinite(total) ||
    total < 0.01 ||
    total > PACK_MAX_TOTAL_PRICE ||
    Math.abs(total * 100 - Math.round(total * 100)) > 1e-6
  ) {
    return 'auth.coach.step3PackPriceError'
  }
  // The backend derives the per-session price as total / count rounded HALF_UP to cents, and it must
  // stay above 0.00.
  if (total / count < 0.005) {
    return 'auth.coach.step3PackPerSessionError'
  }
  if (p.label != null && p.label.length > PACK_MAX_LABEL_LENGTH) {
    return 'auth.coach.step3PackLabelTooLong'
  }
  return null
}

/** The request body's sessionPacks list: 0 or 1 pack. */
export function packsForRequest(packs) {
  return packs
    .filter((p) => packTouched(p))
    .map((p) => ({
      sessionCount: Number(p.sessionCount),
      totalPrice: Number(p.totalPrice),
      label: p.label || null,
    }))
}

/**
 * The first server field error about the pack. Bean validation reports nested fields as
 * `sessionPacks[0].totalPrice`, which an exact `hasFieldError('sessionPacks')` never matches.
 */
export function packServerError(fieldErrors) {
  const key = Object.keys(fieldErrors || {}).find(
    (k) => k === 'sessionPacks' || k.startsWith('sessionPacks['),
  )
  return key ? fieldErrors[key] : null
}
