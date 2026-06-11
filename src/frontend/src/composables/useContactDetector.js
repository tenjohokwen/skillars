import { computed } from 'vue'

const EMAIL_RE = /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/
const PHONE_RE = /(?:\+?[\d][\d\s\-().]{6,14}[\d])/

export function useContactDetector(valueRef) {
  const hasContactDetail = computed(() =>
    !!(valueRef.value && (EMAIL_RE.test(valueRef.value) || PHONE_RE.test(valueRef.value)))
  )
  return { hasContactDetail }
}
