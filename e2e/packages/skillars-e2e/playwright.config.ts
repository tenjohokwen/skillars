import { defineConfig, devices } from '@playwright/test'
import { baseRunPolicy, laneProjects } from 'pitchside-core'
import { E2E } from './src/env.js'

const policy = baseRunPolicy()
// Set PITCHSIDE_BROWSER_CHANNEL=chrome where the bundled Chromium does not launch.
const channel = process.env.PITCHSIDE_BROWSER_CHANNEL || undefined

export default defineConfig({
  ...policy,
  testDir: './specs',
  outputDir: './test-results',
  globalSetup: './global-setup.ts',
  // Provisioning a baseline persona takes several API round trips; give the first test room.
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    ...policy.use,
    baseURL: E2E.baseUrl,
    testIdAttribute: 'data-testid',
    locale: E2E.locale,
    timezoneId: process.env.TZ || 'UTC',
  },
  projects: laneProjects({ use: { ...devices['Desktop Chrome'], channel } }),
})
