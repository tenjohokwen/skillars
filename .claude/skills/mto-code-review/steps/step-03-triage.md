---
---

# Step 3: Triage

## RULES

- YOU MUST ALWAYS SPEAK OUTPUT in your Agent communication style with the config `{communication_language}`
- Be precise. When uncertain between categories, prefer the classification that puts the finding in front of a human rather than the one that drops it — `decision_needed` over `dismiss`, `patch` over `defer`. "Conservative" means surfaced, not suppressed: silently dropping a finding is never the conservative choice, because triage cannot recover a finding that was never reported.

## INSTRUCTIONS

1. **Normalize** findings into a common format. Every layer (A–D) outputs a Markdown list; parse best-effort and note any parsing problems for the user.

   Convert all to a unified list where each finding has:
   - `id` — sequential integer
   - `source` — `blind`, `edge`, `auditor`, `claims`, or merged (e.g. `blind+edge`)
   - `title` — one-line summary
   - `detail` — full description
   - `location` — `file:line` (if available)
   - `verification` — the evidence or command output that settles it, or the literal string `UNVERIFIED`

2. **Resolve every `UNVERIFIED` finding before classifying it.** This is the step that turns Layer A's blind guesses into either a real finding or a dismissal. For each one, run the check its "requires verification" label names — a grep, a file read, a test run. Record the result in `verification`.

   A finding that is still `UNVERIFIED` after this step may **not** be dismissed. Carry it forward labelled "unverified — could not execute" and let the human decide. Dismissing an unchecked finding is how both prior failures of this skill happened.

3. **Deduplicate.** Merge findings describing the same issue; prefer the one with real `file:line` plus verification as the base, and fold in unique detail from the others. Set `source` to the merged sources.

   **When two layers disagree, the one holding measured or executed output wins** — not the one with the more confident prose. In `skillars-deferred-141` two layers reasoned oppositely about a margin; the browser measurement settled it, and both prose arguments were wrong.

4. **Classify** each finding into exactly one bucket:
   - **decision_needed** — an ambiguous choice requiring human input; the code cannot be correctly patched without knowing intent. Only possible if `{review_mode}` = `"full"`.
   - **patch** — fixable without human input; the correct fix is unambiguous.
   - **defer** — real, but pre-existing and not caused by this change.
   - **dismiss** — actively disproven.

   If `{review_mode}` = `"no-spec"` and a finding would be `decision_needed`, reclassify as `patch` (fix unambiguous) or `defer`.

   **A false claim in the spec is a `patch`, not a `dismiss`.** The spec is a durable artifact; the next story and the next reviewer will trust it. Correcting the text is the patch.

   **A `defer` requires evidence it is pre-existing** — `git log`/`git blame` showing the line predates this branch. Without that, it is a `patch`. "Probably pre-existing" is not triage.

5. **Dismissals require a citation.** Every `dismiss` must carry a specific, recorded piece of evidence that disproves it: a quoted Javadoc sentence, a sibling `file:line` showing the same established pattern, a grep result, a confirmed library contract, a command's output. Record the citation next to the dismissal.

   If you cannot produce the citation, the finding is not dismissed — reclassify it. Plausibility is not disproof. Note that in `skillars-deferred-141` *both* reported findings were themselves false positives that survived because nobody checked them, so this rule cuts in both directions: it blocks unverified dismissals **and** unverified reports.

6. **Drop** all `dismiss` findings. Record the count, and keep the citations available for the summary.

7. If `{failed_layers}` is non-empty, report which layers failed before announcing results. If zero findings remain AND `{failed_layers}` is non-empty, warn that the review may be incomplete rather than announcing a clean review.

8. **Two sweeps before finalizing.**

   **8a. Admitted-ambiguity sweep.** Grep `{spec_file}` and the diff for the author's own hedge markers: "implementer decides", "TBD", "open question", "deferred", "revisit", "assume", "should be", "leading hypothesis", or a reference to another story's open item. For each hit, confirm a corresponding finding exists, or that the diff documents the resolution. A hit with neither must be added back as `decision_needed`.

   **8b. Confident-falsehood sweep.** Sweep 8a only catches ambiguity the author *admitted to*. The more dangerous class is stated with full confidence and is wrong. Grep `{spec_file}` for assertive-mechanism language — "reliably", "guaranteed", "always", "never", "because", "confirmed", "verified", "by definition", "cannot" — and for every hit, confirm Layer D actually tested that claim. An untested assertive claim about a mechanism must be added as a finding.

   `skillars-deferred-141` shipped a broken fix behind the sentence "scoped styles compile to an attribute selector, which **reliably** outranks a same-specificity global utility class". Maximally confident, perfectly true as CSS trivia, and completely irrelevant to the actual cause. Sweep 8a could never have caught it; 8b does.

9. If zero findings remain after triage: state "✅ Clean review — all layers passed", and state the `findings_total` vs `expected_floor` numbers from step 2 section 6 so the user can see the clean result was reached with the coverage gate satisfied, not by under-reporting.

## NEXT

Read fully and follow `./step-04-present.md`
