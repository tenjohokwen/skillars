# PRD Quality Review — Video Storage & Streaming Module

## Overall verdict

This is a technically solid PRD for an internal platform module — the integration contract is clearly specified, scope boundaries are honestly drawn, and the provider-agnostic design thesis is coherent and consistently applied throughout. The main weaknesses are in Done-ness Clarity: several functional requirements describe behavior without testable exit criteria, and two of the four NFR sections rely on adjectives rather than bounds. One mechanical inconsistency (TEAM vs. GROUP in the data model) and a duplicate section-8 heading are the only structural defects. The PRD is ready for architecture and story creation with targeted fixes to the low-testability FRs and one clarification on the data model enum mismatch.

---

## 1. Decision-readiness — adequate

The PRD surfaces the three most consequential design decisions cleanly: `QuotaProvider` as an interface rather than a configurable module default, moderation omitted in favor of an `accessState == BLOCKED` hook, and Bunny.net as MVP provider. Each is stated as a decision, not a "consideration." The addendum's Rejected Alternatives section (A7) directly backs the first two, giving a decision-maker solid rationale without having to dig into prose.

The trade-offs are honest: GROUP visibility enforcement is explicitly delegated to consuming apps; playback optimization features are scoped as SHOULD with an explicit deferral reason. The open-questions section is honest about its own emptiness ("all resolved; see decision-log"), which is fine — but the referenced `.decision-log.md` was not provided. If the decision-log does not exist yet, that gap should be documented rather than cited.

The `[NOTE FOR PM]` callout pattern is not used at all. There are two genuine tensions that warrant this treatment: (1) the dual-confirmation path in FR-4 (webhook vs. client signal) introduces ambiguity about which path is authoritative when both arrive close together; and (2) FR-7 defers default rate limit values entirely to implementation. Neither is fatal, but a PM reviewing scope commitment should know these decisions were not made at PRD time.

### Findings

- **medium** Missing decision on FR-4 dual-confirmation authority (§4.1 / FR-4) — The PRD states both webhook and client-signal paths advance the Upload Session to COMMITTED, handled idempotently. It does not state which path is expected to arrive first in the happy path, or what happens if only the client signal arrives and the webhook never comes. This matters for reconciliation logic. *Fix:* Add a `[NOTE FOR PM]` or clarifying sentence: state whether the module trusts client signals for COMMITTED transitions absent a webhook, or whether that combination triggers reconciliation.
- **low** `.decision-log.md` cited but not present (§8 Open Questions) — The PRD asserts all questions are resolved and defers to a decision log that was not provided. This is not a PRD defect per se, but downstream teams cannot verify the rationale. *Fix:* Either include the decision log in the same directory or note that it is forthcoming.

---

## 2. Substance over theater — strong

**Vision** — Not theater. The Vision paragraph names a concrete constraint ("contains no business rules that vary by application") and a concrete outcome ("production-ready video infrastructure on day one, backed by a swappable provider adapter, composable with no source edits"). It would not survive being swapped unchanged into an unrelated PRD. Passes.

**Personas** — Two personas (App Developer, End User as indirect), each doing real work. The App Developer persona directly drives the integration contract and the JTBD list. The End User as indirect is honest about their mediated relationship to the module. No persona theater.

**NFRs** — Mixed. Performance targets (§5.1) are specific and earned: p99 latency numbers, availability percentages, a 5-minute reconciliation bound. Security NFRs (§5.2) are specific — signed URLs, webhook signature validation, rate limiting, no credentials in logs — not boilerplate. Reliability (§5.3) mentions dead-letter queues and idempotency, which are earned from the dual-track processing design. Observability (§5.4) is a one-liner deferring to the project convention. That's appropriate for a platform module; it's not theater, just lightweight.

**Success Metrics** — Not theater. SM-1 and SM-2 are testable adoption/portability metrics directly tied to the thesis. Counter-metrics SM-C1 and SM-C2 are explicit about what must not happen, which is rare and valuable.

No findings warranted here.

---

## 3. Strategic coherence — strong

The PRD has a clear thesis: generic provider-agnostic video infrastructure that any javatemplate app can adopt on day one without understanding provider mechanics, and without modifying the module for app-specific rules. Every feature traces back to this thesis:

