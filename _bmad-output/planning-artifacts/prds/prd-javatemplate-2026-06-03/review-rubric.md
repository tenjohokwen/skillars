# PRD Quality Review — Deployment & Infrastructure

## Overall verdict

This is a well-crafted PRD for an internal tooling deployment spec. The functional requirements are concrete, testable, and consequenced correctly, and the strategic framing is honest and proportionate to the actual problem. Two real weaknesses lower the ceiling: the cost picture is absent (a decision-maker cannot evaluate trade-offs without it), and FR-23 through FR-28 are consequence-free documentation stubs that an engineer cannot treat as acceptance criteria. Fixing those two issues would elevate this from adequate to strong.

---

## 1. Decision-readiness — adequate

The core deployment trade-off (in-place update / single-node / manual prod trigger) is clearly stated and the non-goals are honest. A decision-maker can understand why this architecture was chosen and what it defers. However, one piece of information that belongs in every infrastructure PRD is entirely missing: cost. The upstream proposal (`deployment-proposal.md`) contains a ~€18/month cost estimate with a breakdown. That number is not surfaced anywhere in the PRD. At launch-level stakes, a decision-maker cannot approve a deployment architecture without knowing what it costs to run — even a rough figure. OQ-5 (snapshot retention) implicitly acknowledges that cost scales with retention, but never gives the reader a number to anchor on.

OQ-1 (notification channel) and OQ-3 (SSH allowlist IPs) are correctly flagged as "required before first production deploy," which is good practice. OQ-2 (UAT server setup) is underspecified: it is an open question but also a dependency that determines whether FR-22's "≤ 30-minute deploy cycle" target is achievable at all — if the UAT server doesn't exist, someone needs to provision it first.

### Findings

- **high** Missing cost estimate (§Overall / §6) — The upstream proposal documents ~€18/month with per-component breakdown; the PRD contains no cost information. A decision-maker approving infrastructure spending cannot act without it. *Fix:* Add an Operational Cost section (or subsection under §6) with the monthly estimate from the proposal and a note that snapshot retention (OQ-5) is the primary variable cost driver.
- **medium** OQ-2 is a blocker dressed as a question (§8 / UJ-2) — "Is UAT server a separate Hetzner node?" is not just an open question; it is an unresolved dependency that affects UJ-2's time target and may require provisioning scope. *Fix:* Elevate OQ-2 to a blocking dependency with an explicit consequence: "If no UAT server exists, FR-22's 30-minute target cannot be validated until UAT provisioning is scoped."

---

## 2. Substance over theater — strong

No theater found. The vision section (§1) earns its presence: "a developer who arrives with SSH access, a secrets file, a domain name, and working Docker knowledge should be able to provision a production environment for the first time in under two hours" is a real, falsifiable statement, not a slogan. The NFRs are tight and operational: resource bounds, secrets hygiene, idempotency, deploy downtime budget — each one traces to a real failure mode. The persona is appropriately lean for a single-operator role. No aspirational filler.

The one mild tension: §2.2 ("Jobs To Be Done") and §2.3 ("Key User Journeys") together occupy significant vertical space for a two-role, single-operator product. UJ-1 and UJ-2 are well-written and genuinely useful as the entry state / path / climax structure gives story authors real context. This is borderline overhead for the product type but does real work, so it is not theater.

### Findings

- **low** JTBD list is partially redundant with UJ summaries (§2.2 / §2.3) — The four JTBDs map 1:1 onto the UJ structures below them without adding information. *Fix:* Either collapse JTBD into a single sentence per UJ entry state, or remove the JTBD list and retain only the UJs.

---

## 3. Strategic coherence — strong

The PRD has a clear thesis: eliminate the deployment knowledge gap through a self-contained, developer-controlled pipeline and documentation set. Every feature section advances that thesis. §4.7 (Deployment Documentation) as a first-class feature area — not an afterthought appendix — is the right call for this product type; documentation completeness is literally a success metric (SM-3).

The non-goals (§5) are well-matched to the thesis: they explicitly reject complexity (blue-green, Kubernetes, CDN) that would undermine the "lean and self-contained" arc. The scaling trigger in the Operational Requirements section gives a clear exit condition without adding premature scope.

No strategic incoherence found.

---

## 4. Done-ness clarity — adequate

