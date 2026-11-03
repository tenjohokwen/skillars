import { defineTaxonomy } from 'pitchside-core'

/** The Skillars tag vocabulary on top of the kernel's scopes and core traits. */
export const taxonomy = defineTaxonomy({
  persona: ['@guest', '@coach', '@parent', '@player', '@admin', '@ltd-admin'],
  domain: [
    '@auth',
    '@registration',
    '@onboarding',
    '@marketplace',
    '@payment',
    '@booking',
    '@money',
    '@subscription',
    '@session',
    '@development',
    '@messaging',
    '@video',
    '@reviews',
    '@ops',
    '@noshow',
    '@notifications',
    '@privacy',
    '@admin-console',
  ],
  trait: ['@stripe-live', '@payment-ui', '@ratelimit', '@tz', '@i18n', '@video-shared'],
})