- Upload Management exists because the thesis requires quota delegation without module coupling.
- Provider Abstraction exists because the thesis requires zero `platform.video` changes on provider swap.
- Playback Authorization exists because the thesis requires security handled by the module, not reconstructed per-app.
- Reconciliation exists because the thesis requires reliability without burdening consuming apps with webhook resilience.

Feature prioritization follows logically. MVP scope includes exactly the capabilities required to validate SM-1 (module adoptability) and SM-2 (provider portability). Deferred features (FR-35–37, additional providers) are deferred because they are provider-dependent enhancements, not core-contract requirements — this is coherent reasoning, not "what's easy first."

Success metrics validate the thesis rather than measuring activity. Counter-metrics actively guard against scope drift (business logic leakage, proxy upload traffic).

No findings warranted here.

---

## 4. Done-ness clarity — thin

This is the weakest dimension. Many FRs state obligations clearly but stop short of testable exit conditions. The rubric is unforgiving here because story creation and acceptance testing lean on this hardest.

**Strong FRs** — FR-3 (no video bytes through backend), FR-10 (exact eligibility gate), FR-11 (DELETED is terminal), FR-13 (no unsigned URLs through any service path), FR-17 (15-minute default TTL, 2-hour max, both configurable), FR-26 (idempotency — same webhook twice yields same final state), FR-28 (reconciliation cycle within 5 minutes) all have testable consequences.

**Weak FRs:**

- FR-6 (upload validation): "validate file size, MIME type, and container format" — no allowed types, no size bounds, no enumeration of container formats. An engineer writing a test cannot determine what values to use.
- FR-7 (rate limiting): "rate-limited per caller" — no default values, no unit (requests per second? per minute?), deferred to implementation. Testable only after implementation decides.
- FR-9 (Access State): Defines states but has no Consequences block. It is unclear what a state transition to ARCHIVED entails — does it require a module operation, or can the consuming app set it directly?
- FR-12 (FAILED retry): "eligible to restart the upload flow" — no specification of what "restart" means. Does it create a new Video entity, or a new Upload Session on the existing Video? Does it reset Operational State to UPLOADING?
- FR-15 and FR-16: Token requirements are well-specified (FR-15), but FR-16 uses SHOULD for claims that are load-bearing for revocation (userId, sessionId). If userId is optional, how does FR-18 revocation by viewerId work? The relationship between viewerId (FR-18) and userId (FR-16) is unstated.
- FR-24 (webhook processing): "must process provider events" — no enumeration of which event types are required. What events must the module handle? What happens to unrecognized event types?
- FR-25 (polling fallback): No polling interval specified. The 5-minute SLA is in FR-28, but FR-25 itself does not reference it, leaving ambiguity about whether FR-25 has its own testable condition.
- FR-32 (admin endpoint): "`@PreAuthorize` using `SecurityConstants`" — SecurityConstants is a project convention. This is specific enough for the project context but an engineer would need to know which SecurityConstants role is expected.
- FR-35–37 (playback optimization): All SHOULD with no conditions — fine for deferred features, but no criteria for when they count as "done" if implemented.

### Findings

