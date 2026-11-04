# Story review: skillars-deferred-167-per-session-jwt-revocation-and-refresh-chain-follow-ups

Audit date 2026-11-03. Real HEAD at review time: `b3b0d647 skillars-deferred-167: record the five owner decisions and create the story`
(source files are unchanged since `21e72013`). Every citation below was checked against this HEAD, not against any
SHA the story states. Static review only: nothing was executed, so every runtime claim rests on reading code.

Layers: 1 citations (66 checked), 2 ledger/precedent attributions (12), 3 mechanistic claims (8 groups), 4 corner cases (9 hypotheses).
Step 4b: I re-read the cited lines myself for the findings that change the design (H1, H2, H3, the clock hook).

## 1. Citation verification

62 of 66 MATCH. Real non-matches:

| citation | verdict | correct location |
|---|---|---|
| `JWTAuthorizationFilter` "comment at `:228-246`" | DRIFTED | comment is `:224-241`; `:242-247` is code |
| `AuthService` window test "`:485`" | DRIFTED | `:487-488` (`:485` is the comment above it) |
| `AuthService` `used` branch "`:332-344`" | DRIFTED (minor) | `:333-345` |
| `JwtManagerImpl:77` "carried over by hand" | PARTIAL | `:77` is the read; the value reaches the claims as an argument to `toClaims` at `:79` |

Everything else (the V224 text, `UserRepository` propagation, `User.java:166-167`, all `AuthResourceIT` helper lines,
`assert-context-count.sh`/`pr-build.yml:127`, the context-key constants) matches.

## 2. Ledger and precedent attribution

| claim | sources checked | verdict |
|---|---|---|
| 144 AC8 removed the per-request refresh_tokens query | code comment `JWTAuthorizationFilter:224-241`, `GdprErasureIT:401`, `JWTAuthorizationFilterTest:171`; no ledger entry; `git log --grep` empty | CONFIRMED (code and tests only) |
| 166 AC3 (revoked replay never revokes all) | ledger, `AuthService` `Revoked`, `f0ad7706` body | CONFIRMED |
| `isBeforeSessionEpoch` javadoc names the three callers | `AuthService:~392-403` | CONFIRMED, and it must be rewritten by AC8 |
| #413 rounds 2 and 3 found defects from the prior round's fix | `f0ad7706` body, 166 story | CONFIRMED |
| 5 s `lock.timeout` added then removed | `f0ad7706` body, ledger | CONFIRMED |
| "#413 hung an IT phase 19 minutes on a `REQUIRES_NEW` revoke of a row the caller already wrote" | 166 story, commit body | PARTLY: the rows were locked by the walk, not written |
| "five items filed by the reviewer arms" | ledger | PARTLY: the VALIDATE item was filed by #413's author, not the arms |
| every decision is recorded in the ledger, "do not re-litigate" | ledger `:71` | **MISATTRIBUTED for AC7**: the ledger still says "measure the window from the chain's live end", the opposite of AC7. The re-decision exists only in chat. Fixed in the ledger as part of this review |
| "the merge-hotspots note in `deferred-work.md`" | ledger, repo-wide grep | **NOT SUPPORTED**: no such note. Real support: renumber commits `0621d23f`, `dce987cb`, `67d7f945` and `docs/dev-docs/README.md` (search-index is generated) |
| `GdprErasureService` revokes, never stamps the epoch | `:810`, grep of the file | CONFIRMED |
| V224 promises the VALIDATE; V225 is the first executed one | V224 header, `provisioning-checklist:684`, `V145:49` is a comment | CONFIRMED |

## 3. Mechanistic claims

