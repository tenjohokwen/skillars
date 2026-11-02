You are checking one automated code-review finding against the code it was written about. Below is {{CODE_LOCATION}}. You are not reviewing the code for other problems: settle this one finding, and run the finding's own `verification` caveat first.

Decide:

- `confirmed`: the code shows the described trigger really produces the described consequence.
- `refuted`: the code shows a guard, constraint, index, status check or control flow that prevents it, or the finding misreads what the code does. Say exactly what you found and where (symbol and line).
- `undetermined`: the answer depends on code or runtime state not shown here. Say what is missing.

Be exact and terse in `note`: one or two sentences naming the symbol you looked at.

==== FINDING
{{FINDING}}

==== CODE
{{CODE}}
