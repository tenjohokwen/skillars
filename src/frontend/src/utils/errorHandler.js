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
};

/**
 * Parse API error into structured object.
 * Handles axios error structure and extracts ErrorDto fields.
 *
 * @param {Error} error - Axios error object
 * @returns {{
 *   helpCode: string|null,
 *   errorKey: string,
 *   message: string,
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
      message: 'Network error. Please check your connection.',
      fieldErrors: {},
      isValidationError: false,
      status: 0,
    };
  }

  const { status, data } = error.response;

  // Extract helpCode
  const helpCode = data?.helpCode || null;

  // Extract errorKey - from errorMsg or derive from status
  let errorKey = data?.errorMsg?.errorKey;
  if (!errorKey) {
    if (status >= 500) {
      errorKey = 'error.unknown';
    } else {
      errorKey = defaultErrorKeyByStatus[status] || 'error.unknown';
    }
  }

  // Extract message - from errorMsg or provide default
  let message = data?.errorMsg?.message;
  if (!message) {
    switch (status) {
      case 400:
        message = 'Bad request';
        break;
      case 401:
        message = 'Unauthorized';
        break;
      case 403:
        message = 'Forbidden';
        break;
      case 404:
        message = 'Not found';
        break;
      default:
        message = status >= 500 ? 'Server error' : 'An error occurred';
    }
  }

  // Convert fieldErrors array to object { fieldName: message }
  const fieldErrors = {};
  if (Array.isArray(data?.fieldErrors)) {
    data.fieldErrors.forEach((fe) => {
      if (fe.field && fe.errorMsg?.message) {
        fieldErrors[fe.field] = fe.errorMsg.message;
      }
    });
  }

  // Determine if this is a validation error
  const isValidationError = errorKey.startsWith('validation.');

  return {
    helpCode,
    errorKey,
    message,
    fieldErrors,
    isValidationError,
    status,
  };
}

/**
 * Check if error is a validation error.
 *
 * @param {Error} error - Axios error object
 * @returns {boolean}
 */
export function isValidationError(error) {
  const parsed = parseApiError(error);
  return parsed.isValidationError;
}

/**
 * Get specific field error message.
 *
 * @param {Error} error - Axios error object
 * @param {string} fieldName - Name of the field to get error for
 * @returns {string|null} Error message or null if no error for field
 */
export function getFieldError(error, fieldName) {
  const parsed = parseApiError(error);
  return parsed.fieldErrors[fieldName] || null;
}

/**
 * Get all field errors as object.
 *
 * @param {Error} error - Axios error object
 * @returns {Object.<string, string>} Object mapping field names to error messages
 */
export function getFieldErrors(error) {
  const parsed = parseApiError(error);
  return parsed.fieldErrors;
}

/**
 * Get main error message.
 *
 * @param {Error} error - Axios error object
 * @returns {string} Error message
 */
export function getErrorMessage(error) {
  const parsed = parseApiError(error);
  return parsed.message;
}

/**
 * Get help code for support reference.
 *
 * @param {Error} error - Axios error object
 * @returns {string|null} Help code or null
 */
export function getHelpCode(error) {
  const parsed = parseApiError(error);
  return parsed.helpCode;
}