- **high** FR-6 lacks allowed types and size bounds (§4.1 / FR-6) — "validate file size, MIME type, and container format" names the validation axes but not their values. An engineer implementing or testing this requirement has nothing to work from. *Fix:* Add a table of allowed MIME types (e.g., `video/mp4`, `video/quicktime`), allowed container formats, and maximum file size. If these are configurable, say so and name the Spring property.
- **high** FR-12 is ambiguous about retry semantics (§4.2 / FR-12) — "A Video in FAILED Operational State MUST be eligible to restart the upload flow" does not specify whether retry creates a new Video or a new Upload Session on the existing Video, or what state transitions occur. *Fix:* Add a Consequences block: e.g., "A new Upload Session is created for the existing Video; Operational State transitions to UPLOADING on session creation. The Video ID is preserved."
- **medium** FR-7 rate limit values fully deferred (§4.1 / FR-7) — No default values, no units. This is acknowledged as an assumption but leaves both the FR and any story unimplementable without a further decision. *Fix:* Provide strawman defaults (e.g., "default: 10 requests per minute per caller, configurable via `video.upload.rate-limit.rpm`") and mark them as provisional.
- **medium** FR-16 SHOULD claims are load-bearing for FR-18 (§4.3 / FR-16, FR-18) — userId/sessionId are SHOULD in FR-16, but revocation in FR-18 targets viewerId. The relationship between viewerId (revocation key) and userId (token claim) is not defined. *Fix:* Promote userId to MUST if it is the viewerId claim used for revocation lookup, or explicitly state how viewerId maps to token claims at revocation time.
- **medium** FR-24 does not enumerate required webhook event types (§4.5 / FR-24) — "provider events" is underspecified. Bunny.net emits multiple event types; not all are relevant. *Fix:* List the required event types (e.g., `video.uploaded`, `video.transcoded`, `video.failed`) and specify handling for unrecognized events (log and discard? reject?).
- **low** FR-9 Access State has no Consequences block (§4.2 / FR-9) — Unlike FR-8, FR-9 defines states but does not describe how Access State changes (consumer sets it via Admin layer? automatic?). *Fix:* Add a Consequences block or cross-reference FR-31 explicitly.
- **low** FR-25 does not state polling interval (§4.5 / FR-25) — The 5-minute SLA is in FR-28, not FR-25. FR-25 as written is testable only via FR-28. *Fix:* Add a cross-reference: "The Reconciliation Worker polling interval must be set such that the SLA in FR-28 is met."

---

## 5. Scope honesty — strong

Non-Goals (§6) are specific and do real work: subscription billing, moderation decisions, group membership enforcement, DRM, enterprise compliance, end-user REST controllers, public video visibility. Each of these could plausibly be assumed in-scope without the explicit denial.

The `[NON-GOAL for MVP]` callout pattern is not used inline, but the Non-Goals section covers the important cases. The most useful inline callout that's missing is on FR-34 (Visibility enforcement boundary) — the text is clear in prose but an inline `[NON-GOAL: the module does not enforce Visibility semantics]` would prevent silent assumption by an architect skimming FR-34.

The Assumptions Index (§9) is complete and coherent. Every inline `[ASSUMPTION]` tag found in the FRs maps to an index entry. No orphaned inline assumptions, no index entries that cannot be traced back to a FR.

The open-items density is appropriate: 8 assumptions, 0 unresolved open questions, on a low-complexity-for-its-domain module. Not a blocker.

### Findings

- **low** FR-34 Visibility enforcement boundary not flagged inline (§4.7 / FR-34) — The prose is clear but lacks an inline `[NON-GOAL]` callout that would survive skimming. *Fix:* Add `[NON-GOAL: Visibility enforcement is the consuming app's responsibility; the module does not gate playback on Visibility.]` at the end of FR-34.

---

## 6. Downstream usability — adequate

**Glossary** — Present and dense (§3). All major domain nouns are defined: Video, Operational State, Access State, Upload Session, Playback Token, QuotaProvider, Provider, Provider Adapter, Owner, Visibility, Reconciliation Worker, Admin Layer. FRs use these terms consistently throughout. One exception: the data model in addendum A5 uses `visibility: Enum (PRIVATE, TEAM, UNLISTED)` — note `TEAM`, not `GROUP` — while the PRD body uses GROUP throughout (FR-33, Glossary). This is an inconsistency that will propagate into entity design.

**FR/UJ/SM IDs** — FR-1 through FR-37 are contiguous and unique. UJ-1 through UJ-3 are contiguous. SM-1 through SM-4, SM-C1, SM-C2 are unique. FR cross-references in the SM section resolve correctly (SM-1 → FR-29, FR-33, FR-34; SM-2 → FR-19, FR-23; etc.).

**UJ persona linkage** — UJ-1 references "App developer" (matches §2.1 "App Developer" by clear intent). UJ-2 and UJ-3 reference "End user" (matches §2.3 "End User (Indirect)"). Exact label matching is loose — the Glossary section does not use formal labels in the header style required for strict roundtrip. Not a critical gap for a single-developer-persona PRD.

