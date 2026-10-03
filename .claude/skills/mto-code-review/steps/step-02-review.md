---
failed_layers: '' # set at runtime: comma-separated list of layers that failed or returned empty
targets_file: '' # set at runtime: path to the written verification target list
exec_log: '' # set at runtime: path to the written execution-gate results
---

# Step 2: Review

## RULES

- YOU MUST ALWAYS SPEAK OUTPUT in your Agent communication style with the config `{communication_language}`
- Sections 1–3 below are **mechanical**. They are commands to run, not judgements to make. Run them before launching any review layer, and do not substitute reasoning for any of them.
- All review subagents must run at the same model capability as the current session.

### Why this step is mechanical

Two prior attempts to fix this skill by adding prose exhortations ("ambiguity is not noise", "a near-zero count is a signal to re-check") shipped in #244 and both failed: `skillars-deferred-139` surfaced 0 of 19 real findings, and `skillars-deferred-141` surfaced 2, both of which were false positives — including one whose prescribed fix called a method that does not exist anywhere in the codebase.

The lesson is recorded here so it is not re-learned a third time: **telling a reviewer to verify does not make verification happen. Only a command does.** Everything below is therefore expressed as a grep, a count, a test run, or a measurement whose output is written to a file and attached to the findings. A finding without its verification output is not a finding.

## INSTRUCTIONS

### 1. Build the verification target list (mechanical — run this first)

Derive the list **from the diff**, not from intuition. Write it to `{implementation_artifacts}/review-targets.md` and set `{targets_file}`. Every row needs a resolution before triage.

Walk the diff and add a row for each of the following. These seven categories are not examples to pick from — enumerate all of them, every time:

| # | Trigger in the diff | Mandatory action |
|---|---|---|
| T1 | The new code **reads a member** it does not define (a store field, config key, DTO property, injected bean) | Open the defining file. Confirm the member exists, and record its **initial value** and contract. A loading flag that initialises `false` behaves differently on first render than one seeded `true`. |
| T2 | The new code **calls a method/function** it does not define | Grep the definition. Read it in full — the name may lie about what it does. **If you intend to recommend a different method as the fix, grep that one too and confirm it exists before writing the finding.** |
| T3 | The spec names a **precedent file** to mirror ("same pattern as X", "mirror Y") | Open the precedent and compare behaviour, not just shape. Record every place the new code diverges, and whether the divergence is justified. |
| T4 | The diff adds an **i18n / config / profile key** | Resolve the key's **full path** in each locale or profile file by walking the object tree up to the root. Equal indentation is not proof of equal nesting. Compare key sets across all locales. |
| T5 | The diff uses a **status / enum / magic string literal** | Grep the enum, DB `CHECK` constraint, or union type. Confirm every literal is real and no live value is missing from the set. |
| T6 | The diff adds or changes a **route, permission, or authorization annotation** | Find the guard or filter that enforces it. Confirm the shape the guard reads matches the shape the diff wrote (`meta.role` vs `meta.roles`, `hasRole` vs `hasAnyRole`). Confirm the method body resolves the right principal for every newly-allowed role. |
| T7 | The diff changes a **shared symbol** — a CSS class/selector, a helper, a base class, a constant, a test fixture | Run the blast-radius sweep in section 2. |

### 2. Blast-radius sweep for shared symbols (mechanical)

**A changed shared symbol's blast radius is by definition mostly outside the diff.** In `skillars-deferred-141` a one-line CSS rule added to a shared `.auth-banner` class silently changed a *fifth* element whose line never appeared in the diff at all — the review layer scoped to diff hunks could not have found it, no matter how carefully it read.

For every T7 row:

1. `grep -rn "<symbol>" <source-root>` — list **every** consumer, not a sample.
2. For each consumer, state explicitly whether the change affects it, and why or why not.
3. Flag any consumer that the spec did not mention. An unmentioned consumer is the most likely place for an unintended regression.

Record the full consumer list in `{targets_file}`. A T7 row with no enumerated consumer list is unresolved.

### 3. Execution gate (mechanical — these are commands, not options)

Run all of these that apply and write the real output to `{implementation_artifacts}/review-execution-log.md`; set `{exec_log}`. Attach the relevant output to any finding that depends on it.

