// Specs never contain a literal UI string: they resolve keys through the app's own message bundle,
// so a copy change touches one translation file instead of forty specs, and the suite runs in any
// shipped locale unchanged (E2E_LOCALE).
//
// Each locale file is imported directly (not the i18n/index.js barrel, whose extensionless
// directory imports Node's ESM resolver does not follow).
import deDE from '../../../../src/frontend/src/i18n/de-DE/index.js'
import enUS from '../../../../src/frontend/src/i18n/en-US/index.js'
import frFR from '../../../../src/frontend/src/i18n/fr-FR/index.js'
import { E2E } from './env.js'

type Messages = Record<string, unknown>
const bundles: Record<string, Messages> = {
  'en-US': enUS,
  'fr-FR': frFR,
  'de-DE': deDE,
}

/**
 * The message for `key` (dot path), with `{name}` placeholders filled. Throws on a missing key or
 * an unknown locale, so a renamed key fails loudly instead of asserting on `undefined`.
 */
export function t(
  key: string,
  params: Record<string, string | number> = {},
  locale: string = E2E.locale,
): string {
  const bundle = bundles[locale]
  if (!bundle) throw new Error(`No i18n bundle for locale "${locale}"`)
  const value = key
    .split('.')
    .reduce<unknown>((node, part) => (node as Messages | undefined)?.[part], bundle)
  if (typeof value !== 'string') throw new Error(`Missing i18n key "${key}" in ${locale}`)
  return value.replace(/\{(\w+)\}/g, (match, name: string) =>
    params[name] === undefined ? match : String(params[name]),
  )
}

/**
 * A pluralised message (`'one | many'` or `'none | one | many'`), chosen the way vue-i18n's default
 * rule does, with `{n}` (and any `params`) filled.
 */
export function tc(
  key: string,
  n: number,
  params: Record<string, string | number> = {},
  locale: string = E2E.locale,
): string {
  const forms = t(key, { n, ...params }, locale)
    .split('|')
    .map((form) => form.trim())
  const index =
    forms.length === 3 ? Math.min(Math.abs(n), 2) : forms.length === 2 ? (n === 1 ? 0 : 1) : 0
  return forms[index]!
}