**Section isolation** — Sections are self-contained enough for downstream extraction. Each feature section (§4.1–4.8) names its realized UJ(s). The integration contract section (§4.6) is clearly the source-of-truth for architecture without requiring a reader to have read §4.1 first.

**Duplicate section heading** — Both "Open Questions" and "Success Metrics" are labeled as §8. This is a numbering error: Open Questions should be §9, Assumptions Index §10. Needs mechanical correction before downstream tools consume the document.

### Findings

- **high** Data model uses TEAM enum value; PRD body uses GROUP (addendum §A5 vs. PRD §3, §4.7, FR-33) — The `visibility` field in the addendum data model lists `PRIVATE, TEAM, UNLISTED`. The PRD body, Glossary, and FR-33 use GROUP consistently. The A7 rejected alternatives section explicitly says "`TEAM` is retained as a label" — but the PRD body does not use TEAM at any point. This inconsistency will produce a defective entity design if the addendum is used as the data model reference. *Fix:* Align the addendum data model enum to GROUP (matching the PRD body), or update the PRD body to TEAM and adjust FR-33 and the Glossary accordingly.
- **medium** Duplicate §8 heading (PRD §8) — "Success Metrics" and "Open Questions" are both numbered §8. *Fix:* Renumber Open Questions to §9 and Assumptions Index to §10.

---

## 7. Shape fit — strong

The PRD shape is an internal platform module / capability spec — not a consumer product, not a multi-stakeholder B2B tool, not a regulatory update. The rubric says: internal tool / single-operator role → capability spec shape; UJs may be overhead; SMs may be operational rather than user-facing.

The PRD chose to include UJs and personas, which is a mild formalization for this product type — but it is not over-formalized. The three UJs are short, concrete, and load-bearing: they define the integration surface (UJ-1), the upload flow (UJ-2), and the playback flow (UJ-3). They are not padding. The two personas are lean and functional.

The brownfield constraint is respected: the PRD references `project-context.md` explicitly, uses the established `@Observed` convention, names the correct package hierarchy (`platform.video`, `infrastructure.video`), and references `SecurityConstants` as an existing project pattern. No new conventions are introduced without justification.

This is the right shape for the product. No findings.

---

## Mechanical notes

**Glossary drift:**
- `TEAM` vs. `GROUP` — addendum A5 vs. PRD body (see Downstream Usability finding). This is the only drift case.
- `Owner` / `ownerId` — used consistently.
- `Playback Token` — used consistently in prose; `PlaybackToken` (no space) used correctly for the entity name.
- `Admin Layer` / `Admin service layer` — two phrasings used in the PRD body. FR-31 uses "Admin service layer," FR-27 uses "Admin Layer," and the Glossary defines "Admin Layer." Minor inconsistency, not a downstream risk.

**ID continuity:**
- FR-1 through FR-37: contiguous, no gaps, no duplicates. Pass.
- UJ-1 through UJ-3: contiguous. Pass.
- SM-1 through SM-4, SM-C1, SM-C2: unique and cross-referenced correctly. Pass.

**Assumptions Index roundtrip:**
- All 8 inline `[ASSUMPTION]` tags found in the FRs map to index entries in §9.
- All §9 index entries can be traced back to a specific FR. Pass.

**UJ persona linkage:**
- UJ-1: "Developer" — resolves to §2.1 App Developer. Acceptable.
- UJ-2: "End user" — resolves to §2.3 End User (Indirect). Acceptable.
- UJ-3: "End user" — resolves to §2.3. Acceptable.
- Exact label matching is loose but not ambiguous given only two personas. Not a risk.

**Required sections for product type:**
All required sections present: Vision, Target User, Glossary, Features with FRs, NFRs, Non-Goals, MVP Scope, Success Metrics, Open Questions, Assumptions Index.

**Minor:**
- The document has `§4.6 / FR-30` referenced in the Assumptions Index as a QuotaProvider concurrency assumption, but FR-30 is titled "QuotaProvider concurrency contract" and the inline assumption is actually in FR-30's Consequences block, not FR-30's body. Cross-reference is accurate in spirit; no downstream risk.
- The duplicate §8 numbering is the only structural error that could confuse automated document parsers.