- **`extendTtlOfToken` rebuilds the map** (`JwtManagerImpl:79`, `:87-90`): CONFIRMED. A new claim must be forwarded by hand.
- **`SESSION_ID` is the browser session id** (`TokenValidatorImpl:39-42`, `JWTAuthorizationFilter:201-202`): CONFIRMED. A second consumer exists at `JwtManagerImpl:184`.
- **DB branch vs fast path** (`TokenValidatorImpl:48-54`, filter `:206-221`, `:222-248`): CONFIRMED.
- **"live end's `createdAt` is the same instant as `rotatedAt`": WRONG.** `now` is taken at `AuthService:308`, before the lock wait; the successor's `createdAt` is set by `@PrePersist` at `saveAndFlush` (`RefreshToken:42-47`). `createdAt = rotatedAt + rotation latency`. The AC7 correction survives (the window is keyed on `presented.getRotatedAt()` either way) but the sentence must be reworded.
- **Transaction boundary of `refresh`** (`:204`, `:215`): CONFIRMED. All revokes and throws run after the template.
- **Concurrent-refresh divergence** (`:341` onward rotates the live end, chain goes A0 to A1 to A2): CONFIRMED.
- **AC8 reorder**: the swap works because a surviving successor was inserted before the revoke committed, so its `createdAt` precedes an epoch computed afterwards. It does **not** make the window fully closed: `decideAndRotate` reads the user with a plain `findById` (`:322`), which does not block on the suspender's lock, and multi-node clock skew can invert the comparison. The javadoc rewrite must not claim closure.
- **AC9 fix**: an ascending-id native delete does remove the cycle against the chain walks. It does not cover `markAllUsedByUserId`/`markAllRevokedByUserId`, which are unordered bulk UPDATEs (the `AuthService:225-229` comment already names them as a deadlock source).
- **AC10 (nothing consults `refresh_tokens` on the reauth branch)**: CONFIRMED.

## 4. Findings that survived re-verification

**H1 (high). AC7 gives up the canonical theft signal, and the story, and my own option text, said it did not.**
`decideAndRotate:333-339` plus `resolveGraceSuccessor:482-490`: a used token whose chain has a live end but whose rotation is
outside 30 s returns `none()`, so the outcome is `Reuse` and `markAllUsedByUserId` + `invalidateSessionsForUser` run
(`:274-275`). That is exactly what happens when an attacker rotates a stolen refresh token first and the victim later presents
the stale cookie. After AC7 the victim gets a 401, the attacker's chain stays live for up to 7 days, and nothing is logged.
When I offered this option I described it as "no weakening of theft detection". That was wrong. What remains detected: a
replay when no live end exists. What is lost: replay of a rotated token against a still-live chain after 30 s.
Required: state the loss in AC7 and `docs/session-refresh-mechanism.md`, and log at `warn` / count a metric (no DB write needed).
Exposure note: the SPA never calls `POST /api/auth/refresh`, so only API or mobile clients are affected today.

