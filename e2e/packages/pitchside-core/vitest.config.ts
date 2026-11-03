import { defineConfig } from 'vitest/config'

// The kernel's own unit tests: pure logic and fakes, no browser. Browser-level self-tests live
// under test/browser and run through Playwright (npm run test:browser).
export default defineConfig({
  test: {
    include: ['test/unit/**/*.test.ts'],
    environment: 'node',
  },
})
