# Validation Strategy

This document defines how code changes should be validated during BMad-driven
development in this repository. It is loaded automatically as a persistent
fact by the `bmad-dev-story` workflow (see "How this is wired in" below), so
its instructions apply for the whole duration of every `/bmad-dev-story` run.

## Validation strategy

After making code changes, validate them using the smallest relevant test scope first.

1. **Run targeted tests**

   * Run unit tests for the modified classes or packages.
   * Run only the related `*IT` integration tests when the change affects persistence, REST endpoints, messaging, Spring configuration, or other integration behavior.
   * Prefer specific test classes or test methods over running the entire Maven test suite.

2. **Do not run the full Maven verification by default**

   * Do **not** run `mvn verify` as part of the normal `/bmad-dev-story` workflow.
   * Do **not** run the entire test suite merely because the story implementation is complete.
   * The full test suite is executed by GitHub CI after changes are pushed.

3. **When `mvn verify` is appropriate**
   Run `mvn verify` only when:

   * the user explicitly asks for it;
   * the changes are cross-cutting and targeted tests are insufficient, such as dependency upgrades, framework configuration changes, shared-library changes, build configuration changes, or security-related changes; or
   * we are performing final validation before creating a PR.

4. **Success criteria**

   * If the targeted tests pass, report the targeted validation as successful.
   * Clearly state which tests were run.
   * Do not claim that the full test suite has passed unless `mvn verify` was actually executed.
   * If targeted tests cannot adequately validate the change, explain why and either run the broader validation required by the change or ask for permission to run `mvn verify`.

GitHub CI is the authoritative environment for the full test suite. Avoid duplicating the full `mvn verify` locally during normal story development.

## How this is wired in

`bmad-dev-story`'s `customize.toml` exposes a `persistent_facts` array — standing
context the workflow loads once at activation and carries for the rest of the
run. `_bmad/custom/bmad-dev-story.toml` (a team override, committed to the
repo) appends a `file:` reference to this document, so its contents are
treated as foundational context on every `/bmad-dev-story` invocation without
editing the (read-only, auto-updated) skill files themselves.

Caveat: this is context, not a code-level gate. The workflow's own `SKILL.md`
still contains hardcoded steps that mention running "all existing tests" and
"the full regression suite." This document's instructions are intended to
take precedence over those generic mentions, but if the workflow persists in
running `mvn verify` unprompted, the override may need to be strengthened
(e.g. via `bmad-builder`) rather than relying on `persistent_facts` alone.

To change this policy, edit this file directly — no need to touch
`_bmad/custom/bmad-dev-story.toml` unless the file path or merge behavior
itself needs to change.
