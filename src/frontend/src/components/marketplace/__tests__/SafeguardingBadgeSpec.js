// S-09: SafeguardingBadge.vue - neutral, factual wording in every locale, never a warning colour.

import { describe, it, expect, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US/index.js'
import deDE from 'src/i18n/de-DE/index.js'
import frFR from 'src/i18n/fr-FR/index.js'
import SafeguardingBadge from 'src/components/marketplace/SafeguardingBadge.vue'

const mounted = []
function mountBadge(props, locale = 'en-US') {
  const i18n = createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'en-US',
    messages: { 'en-US': enUS, 'de-DE': deDE, 'fr-FR': frFR },
  })
  const wrapper = mount(SafeguardingBadge, { props, global: { plugins: [i18n] } })
  mounted.push(wrapper)
  return wrapper
}

describe('SafeguardingBadge.vue (S-09)', () => {
  afterEach(() => {
    while (mounted.length) mounted.pop().unmount()
  })

  it('shows the valid-until month when a check is on record', () => {
    const wrapper = mountBadge({ onRecord: true, validUntil: '2031-03-15' })
    expect(wrapper.text()).toContain('Safeguarding check on record (valid until 03/2031)')
    expect(wrapper.find('[data-testid="safeguarding-badge-on"]').exists()).toBe(true)
  })

  it('states plainly that no check is on record, without accusing', () => {
    const wrapper = mountBadge({ onRecord: false })
    expect(wrapper.text()).toContain('No safeguarding check on record')
    expect(wrapper.find('[data-testid="safeguarding-badge-none"]').exists()).toBe(true)
    // neutral: never a warning/negative colour
    expect(wrapper.html()).not.toMatch(/\b(negative|warning|red)\b/)
  })

  it('has German wording', () => {
    expect(mountBadge({ onRecord: true, validUntil: '2031-03-15' }, 'de-DE').text()).toContain(
      'Kinderschutz-Check hinterlegt (gültig bis 03/2031)',
    )
    expect(mountBadge({ onRecord: false }, 'de-DE').text()).toContain(
      'Kein Kinderschutz-Check hinterlegt',
    )
  })

  it('has French wording', () => {
    expect(mountBadge({ onRecord: false }, 'fr-FR').text()).toContain(
      'Aucun contrôle de protection de l’enfance enregistré',
    )
  })
})
