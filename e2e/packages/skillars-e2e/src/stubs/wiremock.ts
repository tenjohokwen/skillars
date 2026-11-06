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
      Object.entries(r.headers).find(([name]) => name.toLowerCase() === 'idempotency-key')?.[1],
  )
  // A keyless charge can't be deduplicated by Stripe at all: two of them are two charges, never one
  // "missing" key. Fail loudly rather than fold them together.
  if (keys.some((key) => !key)) {
    throw new Error(`A PaymentIntent for payer ${payerUserId} was sent without an Idempotency-Key`)
  }
  return new Set(keys as string[])
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

/** One refund the backend asked Stripe for: the PaymentIntent and the amount in cents. */
export interface RefundRequest {
  paymentIntent: string
  amountCents: number
}

/**
 * The refunds the backend has requested against the given PaymentIntents, read
 * from the WireMock journal (refused ones included). Refunds carry no payer metadata, so a spec
 * filters by the ids it seeded (provision.seedWalletCredit with `fundedBy`).
 */
export async function refundsOn(paymentIntents: string[]): Promise<RefundRequest[]> {
  const context = await request.newContext()
  try {
    const response = await context.post(`${E2E.wiremockUrl}/__admin/requests/find`, {
      data: { method: 'POST', urlPath: '/v1/refunds' },
    })
    if (!response.ok()) throw new Error(`WireMock journal: HTTP ${response.status()}`)
    const { requests } = (await response.json()) as { requests: { body: string }[] }
    return requests
      .map(({ body }) => new URLSearchParams(body))
      .map((form) => ({
        paymentIntent: form.get('payment_intent') ?? '',
        amountCents: Number(form.get('amount')),
      }))
      .filter((refund) => paymentIntents.includes(refund.paymentIntent))
  } finally {
    await context.dispose()
  }
}

/**
 * How many moderation calls reached the Gemini stub with `text` in their prompt. Every message is
 * moderated with its text verbatim inside the prompt, so a unique token per test makes this per-test
 * even though every worker shares one WireMock.
 */
export async function geminiCallsContaining(text: string): Promise<number> {
  const context = await request.newContext()
  try {
    const response = await context.post(`${E2E.wiremockUrl}/__admin/requests/count`, {
      data: {
        method: 'POST',
        urlPathPattern: '/v1beta/models/[^/]+:generateContent',
        bodyPatterns: [{ contains: text }],
      },
    })
    if (!response.ok()) throw new Error(`WireMock count failed: ${response.status()}`)
    return ((await response.json()) as { count: number }).count
  } finally {
    await context.dispose()
  }
}
