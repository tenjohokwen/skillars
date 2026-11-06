// French takes the singular for 0 as well as 1 ("0 utilisation"), unlike vue-i18n's default
// English-style rule. Only two-form messages ("one | many") change; a three-form message
// ("zero | one | many") keeps the default choice. Found by Pitchside SES-13 review.
export function frenchPlural(choice, choicesLength) {
  const n = Math.abs(choice)
  if (choicesLength === 2) return n <= 1 ? 0 : 1
  if (choicesLength === 3) return Math.min(n, 2)
  return 0
}

export const pluralRules = { 'fr-FR': frenchPlural }
