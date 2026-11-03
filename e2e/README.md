# e2e — Pitchside UI test framework

Playwright browser tests for Skillars, split so the infrastructure is reusable on other projects:

| Package                                                        | Role                                                                                                                       | Status      |
| -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | ----------- |
| [`packages/pitchside-core`](packages/pitchside-core/README.md) | The portable kernel: fixtures, auth strategies, mail sinks, seeding contract, gates, tag taxonomy. Imports no application. | Implemented |
| [`packages/skillars-e2e`](packages/skillars-e2e/README.md)     | The Skillars adapter: disposable stack, hash-route map, `skp` cookie login, factories, screen objects, and every spec.     | Smoke set   |

The design, constraints and the 313-scenario plan live in the "Pitchside" proposal artifact.

## Ground rules

- **Opt-in, never on the build path.** Nothing here is part of `mvn verify`, `ci.yml` or `pr-build.yml`,
  and the Docker image ignores this directory. Lint, typecheck and the kernel's tests run in
  `.github/workflows/pitchside-core.yml` on PRs that touch `e2e/**`; the browser suite itself runs in
  `.github/workflows/ui-e2e.yml` only when asked (PR label `ui-e2e` / `ui-e2e-full`, or dispatch).
- **One selector policy:** `data-testid`, lower-kebab-case, declared once in a screen object.
- **No sleeping:** `waitForTimeout` is a lint error. Wait for a locator, a response or a URL.
- **Formatting:** Prettier with the repository's frontend settings (`npm run format`).

## Commands

```sh
npm ci                 # install (Node >= 22.16)
npm run format:check
npm run lint
npm run typecheck
npm run build
npm test               # unit tests of every package
```
