// skillars-deferred-152 AC1/AC10 — errorHandler.js's parseApiError/getErrorMessage.
//
// Zero direct test coverage existed for this file before this story, despite it being the
// shared helper the store-wide i18n sweep (development.store.js, booking.store.js) routes
// through. errorHandler.js imports the REAL `boot/i18n` bundle directly (not
// `test/vitest/setup-file.js`'s near-empty global i18n instance, which only components that
// `mount()` ever see) — so asserting "translated" here means switching
// `i18n.global.locale.value` explicitly and asserting the OTHER locale's own string, not just
// that the result differs from the old hardcoded English literal (the real en-US bundle's text
// would pass that weaker check too, since it happens to read in English either way).
//
// skillars-deferred-152 code review (2026-10-17): each case also asserts one CONCRETE locale
// literal, not only `toBe(i18n.global.t(<same key>))`. vue-i18n returns the key string itself on
// a miss, so a key-vs-key comparison reads 'x.y' === 'x.y' and passes green while the user sees a
// raw key on screen -- it could not fail for the one regression this file exists to catch (a key
// pruned from a bundle).

import { describe, it, expect, afterEach } from 'vitest'
import { i18n } from 'boot/i18n'
import { parseApiError, getErrorMessage } from 'src/utils/errorHandler'

function axiosError({ status, data } = {}) {
  if (status === undefined) return { response: undefined }
  return { response: { status, data } }
}

describe('errorHandler — parseApiError/getErrorMessage status-code fallback messages', () => {
  afterEach(() => {
    i18n.global.locale.value = 'en-US'
  })

  it('translates the network-error fallback (no response) in the active locale', () => {
    i18n.global.locale.value = 'fr-FR'
    const message = getErrorMessage(axiosError())
    expect(message).toBe('Erreur réseau. Veuillez vérifier votre connexion.')
    expect(message).toBe(i18n.global.t('error.network'))
    expect(message).not.toBe('Network error. Please check your connection.')
  })

  it("maps 401 to security.sessionExpired, not defaultErrorKeyByStatus[401]'s security.unauthorized", () => {
    i18n.global.locale.value = 'fr-FR'
    const message = getErrorMessage(axiosError({ status: 401, data: {} }))
    expect(message).toBe("Votre session n'est plus valide. Vous devez vous reconnecter")
    expect(message).toBe(i18n.global.t('security.sessionExpired'))
    expect(message).not.toBe(i18n.global.t('security.unauthorized'))
  })

  // skillars-deferred-152 code review (2026-10-17): error.badRequest, NOT
  // defaultErrorKeyByStatus[400]'s validation.badRequest -- that key is developer-facing copy
  // ("Check method argument mismatch, missing parameters e.t.c.") and was being shown to users.
  it('maps 400 to the user-facing error.badRequest, translated', () => {
    i18n.global.locale.value = 'de-DE'
    const message = getErrorMessage(axiosError({ status: 400, data: {} }))
    expect(message).toBe(
      'Diese Anfrage konnte nicht abgeschlossen werden. Bitte überprüfen Sie Ihre Angaben und versuchen Sie es erneut.',
    )
    expect(message).toBe(i18n.global.t('error.badRequest'))
    expect(message).not.toBe(i18n.global.t('validation.badRequest'))
  })

  it('maps 403 to security.opForbidden, translated', () => {
    i18n.global.locale.value = 'fr-FR'
    const message = getErrorMessage(axiosError({ status: 403, data: {} }))
    expect(message).toBe("L'accès a été refusé. Vous pouvez contacter le support")
    expect(message).toBe(i18n.global.t('security.opForbidden'))
  })

  it('maps 404 to error.notFound, translated', () => {
    i18n.global.locale.value = 'de-DE'
    const message = getErrorMessage(axiosError({ status: 404, data: {} }))
    expect(message).toBe('Die angeforderte Ressource wurde nicht gefunden')
    expect(message).toBe(i18n.global.t('error.notFound'))
  })

  it('maps 5xx to error.generic, not the error.unknown used for other unmapped statuses', () => {
    i18n.global.locale.value = 'fr-FR'
    const message = getErrorMessage(axiosError({ status: 500, data: {} }))
    expect(message).toBe('Une erreur inattendue est survenue. Veuillez réessayer.')
    expect(message).toBe(i18n.global.t('error.generic'))
    expect(message).not.toBe(i18n.global.t('error.unknown'))
  })

  it('maps an unmapped non-5xx status (e.g. 409) to error.unknown, translated', () => {
    i18n.global.locale.value = 'de-DE'
    const message = getErrorMessage(axiosError({ status: 409, data: {} }))
    expect(message).toBe('Eine unbekannte Ausnahme ist aufgetreten')
    expect(message).toBe(i18n.global.t('error.unknown'))
  })

  // skillars-deferred-152 code review (2026-10-17): messageKey is what lets
  // useErrorHandler.errorMessage honour the deliberate 401/5xx choices above instead of
  // re-deriving from errorKey (which stays the machine-readable discriminator).
  it('reports the fallback messageKey it chose, and leaves errorKey untouched', () => {
    const unauthenticated = parseApiError(axiosError({ status: 401, data: {} }))
    expect(unauthenticated.messageKey).toBe('security.sessionExpired')
    expect(unauthenticated.errorKey).toBe('security.unauthorized')

    const serverError = parseApiError(axiosError({ status: 500, data: {} }))
    expect(serverError.messageKey).toBe('error.generic')
    expect(serverError.errorKey).toBe('error.unknown')
  })

  it('leaves messageKey null when the backend supplied its own message', () => {
    const parsed = parseApiError(
      axiosError({
        status: 500,
        data: { errorMsg: { errorKey: 'booking.conflict', message: 'Slot taken' } },
      }),
    )
    expect(parsed.messageKey).toBeNull()
    expect(parsed.message).toBe('Slot taken')
  })

  it('prefers the backend errorMsg.message over any status-code fallback', () => {
    i18n.global.locale.value = 'fr-FR'
    const parsed = parseApiError(
      axiosError({
        status: 400,
        data: { errorMsg: { errorKey: 'validation.invalidData', message: 'Invalid Data' } },
      }),
    )
    expect(parsed.message).toBe('Invalid Data')
    expect(parsed.errorKey).toBe('validation.invalidData')
  })
})