1. **Run the project's test suite(s)** touched by the diff. Record the real file/test counts and compare them to what the spec claims. Record stderr warnings too — `skillars-deferred-141`'s new tests emitted ~40 `[Vue Router warn]: No match found` lines that revealed the assertions were resting on unmatched-route behaviour.
2. **For every "mutation-checked" / "mutation-tested" claim in the spec: apply the mutation and confirm the test actually goes red.** Restore the file afterwards and verify the restore. In `skillars-deferred-141` one of two such documented claims was false — the mutant survived all four tests — and no amount of reading would have revealed it.
3. **For every claim of the form "N tests green", "suite passes", "lint clean": run it.** Do not copy the number from the spec.
4. **For a CSS / layout / visual AC: measure it.** Use `{skill-root}/scripts/measure-rendered-css.sh` with a harness built from `{skill-root}/scripts/harness-template.html`. Measure the **pre-change baseline as well as the fixed state** — that comparison is the only thing that distinguishes a fix from a no-op. Margin collapsing, `inline-flex` vs block, and `!important` interactions are not reliably derivable from stylesheet source; two review layers reasoned about `skillars-deferred-141`'s banner margin and reached opposite wrong conclusions.
5. If a command **cannot** be run (no browser, no DB, missing fixture), write that in `{exec_log}` and label the related finding **"unverified — could not execute"**. Never present reasoning as if it were measurement.

### 4. Launch the review layers

If `{review_mode}` = `"no-spec"`, note: "Acceptance Auditor and Claims Auditor skipped — no spec file provided."

Launch in parallel, without conversation context. Give every layer `{targets_file}` and `{exec_log}`. If subagents are unavailable, write one prompt file per role into `{implementation_artifacts}` and HALT, asking the user to run each in a separate session (ideally a different LLM) and paste back findings; resume here when they do.

#### Layer A — Blind Hunter
Receives `{diff_output}` only. No spec, no context docs, no project access.

> You are a cynical code reviewer. Review this diff with extreme skepticism and report genuine defects: correctness bugs, logic errors, unhandled paths, internal contradictions, and unresolved design ambiguity.
>
> You have no project access, so you cannot confirm whether something outside this diff exists. That limitation changes how you WRITE a finding, not whether you report it. For anything you cannot verify from the diff alone, report it and label it **"requires verification: <the exact thing to check>"**. The orchestrator has a verification target list and will resolve it. Do not silently drop a finding because you couldn't check it, and do not assert as fact that something is missing when you cannot see outside the diff — say which check would settle it.
>
> Specific things to hunt:
> - A name that contradicts what the code does with it.
> - Tests that mock away the exact behaviour under test, or assert something weaker than their own description claims.
> - Comments or test headers that make a verifiable claim — treat each as a claim to be checked, not as information.
> - Copy-paste residue: identifiers, strings, or links carried over from the code this was cloned from.
> - Error/loading state handling that is incomplete, or whose branch order lets the wrong state win.
>
> Ignore pure style and "could be more elegant". Everything else is in scope. Output a Markdown list; each finding gets a one-line title, `file:line`, a severity, the concrete failure scenario, and either its diff evidence or its "requires verification" label.

#### Layer B — Edge Case Hunter
Receives `{diff_output}`, `{targets_file}`, and **read access to the project**.

> **Do NOT invoke the `bmad-review-edge-case-hunter` skill for this layer.** That skill instructs the reviewer to "scan only the diff hunks" and "ignore the rest of the codebase" — which directly contradicts the project read access this layer is given, and structurally blinds it to the majority of real findings. Its 4-field output format also has no evidence field and forces every finding into "a missing guard" shape, which is wrong for false claims, inert CSS, and missing test assertions. Use the prompt below instead.

> You are an Edge Case Hunter. Your method is exhaustive path-walking: enumerate every branching path, boundary condition, and state combination the changed code can enter, and report the unhandled ones.
>
> **You have full project read access and you are required to use it.** Most real findings in a change like this live *outside* the diff hunks — in the store the new code reads, the guard that enforces its route, the precedent file it was cloned from, or the other consumers of a shared symbol it changed. Reading only the diff is a failure of this layer.
>
> Work the verification target list you were given first: resolve every row against real source and report what you found. Then walk the paths. Mandatory for each finding:
> - `file:line` read **directly from the current file** — open it and confirm. Never extrapolate or recall a line number.
> - The exact state/input combination that triggers it, and the resulting wrong behaviour.
> - **The verification you performed**, quoting the real source you read.
>
> Pay particular attention to **initial state**: the value a flag, ref, or field holds on first render before any fetch or effect has run. This is the single most commonly missed state class, because it exists for only one frame and appears in no test fixture unless someone wrote one deliberately.
>
> Output a Markdown list. End with a short **"verified clean"** list of things you checked that turned out fine — this is valuable, because it stops other layers' false positives from surviving triage.

