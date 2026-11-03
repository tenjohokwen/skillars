import { request } from '@playwright/test'
import { E2E } from '../env.js'

/**
 * How many PaymentIntents the backend has created for one payer, read from the stack's WireMock
 * request journal. Every charge carries `metadata[payerUserId]` (StripePaymentGateway), so this is
 * per-user even though every worker shares one WireMock.
 */
export async function chargesFor(payerUserId: string): Promise<number> {
  return (await chargeRequestsFor(payerUserId)).length
}

/**
 * The distinct Stripe idempotency keys among one payer's PaymentIntent requests. The stub answers
 * every request with a new intent, where real Stripe would replay the first one for a repeated key;
 * so "charged once" means one key, however many times the backend retried with it.
 */
export async function chargeKeysFor(payerUserId: string): Promise<Set<string>> {
  const keys = (await chargeRequestsFor(payerUserId)).map(
    (r) =>
      Object.entries(r.headers).find(([name]) => name.toLowerCase() === 'idempotency-key')?.[1] ??
      '(none)',
  )
  return new Set(keys)
}

interface JournalRequest {
  headers: Record<string, string>
}

async function chargeRequestsFor(payerUserId: string): Promise<JournalRequest[]> {
  const context = await request.newContext()
  try {
    const response = await context.post(`${E2E.wiremockUrl}/__admin/requests/find`, {
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
    return ((await response.json()) as { requests: JournalRequest[] }).requests
  } finally {
    await context.dispose()
  }
}
