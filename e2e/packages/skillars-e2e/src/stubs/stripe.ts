import type { BrowserContext } from '@playwright/test'

/**
 * The default ("deterministic") Stripe tier: js.stripe.com is never reached. The SPA loads Stripe.js
 * through @stripe/stripe-js — including, as the network gate found, for a guest on a public coach
 * profile — so every context gets this stub instead of the real script.
 *
 * It implements just enough of the Stripe.js surface for the SPA to initialise and render; card entry
 * itself is the `@payment-ui` tier's job (a richer stub) and real Elements is `@stripe-live` only.
 * `window.__stripeStub` counts calls so a spec can assert that a page did (or did not) load Stripe.
 */
const STRIPE_STUB = `
(() => {
  const calls = (window.__stripeStub = window.__stripeStub || { loads: 0, instances: 0 });
  calls.loads += 1;
  const element = () => ({ mount() {}, unmount() {}, destroy() {}, on() { return this }, off() {}, update() {}, focus() {}, blur() {}, clear() {} });
  window.Stripe = function Stripe() {
    calls.instances += 1;
    return {
      elements: () => ({ create: element, getElement: () => null, update() {}, fetchUpdates: async () => ({}), submit: async () => ({}) }),
      confirmSetup: async () => ({ error: { type: 'stub', message: 'Stripe is stubbed in this test tier' } }),
      confirmCardSetup: async () => ({ error: { type: 'stub', message: 'Stripe is stubbed in this test tier' } }),
      confirmPayment: async () => ({ error: { type: 'stub', message: 'Stripe is stubbed in this test tier' } }),
      retrieveSetupIntent: async () => ({}),
    };
  };
  window.Stripe.version = 'e2e-stub';
})();
`

export async function stubStripeJs(context: BrowserContext): Promise<void> {
  await context.route('https://js.stripe.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/javascript', body: STRIPE_STUB }),
  )
}

/**
 * The `@payment-ui` card-entry stub: like the default stub, but `confirmCardSetup` succeeds with
 * `paymentMethodId`, the way real Stripe.js does once a SetupIntent is confirmed. The app then saves
 * that id through its own endpoint, and the stack's WireMock answers every `pm_e2e_*` lookup as a
 * Visa ending 4242. Register it on a context before its first navigation: routes registered later win
 * over the default one from `prepareContext`.
 */
export async function stubStripeCardEntry(
  context: BrowserContext,
  paymentMethodId: string,
): Promise<void> {
  if (!paymentMethodId.startsWith('pm_e2e_')) {
    throw new Error(`The stack's WireMock answers only pm_e2e_* ids, got ${paymentMethodId}`)
  }
  const confirmed = `async () => ({ setupIntent: { status: 'succeeded', payment_method: ${JSON.stringify(paymentMethodId)} } })`
  const body = STRIPE_STUB.replace(
    /confirmCardSetup: async \(\) => \(\{[^\n]*\}\),/,
    `confirmCardSetup: ${confirmed},`,
  )
  if (body === STRIPE_STUB) throw new Error('stubStripeCardEntry: the stub no longer matches')
  await context.route('https://js.stripe.com/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/javascript', body }),
  )
}
