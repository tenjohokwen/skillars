# skillars-e2e

The Skillars adapter for the Pitchside UI test framework. Everything app-specific lives here: the
stack, the route map, login, account factories, screen objects and every spec. The portable kernel
is [`pitchside-core`](../pitchside-core/README.md); the boundary between them is the
`infrastructure` / `platform` rule (`_bmad-output/project-context.md` §6).

## Running locally

```sh
cd e2e && npm ci
npm run stack:up -w skillars-e2e        # app (image skillars:local by default), postgres, redis,
                                        # storage, Mailpit, WireMock — all disposable (tmpfs)
cd packages/skillars-e2e
npm run test:e2e                        # parallel lane, then the serial lane on one worker
npx playwright test --project=parallel --grep @smoke   # just the smoke set
npm run stack:down                      # tear down (data is gone either way)
```

- Build the image first if needed: `docker compose -f docker-compose.yml -f docker-compose.local.yml
build app` (tags `skillars:local`), or point `APP_IMAGE` at any image.
- Mailpit's UI is at http://localhost:8025 — every email the app sends during a run lands there.
- Running against an existing environment instead: set `E2E_BASE_URL`, `E2E_MAILPIT_URL`,
  `E2E_DATABASE_URL`, `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD`.
- On a Mac where the bundled Chromium won't start / Playwright has no ffmpeg:
  `PITCHSIDE_BROWSER_CHANNEL=chrome PITCHSIDE_VIDEO=off npx playwright test`.

In CI: add the `ui-e2e` label to a PR (smoke) or `ui-e2e-full` (everything), or dispatch the
`UI E2E` workflow with any `--grep` expression.

## How accounts are made

Personas are created the way a user would make them, over HTTP: register → the verification email in
Mailpit → follow the link → log in → fill the profile builder. Each worker gets its own **baseline**
personas on first use (`coach` — published, priced, with a pack tier, payment-ready;
`coach-incomplete`; `parent` with one 11-year-old; `player` — self-registered adult). A test that
changes a persona in a way other tests could see should create its own with the `provision` fixture.

Three things have no HTTP path without a real Stripe and are written with SQL (`src/data/provisioner.ts`,
narrowly): a COMPLETE Stripe Connect account (bookable coach), a card on file, an identity verdict.

Facts that shape the factories (all verified against the backend):

- Login requires an `fcookie` cookie (it becomes the session's client id), and sessions are pinned
  to the User-Agent — the kernel logs in with the test contexts' own UA.
- Emails must be lower-case with a 2–3 letter TLD: `e2e.example.com`, never `.test`.
- Phone numbers are unique per account.
- Phone OTP is off in the seeded config; verify-email alone activates an account.

## The stack

`stack/docker-compose.e2e.yml` is standalone (it does not layer on the production compose file).
`stack/config/application-e2e.yaml` is mounted into the app as an extra Spring config location, so the
production image carries no test configuration: SMTP to Mailpit (the `log` transport dumps bodies
without recipients, so it can't be searched by address), Gemini to WireMock (default verdict SAFE),
rate limiting off.

## Stripe

`src/stubs/stripe.ts` serves a local Stripe.js stub on every context — `js.stripe.com` is never
reached in the default tier. The network gate found the SPA loading Stripe.js for a guest on a public
coach profile, which is why the stub is global rather than per-page.
