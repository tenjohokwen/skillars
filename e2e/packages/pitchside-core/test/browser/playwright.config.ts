import { defineConfig, devices } from '@playwright/test'
import { baseRunPolicy } from '../../src/index.js'
import { TOY_PORT } from './toySuite.js'

const policy = baseRunPolicy()

// The bundled Chromium does not launch on every developer machine; set
// PITCHSIDE_BROWSER_CHANNEL=chrome to use an installed Google Chrome instead. CI uses the bundle.
const channel = process.env.PITCHSIDE_BROWSER_CHANNEL || undefined

export default defineConfig({
  ...policy,
  testDir: '.',
  testMatch: '*.spec.ts',
  outputDir: '../../test-results/browser',
  reporter: process.env.CI ? [['list'], ['github']] : [['list']],
  use: {
    ...policy.use,
    baseURL: `http://localhost:${TOY_PORT}`,
    testIdAttribute: 'data-testid',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], channel } }],
  webServer: {
    command: 'node app/server.mjs', // relative to this config's directory
    url: `http://localhost:${TOY_PORT}`,
    reuseExistingServer: !process.env.CI,
    env: { TOY_APP_PORT: String(TOY_PORT) },
    stdout: 'ignore',
  },
})
