import { computed } from 'vue'

const EMAIL_RE = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/
const PHONE_RE = /(?:\+?[\d][\d\s\-().]{6,14}[\d])/
// Pitchside REG-07: the backend's ContactDetailSanitizer strips a link from a name too, but only behind an explicit
// scheme or "www." (so "J.R." or "St.Clair" are not links); warn on exactly those, rather than drop it silently.
const URL_RE = /(?:\bhttps?:\/\/|\bwww\.)\S+/i

export function useContactDetector(valueRef) {
  const hasContactDetail = computed(() => {
    const value = valueRef.value
    return !!(value && (EMAIL_RE.test(value) || PHONE_RE.test(value) || URL_RE.test(value)))
  })
  return { hasContactDetail }
}