The FR structure is sound: description, consequences (testable), and in most cases an ASSUMPTION tag where appropriate. For FR-1 through FR-22, an engineer can identify acceptance criteria. The consequence language is specific: "SSH password authentication is rejected by the server," "log files for any single container do not exceed the configured maximum size," "alert fires within 5 minutes of health endpoint becoming unreachable." These are good.

The documentation FRs (FR-23 through FR-28) are the weak spot. Six of the eight documentation requirements have no "Consequences (testable)" block at all. This is inconsistent with every other FR in the document and leaves engineers without acceptance criteria for a substantial deliverable scope.

- **FR-23 (Rollback procedure):** No consequence. How does a reviewer verify the rollback doc is sufficient?
- **FR-24 (Backup and restore guide):** No consequence.
- **FR-25 (Secrets reference):** No consequence. There is a clear testable consequence available: "A developer using the reference can provision all required secrets without consulting any other source."
- **FR-26 (Traefik and TLS reference):** No consequence.
- **FR-27 (Monitoring reference):** No consequence.
- **FR-28 (Operational runbook):** No consequence.

FR-21 and FR-22 do have consequences, so the pattern exists — it just stops at FR-22.

Additionally, FR-6's "previous image digest is re-pinned" is ambiguous about mechanism: does the pipeline read the digest from a stored artifact, or from the compose file, or from GHCR history? This matters for story implementation. The PRD deliberately defers mechanism to `addendum.md`, but `addendum.md` does not yet exist alongside this file.

### Findings

- **high** FR-23 through FR-28 have no testable consequences (§4.7) — Six documentation FRs list only deliverable descriptions with no acceptance criteria. Engineers cannot determine done-ness, and story authors cannot write acceptance tests. *Fix:* Add a "Consequences (testable)" block to each. Pattern to follow: "A developer using [guide] can complete [task] without consulting any source outside `docs/deployment`." Each runbook scenario in FR-28 should additionally specify: "Executing the remediation steps returns the system to healthy state as verified by the health endpoint."
- **medium** Auto-Revert mechanism is underspecified (§4.2 / FR-6) — "Previous image digest is re-pinned" describes intent but not mechanism. The referenced `addendum.md` does not exist in the PRD directory. *Fix:* Either add a sentence clarifying where the previous digest is stored (e.g., pinned in compose file committed to git, or stored as a workflow artifact), or create `addendum.md` as a companion file before story creation begins.

---

## 5. Scope honesty — strong

Scope honesty is the strongest dimension. The non-goals list is specific and each entry has a clear rationale ("acceptable at current scale," "deferred to scaling path"). ASSUMPTION tags are used correctly and are indexed in §9. OQs are numbered, anchored to specific FRs, and most carry a "Required before X" qualifier that signals urgency. The Assumptions Index (§9) is complete relative to the inline tags.

One gap: the snapshot retention period (OQ-5) is flagged as "proposal is silent on this" — but the proposal (`deployment-proposal.md`) includes "€3.00 / Volume Snapshots" in its cost estimate, implying a retention assumption was made. The PRD should surface what assumption underpins that estimate so operators know what they are inheriting.

The §6.2 (Out of Scope for MVP) section correctly cross-references the non-goals and adds implementation-level notes ("revisit if team size grows"). This is good practice.

### Findings

- **low** Snapshot retention assumption is implicitly inherited from the proposal without surfacing it (§4.5 / FR-14 / OQ-5) — The deployment proposal budgets €3/month for snapshots, which implies a specific retention period. The PRD correctly flags this as an OQ but doesn't note the inherited assumption. *Fix:* Add to the OQ-5 entry: "The proposal cost estimate of €3/month implies approximately N days of retention at Hetzner pricing — confirm whether this matches operator intent."

---

## 6. Downstream usability — adequate

**Glossary (§3):** Solid. All key terms used in the FRs are defined: Node, GHCR, UAT Server, Artifact, Smoke Test, Auto-Revert, LGTM Stack, Volume, Object Storage, RTO, RPO, IaC. No drift found between glossary definitions and usage in FR text.

**FR ID continuity:** FR-1 through FR-28, no gaps. Section grouping (4.1–4.7) maps cleanly to capability areas. Story authors can source-extract by section.

