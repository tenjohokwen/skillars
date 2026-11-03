import { pollUntil } from 'pitchside-core'
import { API_BASE_URL, E2E } from './src/env.js'

/**
 * Fails the run once, clearly, when the stack isn't up — instead of every test timing out on its
 * first navigation. Waits up to 3 minutes, enough for a cold app container to finish booting.
 */
export default async function globalSetup(): Promise<void> {
  const probe = async (url: string): Promise<true | undefined> => {
    const response = await fetch(url, { signal: AbortSignal.timeout(5_000) })
    return response.status < 500 ? true : undefined
  }
  await pollUntil(() => probe(E2E.baseUrl), {
    timeoutMs: 180_000,
    intervalMs: 2_000,
    describe: `the app at ${E2E.baseUrl} (start it with: npm run stack:up -w skillars-e2e)`,
  })
  // The SPA can be served before the API is ready; any non-5xx answer from the API means "up".
  await pollUntil(() => probe(`${API_BASE_URL}/account`), {
    timeoutMs: 180_000,
    intervalMs: 2_000,
    describe: `the API at ${API_BASE_URL}`,
  })
  await pollUntil(() => probe(`${E2E.mailpitUrl}/api/v1/info`), {
    timeoutMs: 60_000,
    intervalMs: 1_000,
    describe: `Mailpit at ${E2E.mailpitUrl}`,
  })
}
