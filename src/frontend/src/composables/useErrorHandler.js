import { ref, computed, readonly } from 'vue'
import { useI18n } from 'vue-i18n'
import { parseApiError } from 'src/utils/errorHandler'

/**
 * Composable for handling API errors with reactive state.
 * Wraps the errorHandler utility with Vue reactivity.
 * Uses i18n to translate error messages based on errorKey.
 *
 * @returns {{
 *   error: import('vue').Ref,
 *   fieldErrors: import('vue').ComputedRef<Object>,
 *   hasError: import('vue').ComputedRef<boolean>,
 *   hasFieldErrors: import('vue').ComputedRef<boolean>,
 *   errorMessage: import('vue').ComputedRef<string|null>,
 *   errorKey: import('vue').ComputedRef<string|null>,
 *   helpCode: import('vue').ComputedRef<string|null>,
 *   isValidationError: import('vue').ComputedRef<boolean>,
 *   setError: (err: Error) => void,
 *   clearError: () => void,
 *   hasFieldError: (fieldName: string) => boolean,
 *   getFieldError: (fieldName: string) => string|null
 * }}
 */
export function useErrorHandler() {
  const { t, te } = useI18n()

  // Internal state - parsed error object
  const error = ref(null)

  // Computed properties for easy access
  const fieldErrors = computed(() => error.value?.fieldErrors || {})
  const hasError = computed(() => error.value !== null)
  const errorKey = computed(() => error.value?.errorKey || null)
  const helpCode = computed(() => error.value?.helpCode || null)
  const isValidationError = computed(() => error.value?.isValidationError || false)
  const hasFieldErrors = computed(() => Object.keys(fieldErrors.value).length > 0)

  // Translated error message - uses errorKey for translation, falls back to raw message.
  //
  // skillars-deferred-152 code review (2026-10-17): parseApiError's own status-code fallback key
  // wins when it set one. Those fallbacks are deliberate, localized choices that errorKey
  // intentionally does NOT mirror -- errorKey stays the machine-readable discriminator
  // (boot/axios.js distinguishes security.unauthorized from security.sessionExpired on a 401 to
  // decide whether to say "session expired"), so 401 and 5xx diverge on purpose. Preferring
  // errorKey unconditionally meant those two choices never reached any component on this path:
  // a 401 still read "You do not have the required rights" and a 5xx "An unknown Exception has
  // occurred". messageKey is null whenever the backend supplied its own message, so the
  // errorKey-first rule below still wins there -- a translated key beats the server's English.
  const errorMessage = computed(() => {
    if (!error.value) return null

    const messageKey = error.value.messageKey
    if (messageKey) {
      // Fall back to the already-resolved string if this component's i18n instance lacks the key.
      return te(messageKey) ? t(messageKey) : error.value.message || null
    }

    const key = error.value.errorKey
    if (key && te(key)) {
      return t(key)
    }
    // Fallback to raw message from server
    return error.value.message || null
  })

  /**
   * Set error from API error object.
   * @param {Error} err - Axios error object
   */
  function setError(err) {
    error.value = parseApiError(err)
  }

  /**
   * Clear the current error state.
   */
  function clearError() {
    error.value = null
  }

  /**
   * Check if a specific field has an error.
   * @param {string} fieldName - Name of the field
   * @returns {boolean}
   */
  function hasFieldError(fieldName) {
    return Boolean(fieldErrors.value[fieldName])
  }

  /**
   * Get error message for a specific field.
   * @param {string} fieldName - Name of the field
   * @returns {string|null}
   */
  function getFieldError(fieldName) {
    return fieldErrors.value[fieldName] || null
  }

  return {
    error: readonly(error),
    fieldErrors,
    hasError,
    hasFieldErrors,
    errorMessage,
    errorKey,
    helpCode,
    isValidationError,
    setError,
    clearError,
    hasFieldError,
    getFieldError,
  }
}
