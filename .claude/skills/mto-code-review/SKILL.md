---
name: mto-code-review
description: 'Code review for mature codebases, with mechanically enforced verification. Four parallel layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor, Claims Auditor) plus a diff-derived verification target list, a blast-radius sweep for shared symbols, an execution gate that runs tests/mutations/CSS measurements, and a numeric coverage floor. Calibrated for post-audit projects with established patterns. Use when reviewing hardened code with extensive history.'
---

# Code Review Workflow (Mature/Tested Optimized)

**Goal:** Review code changes adversarially using parallel review layers, calibrated for mature codebases with established patterns and minimal noise.

**Your Role:** You are an elite code reviewer. You gather context, run a mechanical verification pass, launch four parallel review layers, triage findings with precision, and present actionable results. No noise, no filler. This variant is optimized for codebases that have shipped many prior hardened stories and have established architectural patterns and design decisions.

## Read this before running

This skill's false-positive calibration twice collapsed into reporting nothing. On `skillars-deferred-139` it surfaced 0 of 19 real findings; on `skillars-deferred-141` it surfaced 2, **both false positives**, one prescribing a fix that called a nonexistent method. Suppression did not even buy accuracy — it just stopped verification from happening in either direction.

The response in #244 was to append prose exhortations, and `skillars-deferred-141` proved that does not work. So the controls in this version are **mechanical**, and they are not optional:

- **A verification target list** (step 2 §1) derived from the diff by seven mechanical triggers — every member read, method called, precedent named, i18n key added, enum literal used, route/permission changed, and shared symbol touched. Every row needs a resolution before triage.
- **A blast-radius sweep** (§2) for shared symbols, whose impact is by definition mostly outside the diff.
- **An execution gate** (§3): run the suite, apply any claimed mutation and confirm it goes red, and measure CSS/layout ACs in a real browser engine via `scripts/measure-rendered-css.sh`. Reasoning may never be presented as measurement.
- **A fourth layer, the Claims Auditor** (§4 Layer D), which tests the spec's own factual claims. The spec is an artifact under review, not ground truth.
- **A numeric coverage floor** (§6) that cannot be reasoned away, replacing the self-assessed "near-zero" check that failed to trip on a 623-line diff.

**Do not invoke `bmad-review-edge-case-hunter` for Layer B.** That skill tells the reviewer to scan only diff hunks and ignore the rest of the codebase, which contradicts the project read access Layer B is given and structurally blinds the highest-yield layer. Step 2 carries an inlined prompt instead.

## Helper scripts

- `scripts/measure-rendered-css.sh <harness.html>` — renders a harness in a real browser engine (Playwright's cached Chromium, or an installed Chrome/Edge) and prints the measurements it computed. Exits non-zero rather than degrading to guesswork when no browser is available.
- `scripts/harness-template.html` — a working template to copy. Replicate the component's real DOM and class lists, copy the real framework CSS in beside it, and always measure the pre-change baseline alongside the fix — that comparison is the only thing that distinguishes a fix from a no-op.

## Conventions

- Bare paths (e.g. `checklist.md`) resolve from the skill root.
- `{skill-root}` resolves to this skill's installed directory (where `customize.toml` lives).
- `{project-root}`-prefixed paths resolve from the project working directory.
- `{skill-name}` resolves to the skill directory's basename.

## On Activation

### Step 1: Resolve the Workflow Block

Run: `python3 {project-root}/_bmad/scripts/resolve_customization.py --skill {skill-root} --key workflow`

**If the script fails**, resolve the `workflow` block yourself by reading these three files in base → team → user order and applying the same structural merge rules as the resolver:

1. `{skill-root}/customize.toml` — defaults
2. `{project-root}/_bmad/custom/{skill-name}.toml` — team overrides
3. `{project-root}/_bmad/custom/{skill-name}.user.toml` — personal overrides

Any missing file is skipped. Scalars override, tables deep-merge, arrays of tables keyed by `code` or `id` replace matching entries and append new entries, and all other arrays append.

### Step 2: Execute Prepend Steps

Execute each entry in `{workflow.activation_steps_prepend}` in order before proceeding.

### Step 3: Load Persistent Facts

Treat every entry in `{workflow.persistent_facts}` as foundational context you carry for the rest of the workflow run. Entries prefixed `file:` are paths or globs under `{project-root}` — load the referenced contents as facts. All other entries are facts verbatim.

### Step 4: Load Config

Load config from `{project-root}/_bmad/bmm/config.yaml` and resolve:

- `project_name`, `planning_artifacts`, `implementation_artifacts`, `user_name`
- `communication_language`, `document_output_language`, `user_skill_level`
- `date` as system-generated current datetime
- `sprint_status` = `{implementation_artifacts}/sprint-status.yaml`
- `project_context` = `**/project-context.md` (load if exists)
- CLAUDE.md / memory files (load if exist)
- YOU MUST ALWAYS SPEAK OUTPUT in your Agent communication style with the config `{communication_language}`

### Step 5: Greet the User

Greet `{user_name}`, speaking in `{communication_language}`.

### Step 6: Execute Append Steps

Execute each entry in `{workflow.activation_steps_append}` in order.

Activation is complete. Begin the workflow below.

## WORKFLOW ARCHITECTURE

This uses **step-file architecture** for disciplined execution:

- **Micro-file Design**: Each step is self-contained and followed exactly
- **Just-In-Time Loading**: Only load the current step file
- **Sequential Enforcement**: Complete steps in order, no skipping
- **State Tracking**: Persist progress via in-memory variables
- **Append-Only Building**: Build artifacts incrementally

### Step Processing Rules

1. **READ COMPLETELY**: Read the entire step file before acting
2. **FOLLOW SEQUENCE**: Execute sections in order
3. **WAIT FOR INPUT**: Halt at checkpoints and wait for human
4. **LOAD NEXT**: When directed, read fully and follow the next step file

### Critical Rules (NO EXCEPTIONS)

- **NEVER** load multiple step files simultaneously
- **ALWAYS** read entire step file before execution
- **NEVER** skip steps or optimize the sequence
- **ALWAYS** follow the exact instructions in the step file
- **ALWAYS** halt at checkpoints and wait for human input

## FIRST STEP

Read fully and follow: `./steps/step-01-gather-context.md`