**Assumptions Index (§9):** All five inline [ASSUMPTION] tags are indexed. However, the index is ordered by section rather than by OQ number, making it harder to cross-reference with the OQ list in §8. FR-14's assumption about snapshot retention (OQ-5) is not included in the index despite OQ-5 existing in §8 — a minor roundtrip gap.

**SM-to-FR traceability:** SM-1 through SM-6 each list the FRs they validate. SM-4 through SM-6 are secondary metrics but are well-specified. The counter-metrics (SM-C1, SM-C2) are useful guardrails that downstream story authors should see — they communicate what "gaming the metric" looks like.

**UX extractability:** Not applicable; there is no UX surface. Story authors will work from FRs directly.

**Architecture extractability:** FR-7 through FR-13 give an architect the component list, isolation requirements, and resource constraint requirements. The technology choices (Docker Compose, Traefik, GHCR, LGTM) are stated in requirements rather than hidden in implementation detail, which is correct for this product type.

### Findings

- **medium** Assumptions Index does not cover FR-14 / OQ-5 (§9 / §4.5) — The snapshot retention assumption that drives OQ-5 has an inline note in FR-14 but is not listed in §9. Roundtrip is broken for this assumption. *Fix:* Add "§4.5 / FR-14 — Snapshot retention period sufficient to meet RPO is not yet confirmed; see OQ-5" to §9.
- **low** Assumptions Index ordering does not match OQ ordering (§9 vs §8) — The index is ordered by section, while OQs are numbered 1–6. Cross-referencing requires scanning both lists. *Fix:* Add OQ cross-reference numbers to each assumptions index entry, e.g., "(see OQ-3)" on the SSH allowlist entry.

---

## 7. Shape fit — strong

The PRD shape is well-matched to the product type. This is a single-operator, internal infrastructure spec, and the document reflects that:

- The persona section is lean (one persona, two UJs) rather than padded with multiple stakeholder views.
- UJs use entry/path/climax/resolution structure, which provides real implementation context without becoming consumer-product scenario theater.
- Documentation FRs (§4.7) are first-class requirements, which is correct: for a tooling PRD where the output is partly documentation, the docs are a deliverable, not a footnote.
- NFRs are operational rather than aspirational: "idempotency," "resource bounds on all containers," "deploy downtime budget" — all things that affect implementation decisions, not marketing copy.
- The SMs are falsifiable and linked to UJ time targets. SM-C1 and SM-C2 are particularly well-fitted: they anticipate the specific ways an internal team might game the metrics (documentation length, smoke test weakening).

The only shape concern: §2.2 (Jobs To Be Done) is standard consumer-product scaffolding that adds modest overhead in a single-operator context. It is not wrong but could be trimmed.

### Findings

- **low** JTBD list adds overhead for single-operator product (§2.2) — Four JTBDs are fully expressed in the two UJs that follow them. *Fix:* Consider removing §2.2 entirely; the UJs carry the same information with more implementation-useful detail.

---

## Mechanical notes

**Glossary drift:** None found. All terms used in FR bodies match their §3 definitions.

**ID continuity:** FR-1 through FR-28, contiguous, no skips. SM-1 through SM-6 plus SM-C1/SM-C2, contiguous.

**Assumptions Index roundtrip:** Four of five inline [ASSUMPTION] tags are correctly indexed in §9. The FR-14 / OQ-5 snapshot retention note is missing from §9 (see §6 finding above). The FR-18 assumption in §9 ("Hetzner native platform monitoring is sufficient") is correctly indexed but its OQ cross-reference (OQ-5 is snapshot, so this maps to no numbered OQ) — there is no corresponding OQ for this assumption, which is a minor gap; it would benefit from either an OQ entry or explicit language that the operator accepted this assumption.

**`addendum.md` reference:** §0 states that "Technical mechanism choices live in `addendum.md`." No such file exists in the PRD directory. This is a forward reference to a document that does not yet exist. Story authors will look for it. Either create it before story creation, or update §0 to say "will be created during architecture phase."

**FR-18 assumption OQ gap:** The inline [ASSUMPTION] in FR-18 says "Confirm if a third-party service is preferred" but there is no corresponding OQ in §8 for this. OQ-1 through OQ-6 do not include uptime monitoring service selection. *Fix:* Add OQ-7 or fold this into the OQ list explicitly.
