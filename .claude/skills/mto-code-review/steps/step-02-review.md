---
failed_layers: '' # set at runtime: comma-separated list of layers that failed or returned empty
---

# Step 2: Review

## RULES

- YOU MUST ALWAYS SPEAK OUTPUT in your Agent communication style with the config `{communication_language}`
- The Blind Hunter subagent receives NO project context — diff only, but with calibration for mature codebases.
- The Edge Case Hunter subagent receives diff and project read access.
- The Acceptance Auditor subagent receives diff, spec, and context docs, with emphasis on spec ambiguity.
- All review subagents must run at the same model capability as the current session.

## INSTRUCTIONS

1. If `{review_mode}` = `"no-spec"`, note to the user: "Acceptance Auditor skipped — no spec file provided."

2. Launch parallel subagents without conversation context. If subagents are not available, generate prompt files in `{implementation_artifacts}` — one per reviewer role below — and HALT. Ask the user to run each in a separate session (ideally a different LLM) and paste back the findings. When findings are pasted, resume from this point and proceed to step 3.

   - **Blind Hunter** — receives `{diff_output}` only. No spec, no context docs, no project access. Enhanced prompt to reduce false positives on mature codebases:
     > You are a cynical code reviewer for a MATURE CODEBASE that has already shipped many hardened stories. Review this diff with extreme skepticism — but only report GENUINE BUGS, not coding-style preferences or hypothetical risks.
     >
     > **Critical:** Before flagging anything as missing or wrong:
     > 1. Don't assume defensive checks are missing — check if they exist in context
     > 2. Don't assume patterns are absent without seeing the full code — gaps often exist but you can't see them in a diff
     > 3. Only flag things where you've directly verified the problem, not inferred it
     > 4. Ignore "could be more elegant" — focus on actual correctness bugs only
     >
     > Find REAL issues (correctness, logic errors, unhandled paths). Ignore style, over-engineering suggestions, or "nice-to-haves". Output as Markdown list (descriptions only).

   - **Edge Case Hunter** — receives `{diff_output}` and read access to the project. Invoke via the `bmad-review-edge-case-hunter` skill.

   - **Acceptance Auditor** (only if `{review_mode}` = `"full"`) — receives `{diff_output}`, the content of the file at `{spec_file}`, and any loaded context docs. Enhanced prompt:
     > You are an Acceptance Auditor reviewing a mature, hardened codebase. Review this diff against the spec and context docs. Check for: violations of acceptance criteria, deviations from spec intent, missing implementation of specified behavior, contradictions between spec constraints and actual code.
     >
     > **Critical before flagging:**
     > 1. If the spec explicitly says "implementer decides during implementation" for a design choice and the implementation made a deliberate, documented choice, that is NOT a violation — dismiss it.
     > 2. Only report deviations where the spec is explicit and unambiguous. Ignore reasonable implementation choices that differ from spec examples.
     > 3. Check if a stated "missing behavior" is actually present but in a different form than the spec example suggests.
     > 4. Read Javadoc in the changed code — design rationale and accepted tradeoffs are documented there; use that context.
     >
     > Output findings as a Markdown list. Each finding: one-line title, which AC/constraint it violates, and evidence from the diff. Only report genuine, verified violations, not style deviations or implementation discretion.


## NEXT

Read fully and follow `./step-03-triage.md`
