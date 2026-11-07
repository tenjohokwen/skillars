import { describe, it, expect } from 'vitest'
import { createI18n } from 'vue-i18n'
import enUS from 'src/i18n/en-US/index.js'
import frFR from 'src/i18n/fr-FR/index.js'
import { frenchPlural, pluralRules } from 'src/i18n/pluralRules'

describe('pluralRules', () => {
  it('French takes the singular for 0 and 1 in a two-form message', () => {
    expect([0, 1, 2, 5].map((n) => frenchPlural(n, 2))).toEqual([0, 0, 1, 1])
  })

  it('a three-form message keeps its zero form', () => {
    expect([0, 1, 2, 7].map((n) => frenchPlural(n, 3))).toEqual([0, 1, 2, 2])
  })

  it('the template counts read correctly in French and English', () => {
    const i18n = createI18n({
      legacy: false,
      locale: 'fr-FR',
      messages: { 'fr-FR': frFR, 'en-US': enUS },
      pluralRules,
    })
    const { t, locale } = i18n.global
    expect(t('session.templates.deployCount', { count: 0 }, 0)).toBe('0 utilisation')
    expect(t('session.templates.deployCount', { count: 3 }, 3)).toBe('3 utilisations')
    locale.value = 'en-US'
    expect(t('session.templates.deployCount', { count: 0 }, 0)).toBe('0 uses')
    expect(t('session.templates.deployCount', { count: 1 }, 1)).toBe('1 use')
  })
})
