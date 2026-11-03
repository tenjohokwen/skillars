import { request } from '@playwright/test'
import { E2E } from '../env.js'

/**
 * How many PaymentIntents the backend has created for one payer, read from the stack's WireMock
 * request journal. Every charge carries `metadata[payerUserId]` (StripePaymentGateway), so this is
 * per-user even though every worker shares one WireMock.
 */
export async function chargesFor(payerUserId: string): Promise<number> {
  const context = await request.newContext()
  try {
    const response = await context.post(`${E2E.wiremockUrl}/__admin/requests/count`, {
      data: {
        method: 'POST',
        urlPath: '/v1/payment_intents',
        // Form-encoded; the brackets may or may not be percent-encoded.
        bodyPatterns: [
          { matches: `(^|.*&)metadata(\\[|%5B)payerUserId(\\]|%5D)=${payerUserId}(&.*|$)` },
        ],
      },
    })
    if (!response.ok()) throw new Error(`WireMock journal: HTTP ${response.status()}`)
    return ((await response.json()) as { count: number }).count
  } finally {
    await context.dispose()
  }
}
