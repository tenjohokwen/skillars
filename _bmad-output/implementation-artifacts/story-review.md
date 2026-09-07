# Senior Review — skillars-deferred-96 (Deployment Visibility: Smoke-Test Error Handling & Channel Health)

**Reviewer role:** senior dev audit for missed corner cases, false assumptions, missed flows.
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-96-deployment-visibility-smoke-test-error-handling-and-channel-health.md`
**Verified against:** `.github/workflows/deploy.yml`, `src/main/resources/application.yaml`, `src/main/java/.../platform/notification/**`, `pom.xml` @ HEAD (`d01f436`).

## Verdict

The story is chasing two real problems, but **both ACs are under-specified in ways that will produce a wrong or actively harmful implementation if handed to a dev as-is**:

- **AC1**: The root-cause analysis is partly wrong, and the "Option B" fix as written makes auto-revert *never* run. The realistic trigger for the bug is also mis-stated, and the proposed manual repro does not reproduce it.
- **AC2**: The single biggest issue in the whole story is unstated — a custom `HealthIndicator` that can report `DOWN` rolls into the aggregate `/manage/health`, **which the deploy smoke test greps for `"status":"UP"`**. A briefly-unreachable mail/Slack endpoint would then fail every subsequent deploy and auto-revert healthy releases. AC2 as specified regresses AC1. Additionally, the app has **zero Slack integration or config**, the cited property names don't exist, and a Slack incoming webhook cannot be health-checked without posting a message.

Recommend the story go back for revision before dev.

---

## AC1 — Smoke-test error visibility

### F1. "Option B" as written disables auto-revert entirely (high)

Story lines 53–57 propose, for the error case:

> Auto-revert: `if: steps.smoke.outcome == 'failure'`
> Pre-smoke notify: `if: steps.smoke.outcome == 'skipped' || steps.smoke.outcome == 'failure'`

In GitHub Actions, a step `if:` expression that contains **no status-check function** (`always()`, `failure()`, `success()`, `cancelled()`) is implicitly evaluated as `success() && (<expr>)`. If the smoke step has failed, `success()` is `false`, so `if: steps.smoke.outcome == 'failure'` is **always false** — the auto-revert step would never run in exactly the scenario the story is trying to fix. Same defect in the pre-smoke-notify rewrite. Only the marker rewrite (`if: always() && steps.smoke.outcome != 'success'`) is correct because it carries `always()`.

This is why the *current* workflow works today: when the smoke step completes with `result=fail` it still exits `0`, so `success()` holds and `if: steps.smoke.outputs.result == 'fail'` (deploy.yml:109, :141, :170) runs. The moment the smoke step itself errors, `success()` drops and all of those skip.

**Correction:** any conditional that must survive a failed smoke step needs an explicit status function — `if: failure() && steps.smoke.outcome == 'failure'` for revert/notify, `if: always() && …` for the marker.

### F2. Root cause is incompletely / inaccurately stated (medium)

Story line 42: *"Conditionals assume the smoke step completes (writing an output)…"* — that is only half of it. Even if the smoke step wrote `result=fail` before dying, the auto-revert and the four `result == 'pass|fail'` notification steps (deploy.yml:129, :141, :153, :170) would **still** skip on a smoke *error*, because none of them carry `always()`/`failure()` and are therefore `success()`-gated (see F1). Guaranteeing the output (Option A) is not sufficient on its own; the `if:` chains must also change.

Also line 33 / line 40: *"the run is [not] visibly marked as failed"* / *"Red run, no notification, no auto-revert"*. If the smoke step errors without `continue-on-error`, **the job is already red** — the marker step exists to catch the *opposite* case (smoke `result=fail` but auto-revert succeeds, which would otherwise be green). The genuine losses in the error case are (a) no auto-revert and (b) no failure notification — not "not marked failed". The AC should be reworded around those two losses.

### F3. Option A's "OR" is misleading and its con is wrong (medium)

Story line 46–50:

> - Add `continue-on-error: true` to smoke test step OR
> - Wrap smoke command in shell script that captures exit code and explicitly writes `result=…`
> - **Con:** Must ensure subsequent steps still fail the job on error (use separate `if: failure()` check)

Two problems:

1. `continue-on-error: true` **alone does not make `steps.smoke.outputs.result` get written**. If the step aborts mid-run (signal, `$GITHUB_OUTPUT` write failure), the output is still absent. Only the wrapper actually delivers the "outputs always available" property the story wants. The "OR" should be "AND (wrapper is mandatory; `continue-on-error` optional)".
2. With `continue-on-error: true`, the step's failure **does not make `failure()` true** and does not fail the job — so the story's suggested `if: failure()` recovery check will not fire. To still fail the job you need `if: always() && steps.smoke.outcome == 'failure'` (`outcome`, not `conclusion`).

### F4. Trigger list in the Overview overstates exposure (medium)

Story lines 12 & 64 cite *"ssh dies, timeout, permission denied"* as causes of the invisible failure. Inspecting the smoke loop (deploy.yml:93–104): every `ssh` invocation is inside `STATUS=$(ssh … 2>/dev/null || echo 0)`. An ssh that dies / is refused / hits "permission denied" exits non-zero, the `|| echo 0` absorbs it, `STATUS=0`, the loop runs to completion, and `result=fail` is written on line 105 — which today drives the full failure→revert→notify chain **correctly**. So two of the three enumerated triggers do **not** actually produce the bug.

The realistic triggers are narrower: the step/runner process being killed, the `echo … >> $GITHUB_OUTPUT` write failing (disk), or the step **hanging** until the job is cancelled (see F5). The story should say so, otherwise a dev "fixing" the ssh-failure path will conclude there is nothing to fix.

### F5. Hang → job-cancellation path is unhandled and un-mentioned (medium)

None of the `ssh` calls set `ConnectTimeout`/`BatchMode`, and neither the smoke step nor the job sets `timeout-minutes`. If the node becomes a network black hole, an ssh TCP connect can stall for the kernel default (~2h+); worst case the job hits the **default 360-minute** limit and is **cancelled**. On cancellation `failure()` is false and `steps.smoke.outputs.result` is unset, so the marker (deploy.yml:189), the auto-revert, and *every* notification skip — a broken deploy stays live with zero alerts, and the run shows as grey/cancelled rather than red. AC1 names "timeout" as in-scope but proposes nothing that addresses it. Fix belongs here: `ConnectTimeout=10 -o BatchMode=yes` on ssh + `timeout-minutes` on the step, plus an `if: always()` marker that also covers `cancelled()`.

### F6. The manual repro in AC1 does not reproduce the bug (low)

Story line 64: *"Kill ssh mid-execution (e.g. `ssh … & sleep 1; pkill ssh`)"*. Per F4, a killed ssh inside `$(… || echo 0)` just yields `STATUS=0`; the loop finishes and writes `result=fail`, exercising the path that already works. To actually make the smoke step *error* you must kill the step's shell or fault the `$GITHUB_OUTPUT` write. The verification steps as written give false confidence.

### F7. Notification "dead-zone" for `outcome == 'failure'` is only half-captured (low)

The story notes (line 55) that pre-smoke notifications gate on `steps.smoke.outcome == 'skipped'` and should also handle `== 'failure'`. Correct — on a smoke *error*, `outcome` is `'failure'`, not `'skipped'`, so deploy.yml:197 and :208 don't fire either. But the story's replacement expression drops the `failure()` guard, reintroducing F1. The intended expression is `if: failure() && (steps.smoke.outcome == 'skipped' || steps.smoke.outcome == 'failure')` — and the message text must then branch, because "failed before smoke test" (deploy.yml:200) is no longer accurate for the `failure` sub-case.

---

## AC2 — Notification-channel HealthIndicator

### F8. Custom HealthIndicator ➜ aggregate `/manage/health` ➜ breaks the deploy smoke test (HIGH — headline finding)

The deploy smoke test polls `http://localhost:8367/manage/health` and passes only if the body matches `"status":"UP"` (deploy.yml:96–97). `application.yaml` configures **no health groups** (`management.endpoint.health.group.*` is absent) and no probe groups, so **every `HealthIndicator` bean contributes to the root health group**, and the root `status` is the worst contributor. A `SlackHealthIndicator`/`SmtpHealthIndicator` that returns `Health.down()` when an *external* endpoint is briefly unreachable will:

1. flip `/manage/health` to `DOWN`,
2. fail the smoke test of the **next** deploy,
3. trigger **auto-revert of a perfectly healthy release**, and
4. fire a false "deploy FAILED" notification.

AC2 as written therefore *regresses* AC1. Any version of AC2 must isolate these indicators from the liveness/readiness rollup the smoke test observes — e.g. put the smoke test on a dedicated `management.endpoint.health.group.<name>` that excludes them, or register them as non-system-health details. This constraint is not mentioned anywhere in the story and is the first thing a dev will trip over.

Related: `management.endpoint.health.show-details: when-authorized` (roles `ROLE_ADMIN`) means an unauthenticated caller only ever sees the top-level `status`. That's fine for the smoke grep, but it also means the "add to ops dashboard so channel health is visible" goal (AC2, line 109) requires an authenticated scrape — worth stating.

### F9. The application has no Slack integration or configuration at all (HIGH)

`grep -rn -i slack src/main` returns only a blacklist data file. There is **no Slack client, no webhook property, no Slack code path** in the app. Slack notifications are sent **exclusively by GitHub Actions** (`secrets.SLACK_WEBHOOK_URL`, deploy.yml:135/147/202) from **GitHub-hosted runners**. Consequences:

- A `SlackHealthIndicator` in the Spring app has **no URL to read** — the property it needs does not exist (see F11).
- Even if given the URL, it would test **prod-node → Slack** egress, which is *not* the path that delivers deploy alerts (**GitHub runner → Slack**). A firewall/proxy issue on the runner side — the thing that actually breaks deploy notifications — would be invisible to this check, and vice-versa.
- So AC2's Slack half does not measure the risk described in the story's own problem statement ("Slack webhooks that are down remain invisible in CI/CD").

If the intent is genuinely to catch a dead deploy-notification webhook, that belongs in the **workflow** (a lightweight post-to-Slack assertion / `#deploys` heartbeat job), not an app `HealthIndicator`.

### F10. A Slack incoming webhook cannot be health-checked without posting a message (medium)

Story line 81 / line 124: *"webhook validation ping if supported by Slack API"*. Slack **incoming webhooks have no validation or ping endpoint**. Options are: (a) POST a real message (channel noise on every `/manage/health` poll — unacceptable), or (b) POST a deliberately malformed body and infer liveness from a `400 invalid_payload` — fragile, still counts against Slack rate limits, and does not cleanly distinguish a live webhook from a disabled/revoked one (`404 no_service` vs `403`/`410`). The story's stated mechanism does not exist; this needs to be called out so the design doesn't assume it.

### F11. Cited property names are fabricated (medium)

Story lines 82 & 89 reference `app.notification.slack-webhook-url` and `app.email.smtp-host` and claim *"App properties already exist for SMTP and Slack configuration."* Actual config:

- Email: `email.providerConfigs[].{name,host,port,username,password}` (two providers: gmx + gmail, round-robin via `MailSenderProvider`) — `EmailProperties`, `application.yaml:144–158`.
- Spring mail: `spring.mail.{host,port,username,password}` (`mail.gmx.net`) — a **separate** config, `application.yaml:118–128`.
- Slack: **none**.
- There is no `app.*` property namespace.

A dev following the story will look for properties that aren't there. The "Re-Verification Against HEAD" section (story lines 139–147) claims all citations were verified, but the AC2 property names and the endpoint path (F13) were not.

### F12. EHLO-only SMTP check misses the failure mode that actually matters (medium)

The app sends mail via STARTTLS + AUTH on port 587 (`MailSenderProvider`: `mail.smtp.auth=true`, `starttls.enable=true`). An unauthenticated EHLO handshake (story lines 80, 124) proves TCP + SMTP banner reachability only. The way these channels **silently break in practice** is credential/app-password expiry or revocation (GMX/Gmail app passwords), which an EHLO probe will report as `UP`. The story's framing ("a down channel surfaces") over-promises: the most common "down" is exactly what this check can't see. If auth is included in the probe, that's a login attempt to an external provider on every health poll — see F14.

### F13. Endpoint path is wrong throughout AC2 (low)

The story repeatedly says `/actuator/health` (lines 78, 108, 111, 122, 165, 180). The app's management base-path is `/manage` on port 8367 (`application.yaml:360–367`); the real endpoint is `http://<host>:8367/manage/health`. Cosmetic, but it's in the acceptance checklist and "manually verified" steps.

### F14. External-provider side effects / latency on `/manage/health` (medium)

`/manage/health` is polled by uptime monitors, load-balancer probes, and (per story) an ops dashboard. Opening an outbound TCP+STARTTLS(+AUTH) connection to `mail.gmx.net` and `smtp.gmail.com` — and an HTTPS POST to Slack — on **every poll** risks connection-rate throttling or transient IP blocklisting by the mail providers, and adds their RTT to every health scrape. The story's own Technical Requirements say `/actuator/health` must not become a bottleneck (line 122) but propose no mitigation. The design needs a scheduled background probe writing a cached result (or `management.endpoint.health.group` + TTL), not a synchronous check per request.

### F15. "The SMTP config" is actually three configs, two providers (low/medium)

AC2 treats SMTP as one host. There are two `email.providerConfigs` entries (gmx, gmail) plus a separate `spring.mail` host. A `SmtpHealthIndicator` must decide: check all providers? Rollup semantics (all-down = `DOWN`, any-down = degraded/`OUT_OF_SERVICE`)? Which config is authoritative? None of this is specified.

### F16. `UNKNOWN`-when-unconfigured still appears in the aggregate (low)

Story line 84: report `UNKNOWN` if endpoints aren't configured. Spring's default `SimpleStatusAggregator` ranks `UNKNOWN` *above* `UP`, so it won't force the aggregate `DOWN` (good, mitigates F8 for that sub-case) — but the indicator still shows in `/manage/health` details and in any "all green?" dashboard logic. Cleaner to not register the bean when the relevant config is absent (`@ConditionalOnProperty`).

### F17. Test-naming / layering unspecified vs. project convention (low)

Story lists `SlackHealthIndicatorIT` / `SmtpHealthIndicatorIT` (Testcontainers-style `*IT`) *and* separately asks for "unit tests" (lines 96–97, 106–107). The repo uses `*IT.java` for Spring/integration and `*Test.java` for pure unit (`src/test/...`). A real Slack webhook call + real SMTP EHLO in an `IT` (story line 107) will be flaky/blocked in CI. Decide: mocked `RestClient`/`JavaMailSender` unit tests for UP/DOWN logic, and keep any real-endpoint check out of CI.

---

## What the story gets right

- The **core AC1 gap is real**: if the smoke step errors (rather than completing with `result=fail`), the auto-revert and all failure notifications silently skip. (Mechanism is mis-diagnosed — see F1/F2 — but the gap exists.)
- **deploy.yml line citations are accurate**: auto-revert `if` at :109, marker at :188–194, pre-smoke notifications at :196/:207, and the `steps.smoke.outcome == 'skipped'` gating.
- The observation that **deferred-94's `continue-on-error: true` removed the CI failure signal** for down notification channels is correct (deploy.yml:130/142/154/171).
- `spring-boot-starter-actuator` is present (`pom.xml:167`) and the app has real email infrastructure (`platform.notification`), so an app-side **SMTP** health check is at least feasible in principle.
- Correctly scopes out DB schema / new external APIs.

---

## Recommended changes before dev

1. **AC1 — rewrite the fix:** mandate the wrapper-script approach (smoke step always writes `result=pass|fail|error` via `set +e`/`trap`), *and* change every downstream `if:` to carry an explicit status function (`failure() && …` for revert/notify, `always() && …` for the marker). Drop "Option B" or fix its three expressions. Add `ConnectTimeout`/`BatchMode` to ssh and `timeout-minutes` to the step; make the marker cover `cancelled()`. Replace the `pkill ssh` repro with one that actually kills the step shell.
2. **AC1 — restate the bug** as "no auto-revert + no failure notification on smoke *error/cancel*", not "run not marked failed".
3. **AC2 — resolve the aggregate-health coupling first (F8):** define a dedicated health group for the deploy smoke test that excludes these indicators, or drop the `HealthIndicator` approach.
4. **AC2 — drop the Slack `HealthIndicator`** (F9/F10/F11): the app has no Slack config and can't probe an incoming webhook cleanly. If deploy-webhook liveness matters, add a workflow-side check instead.
5. **AC2 — SMTP check:** fix property names to `email.providerConfigs[*]` / `spring.mail.*`, specify multi-provider rollup, decide auth-vs-EHLO (and accept EHLO won't catch credential expiry), and design it as a cached background probe, not per-request. `@ConditionalOnProperty` so it's absent when unconfigured.
6. Fix `/actuator/health` → `/manage/health` (port 8367) everywhere, and note that channel identity is only visible to authenticated `ROLE_ADMIN`.
