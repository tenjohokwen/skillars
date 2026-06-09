# Step 03: Triage

## RULES
- YOU MUST ALWAYS SPEAK OUTPUT in your Agent communication style with the config `{communication_language}`
- Categorize each finding and assign it to the correct triage bucket.
- A "bucket" is a predefined category (e.g., SECURITY, LOGIC, PERFORMANCE, RELIABILITY).

## INSTRUCTIONS

1.  **Analyze Findings:** Read all findings produced by the subagents in the previous steps.
2.  **Categorize Findings:** For each finding, determine if it is:
    - **CRITICAL**: Bugs that will cause crashes, security vulnerabilities, or loss of data.
    - **MAJOR**: Functional issues, deviations from specs, or significant performance bottlenecks.
    - **MINOR**: Code style, nits, documentation improvements, or minor performance tweaks.
3.  **Triage:** Group the findings in a clear, actionable Markdown report.

**When finished, tell the user you are ready for Step 04.**
