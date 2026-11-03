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