#### Layer C — Acceptance Auditor
Receives `{diff_output}`, the file at `{spec_file}`, context docs, `{targets_file}`, `{exec_log}`.

> You are an Acceptance Auditor reviewing against a spec. Check for violated acceptance criteria, deviations from spec intent, missing implementation of specified behaviour, and contradictions between spec constraints and the code.
>
> **Audit each AC clause by clause.** An AC is not satisfied because its headline behaviour works; it is satisfied when every clause it contains is true. Testing requirements are clauses too — if an AC says a behaviour must be asserted "absent for the other roles", the absence of that assertion is an AC violation even though the production code is correct. In `skillars-deferred-141` exactly this clause was never implemented, and deleting a role guard would have failed no test.
>
> **The spec is evidence under review, not ground truth.** Where it explains *why* something is safe or *how* a mechanism works, that explanation is a hypothesis to falsify. Identify its premise and check the premise. One `skillars-deferred-141` AC justified bypassing a gate by asserting a nav link was unreachable in that state; the link was plainly reachable, and the whole justification collapsed on one `grep` of the route table.
>
> Also check: every `project-context.md` rule the diff could violate — but before reporting a violation, check whether the diff's own module has an established contrary precedent (list the sibling files you checked). An established, consistent module pattern is a resolved decision, not a fresh violation.
>
> Output a Markdown list: one-line title, the AC/rule/claim violated, `file:line` evidence read directly from current source, severity. Then a short list of spec claims you verified as TRUE, so other layers don't re-flag them.

#### Layer D — Claims Auditor *(new — this layer exists because the spec lies)*
Receives the file at `{spec_file}`, `{diff_output}`, `{exec_log}`, and project read + execute access.

> You are a Claims Auditor. Your only job is to test the **factual claims the spec makes about itself**. You are not reviewing the production code.
>
> Extract every verifiable claim from the spec's Dev Agent Record, Testing Summary, Completion Notes, File List, and Definition of Done. Then sort each into one of three kinds and handle it:
>
> 1. **Countable** ("19 new test cases", "4 files changed", "3 tiles", "6 tests") → **count it** in the actual diff. Report any mismatch, and say what the real number is. Watch for a count that is really the *total* of something rather than the *new* part of it.
> 2. **Runnable** ("suite green", "N/N passing", "mutation-checked", "lint clean", "build succeeds") → **run it** and paste the real output. For a mutation claim, apply the mutation and confirm the test goes red; a surviving mutant means the claim is false.
> 3. **Rationale** ("safe because…", "equivalent because…", "unreachable because…", "X outranks Y so…") → name the **premise**, then verify the premise against real source. A confident explanation with a false premise is the most dangerous item in any spec, because every later reviewer treats it as settled.
>
> Also check internal consistency: a Definition of Done box checked `[x]` while the task it depends on is `[ ]`, or an AC marked complete whose own text forbids completing it the way it was completed.
>
> Output a Markdown list: the claim verbatim, its kind, VERIFIED / FALSE / UNVERIFIABLE, and the evidence or command output. A FALSE claim in a spec is a finding of its own — the spec is a durable artifact that future reviewers and future stories will trust.

### 5. Collect findings

Append any layer that fails, times out, or returns empty to `{failed_layers}` and proceed with the rest.

### 6. Coverage gate (numeric — not a judgement call)

Compute `findings_total` across all layers, then compute the floor:

- Let `L` = lines changed in `{diff_output}` (added + removed).
- `expected_floor = max(1, floor(L / 150))`
- Add 1 to the floor for **each** of: a new HTTP endpoint or changed authorization annotation; a new page/route/screen; a changed shared symbol (any T7 row); a CSS/layout AC; a spec claiming "mutation-checked".

If `findings_total < expected_floor`, **or** if any row in `{targets_file}` is still unresolved, **or** if any applicable command in section 3 was not run: you are not done. Re-run Layers B, C, and D once more, passing them the unresolved rows explicitly and naming the specific checks still outstanding. Record both counts in the summary.

A review may legitimately finish below the floor — but only after this second pass, and the summary must say so explicitly rather than presenting the low count as a clean result.

## NEXT

Read fully and follow `./step-03-triage.md`