**H2 (high). The `rtkn` derivation in AC3 is dead code, and null ids propagate forever.**
Row creation is only `AuthService:146` (login) and `:362` (rotation). Pre-V225 rows have a null `session_id` and AC1 forbids a
backfill, so deriving from the cookie's row yields null for exactly the sessions the bullet says it helps. Worse, AC2 says
rotation copies the predecessor's id, so a pre-V225 chain stays null **through every rotation**: a continuously refreshing
API client never acquires an id. Also the story misstates the uncovered attacker: a post-deploy `potc` already carries the
claim, so a thief with only that `potc` is covered; the uncovered thief holds a **pre-deploy** `potc`.
Fix: drop the derivation; in `decideAndRotate`, mint a fresh id when the predecessor's is null; the next `createLoginToken`
then carries it. The fail-closed flip can follow one `REFRESH_TOKEN_TTL` (7 days) after deploy. This also removes the
second query (the story's "exactly one query" in AC4 contradicts the derivation), the new repository dependency the filter
javadoc says `renewLoginToken` does not have (`:83`), and the unchecked cross-chain pairing of a `potc` with a different `rtkn`.

**H3 (high if implemented as written). AC9's "log at warn" cannot work inside the current method.**
`purgeExpiredRefreshTokens` is `@Transactional` (`AuthCleanupService:38-45`) and `deleteExpiredTokens` is itself
`@Modifying @Transactional` (`RefreshTokenRepository:233-235`) so it joins that transaction. A deadlock thrown through the
participating proxy marks the transaction rollback-only; a catch in the body then still ends in `UnexpectedRollbackException`
from the outer commit, and Postgres has already aborted the transaction. The catch must sit outside the transaction (a
non-transactional wrapper, or a `TransactionTemplate` inside the lock). I verified the annotations, not the runtime behaviour.
AC9 should also require a real test of the ordered delete including the `ON DELETE SET NULL` path, which nothing exercises.

**M2 (medium). Plumbing is larger than Tasks 3 and 4 say.** `LoginTokenManager.createLoginToken/renewLoginToken` have no
parameter for an id, the `Rotated` record carries none, `toClaims` is called from `JwtManagerImpl:45,58,79`, and
`JWTAuthorizationFilterTest:471,503` verify exact `renewLoginToken(response, principal)` arguments. AC2's "predecessor's id"
is ambiguous in a grace redirect: the row rotated is the live end (`token`), not `presented`.

**M4 (low-medium). V225 holds `ACCESS EXCLUSIVE` across the index build and the validation scan.** One Flyway transaction,
so `ADD COLUMN`'s lock is held through `CREATE INDEX` and `VALIDATE`, which defeats rule 3's stated reason for validating in
a *later* migration. The lint does not look for this. Acceptable on a small, not-yet-live table; say so in the migration.

**Test hook (medium). The story's clock instructions do not work for HTTP ITs.** `ClockProvider` is a `ThreadLocal`
(`ClockProvider.java`) and the embedded server handles requests on its own thread (`AuthResourceIT:391-401` says so).
AC6/AC7/AC10 tests must change state through `commitWrite` (`rotated_at`, `expires_at`) or hand-build a JWT as `:402-415` does.

**AC8 reactivate half is probably unnecessary.** Suspend already marks every token used and stamps a monotonic epoch that
is never cleared, so pre-suspension tokens and JWTs stay refused after reactivation. Once the statements are swapped, the
only token a revoke-on-reactivate could still catch is a race successor that is already refused. The ledger entry claimed
otherwise. This is a re-read of the same lines, not an executed test; it deserves an IT before the owner decides to keep the work.

## 5. What did not survive re-verification

- Layer 1 rows 30 and 56 were flagged DRIFTED by the agent itself while noting the content was correct: dropped.
- Layer 4 M3 (`expires_at > now()` ignoring `ClockProvider`): downgraded to a nit. In HTTP ITs `ClockProvider` already falls back to the system clock on the server thread, so a DB `now()` and `Instant.now()` agree; it only matters for unit tests. A `:now` parameter is still the cleaner shape.
- "Mid-rotation moment with zero live rows": dismissed; rotation is one transaction.
- Orphan `replaced_by_id` breaking `VALIDATE`: dismissed; `ON DELETE SET NULL` applies even to a `NOT VALID` FK and ids are written only from flushed successors.
- `NativeModifyingVersionAuditTest` rejecting the native delete: dismissed; it skips statements starting with `DELETE`.
- Layer 4's claim that the reactivate "authorisation hole" is real: not accepted, see above.

## 6. Recommendation (per claim, not a blanket score)

- High confidence, re-read by me: H1, H2, H3, the clock hook, the `createdAt`/`rotatedAt` correction, the ledger contradiction.
- Medium confidence, from agent reports and unrun: M2 (a grep-able omission), M4, the AC8 reactivate redundancy, the rollback-only mechanics in H3.
- Not independently re-checked: `NativeModifyingVersionAuditTest`'s logic, Layer 4's SSE timeout claim, cross-node clock skew.
- Not re-checked at all: whether `session_id uuid` fits the project's id conventions beyond one precedent (`admin.gdpr_requests`).

Do not run `dev-story` until H1 (an owner decision, below) and H2/H3 are folded into the ACs.
