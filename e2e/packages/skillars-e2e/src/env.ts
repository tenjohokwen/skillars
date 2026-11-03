import { optionalEnv } from 'pitchside-core'

/**
 * Where the suite points. Defaults match stack/docker-compose.e2e.yml so a bare local run works;
 * CI and `workflow_dispatch` against an existing environment override them.
 */
export const E2E = {
  baseUrl: optionalEnv('E2E_BASE_URL', 'http://localhost:9990'),
  mailpitUrl: optionalEnv('E2E_MAILPIT_URL', 'http://localhost:8025'),
  /**
   * Object storage as the BROWSER sees it: presigned upload/download URLs point here directly, not
   * through the app (the stack's APP_STORAGE_ENDPOINT_URL; `storage` resolves via /etc/hosts).
   */
  storageUrl: optionalEnv('E2E_STORAGE_URL', 'http://storage:9500'),
  databaseUrl: optionalEnv(
    'E2E_DATABASE_URL',
    'postgres://postgres:e2e-postgres@localhost:55432/skillars',
  ),
  adminEmail: optionalEnv('E2E_ADMIN_EMAIL', 'admin@e2e.example.com'),
  adminPassword: optionalEnv('E2E_ADMIN_PASSWORD', 'E2e-Admin-Password-1!'),
  /** Every generated account shares one password; accounts themselves are unique per run. */
  accountPassword: optionalEnv('E2E_ACCOUNT_PASSWORD', 'E2e-Account-Passw0rd!'),
  /** The User entity accepts only 2-3 letter TLDs: example.com, never .test. */
  emailDomain: optionalEnv('E2E_EMAIL_DOMAIN', 'e2e.example.com'),
  runId: process.env.E2E_RUN_ID || process.env.GITHUB_RUN_ID || undefined,
  locale: optionalEnv('E2E_LOCALE', 'en-US'),
} as const

export const API_BASE_URL = `${E2E.baseUrl.replace(/\/+$/, '')}/api`
