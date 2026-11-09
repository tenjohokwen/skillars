import { api } from 'src/boot/axios'

// skillars-deferred-172: the two public links of an email change. The single-use token is the credential;
// POST only, after an explicit click, so a link scanner rendering the page cannot use it.
export const emailChangeApi = {
  /** Applies the change requested from the profile. Resolves to { code } or rejects (400 linkInvalid). */
  confirm(token) {
    return api.post('/api/email-change/confirm', { token })
  },

  /** Reverts a confirmed change. Resolves to { code, passwordResetSent } or rejects (400 linkInvalid). */
  undo(token) {
    return api.post('/api/email-change/undo', { token })
  },
}
