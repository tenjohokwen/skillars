You are grading an automated code reviewer against an answer key. You will see (1) the EXPECTED findings a correct reviewer should have raised for this piece of code, with the files and symbols they live in; (2) NON-FINDINGS, behaviours that look wrong but are deliberate; (3) the REPORTED findings the reviewer actually produced.

Your job is matching, not reviewing. Decide for each expected finding whether a reported finding identifies the same defect, and for each reported finding that matches nothing whether it is nevertheless a real defect, a non-finding, noise, or a duplicate of another reported finding.

Matching rules:
- `full`: the reported finding identifies the same defect at the same place and its described consequence is the same, even if worded very differently or less precisely.
- `partial`: the reported finding points at the right place and smells the right problem, but misreads the mechanism or the consequence, or covers only a fragment of the expected defect.
- `none`: nothing reported corresponds to it.
- A single reported finding may match several expected findings when they share a root cause, and several reported findings may jointly match one expected finding.
- A reported finding that merely says "verify X" where X is the expected defect's location counts as `partial`, not `full`.

For unmatched reported findings:
- `real`: a genuine defect that the answer key does not list. Be strict: you must be able to state the concrete trigger from the reported text alone. If it is speculative or unverifiable, it is `noise`.
- A `verification` caveat on a reported finding is not evidence either way. Grade on the described trigger; every `real` verdict is re-checked against the code in a second pass that runs the caveat.
- `non_finding`: matches an entry in NON-FINDINGS.
- `duplicate`: the same defect as another reported finding that you already matched or judged.
- `noise`: style, speculation, something the code visibly already handles, or a claim with no concrete trigger.

Be exact and terse in `note`: one sentence stating why.

==== EXPECTED FINDINGS
{{EXPECTED}}

==== NON-FINDINGS
{{NON_FINDINGS}}

==== REPORTED FINDINGS
{{REPORTED}}
