import { i18n } from 'boot/i18n'

/**
 * Error handling utilities for parsing backend ErrorDto responses.
 *
 * Backend ErrorDto structure:
 * {
 *   "helpCode": "abc123xyz",
 *   "errorMsg": {
 *     "errorKey": "validation.invalidData",
 *     "message": "Invalid Data"
 *   },
 *   "fieldErrors": [
 *     {
 *       "objectName": "registrationDto",
 *       "field": "email",
 *       "errorMsg": {
 *         "errorKey": "invalid.email",
 *         "message": "Must be a valid email address"
 *       }
 *     }
 *   ]
 * }
 */

/**
 * Default error keys by HTTP status code
 */
const defaultErrorKeyByStatus = {
  400: 'validation.badRequest',
  401: 'security.unauthorized',
  403: 'security.opForbidden',
  404: 'error.notFound',
}

// skillars-deferred-152 code review (2026-10-17): status-code fallback MESSAGE keys, deliberately
// a separate map from defaultErrorKeyByStatus above rather than a second hardcoded copy of it.
// The two serve different jobs and must not be collapsed: defaultErrorKeyByStatus is the
// machine-readable `errorKey` discriminator (boot/axios.js:164-192 tells security.unauthorized
// apart from security.sessionExpired on a 401 to decide whether to render "session expired"),
// while these are the strings a user actually reads. Two entries therefore diverge on purpose:
//   401 → security.sessionExpired, not security.unauthorized ("You do not have the required
//         rights" is a 403-shaped, not-authenticated-vs-not-authorized message)
//   400 → error.badRequest, not validation.badRequest (that key is developer-facing copy:
//         "Check method argument mismatch, missing parameters e.t.c.")
// 403/404 read through from the map above precisely so the shared values cannot drift.
const fallbackMessageKeyByStatus = {
  400: 'error.badRequest',
  401: 'security.sessionExpired',
  403: defaultErrorKeyByStatus[403],
  404: defaultErrorKeyByStatus[404],
}

// No error.serverError key exists in any locale bundle, so 5xx maps to error.generic — which
// preserves the 5xx-vs-client distinction the pre-sweep English literals ('Server error' vs
// 'An error occurred') had, instead of collapsing 5xx into the error.unknown used for every
// other unmapped status.
const SERVER_ERROR_MESSAGE_KEY = 'error.generic'
const UNKNOWN_ERROR_MESSAGE_KEY = 'error.unknown'

/**
 * Parse API error into structured object.
 * Handles axios error structure and extracts ErrorDto fields.
 *
 * @param {Error} error - Axios error object
 * @returns {{
 *   helpCode: string|null,
 *   errorKey: string,
 *   message: string,
 *   messageKey: string|null,
 *   fieldErrors: Object.<string, string>,
 *   isValidationError: boolean,
 *   status: number
 * }}
 */
export function parseApiError(error) {
  // Handle network errors (no response)
  if (!error.response) {
    return {
      helpCode: null,
      errorKey: 'error.network',
      // skillars-deferred-92 AC14: this is the message the UI actually renders
      // (useErrorHandler exposes error.message), so an English literal here reached a
      // French or German user verbatim. `error.network` already existed in all three
      // bundles. i18n.global.t is the documented way to translate outside a component.
      message: i18n.global.t('error.network'),
      fieldErrors: {},
      isValidationError: false,
      status: 0,
    }
  }

  const { status, data } = error.response

  // Extract helpCode
  const helpCode = data?.helpCode || null

  // Extract errorKey - from errorMsg or derive from status
  let errorKey = data?.errorMsg?.errorKey
  if (!errorKey) {
    if (status >= 500) {
      errorKey = 'error.unknown'
    } else {
      errorKey = defaultErrorKeyByStatus[status] || 'error.unknown'
    }
  }

  // Extract message - from errorMsg or provide default.
  // skillars-deferred-152 code review (2026-10-17): `messageKey` is reported alongside the
  // resolved string so consumers that render from a key can honour the deliberate choices above
  // instead of re-deriving their own. useErrorHandler.errorMessage previously preferred errorKey
  // unconditionally, which meant the 401 and 5xx decisions here never reached the ~40 components
  // on that path -- they still rendered security.unauthorized / error.unknown. It stays null when
  // the backend supplied its own message, so the errorKey-first rule still wins there (a
  // translated key beats the server's English text).
  let message = data?.errorMsg?.message
  let messageKey = null
  if (!message) {
    messageKey =
      fallbackMessageKeyByStatus[status] ||
      (status >= 500 ? SERVER_ERROR_MESSAGE_KEY : UNKNOWN_ERROR_MESSAGE_KEY)
    message = i18n.global.t(messageKey)
  }

  // Convert fieldErrors array to object { fieldName: message }
  const fieldErrors = {}
  if (Array.isArray(data?.fieldErrors)) {
    data.fieldErrors.forEach((fe) => {
      if (fe.field && fe.errorMsg?.message) {
        fieldErrors[fe.field] = fe.errorMsg.message
      }
    })
  }

  // Determine if this is a validation error
  const isValidationError = errorKey.startsWith('validation.')

  return {
    helpCode,
    errorKey,
    message,
    messageKey,
    fieldErrors,
    isValidationError,
    status,
  }
}

/**
 * Check if error is a validation error.
 *
 * @param {Error} error - Axios error object
 * @returns {boolean}
 */
export function isValidationError(error) {
  const parsed = parseApiError(error)
  return parsed.isValidationError
}

/**
 * Get specific field error message.
 *
 * @param {Error} error - Axios error object
 * @param {string} fieldName - Name of the field to get error for
 * @returns {string|null} Error message or null if no error for field
 */
export function getFieldError(error, fieldName) {
  const parsed = parseApiError(error)
  return parsed.fieldErrors[fieldName] || null
}

/**
 * Get all field errors as object.
 *
 * @param {Error} error - Axios error object
 * @returns {Object.<string, string>} Object mapping field names to error messages
 */
export function getFieldErrors(error) {
  const parsed = parseApiError(error)
  return parsed.fieldErrors
}

/**
 * Get main error message.
 *
 * @param {Error} error - Axios error object
 * @returns {string} Error message
 */
export function getErrorMessage(error) {
  const parsed = parseApiError(error)
  return parsed.message
}

/**
 * Get help code for support reference.
 *
 * @param {Error} error - Axios error object
 * @returns {string|null} Help code or null
 */
export function getHelpCode(error) {
  const parsed = parseApiError(error)
  return parsed.helpCode
}
