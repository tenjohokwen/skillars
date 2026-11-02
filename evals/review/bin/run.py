#!/usr/bin/env python3
"""Run a reviewer arm over review-eval cases and store its findings.

  run.py <run-id> --arm control|blind-hunter [--cases id,id,...] [--parallel N]
         [--model M] [--effort E] [--max-turns N]

Writes evals/review/results/<run-id>/<arm>/<case>.findings.json (committed) and
evals/review/results/<run-id>/<arm>/raw/<case>.json (git-ignored session metadata).

Arms:
  control       one plain prompt. Diff cases: the diff is inlined, no tools. Snapshot cases:
                the reviewer runs inside the exported tree with Read/Grep/Glob and the scope
                list inlined.
  blind-hunter  the Blind Hunter layer prompt from the mto-code-review skill. Always
                tool-less: diff cases inline the diff; snapshot cases inline the scoped files.

Both arms run FROM the export directory with --setting-sources project, so neither the repo's
nor ~/.claude skills are loaded. See evals/review/README.md.
"""
import argparse, concurrent.futures, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import *  # noqa

ARMS = {
    "control":      {"prompt": "reviewer-control.md",      "snapshot_mode": "tools"},
    "blind-hunter": {"prompt": "reviewer-blind-hunter.md", "snapshot_mode": "inline"},
}
INLINE_CAP = 400_000  # bytes of inlined source before we refuse rather than truncate silently
SCHEMA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "findings.schema.json")

def build(case, arm, work):
    kind = case["kind"]
    tpl = read_prompt(ARMS[arm]["prompt"])
    if kind == "introducing-diff":
        with open(os.path.join(work, "input.diff")) as fh:
            diff = fh.read()
        if len(diff) > INLINE_CAP:
            raise RuntimeError(f"diff too large to inline ({len(diff)} bytes)")
        prompt = tpl.replace("{{MODE_INSTRUCTIONS}}", read_prompt("mode-diff.md")).replace(
            "{{INPUT}}", "```diff\n" + diff + "\n```")
        return prompt, work, "", 8
    tree = os.path.join(work, "tree")
    scope = case.get("scope") or []
    if ARMS[arm]["snapshot_mode"] == "tools":
        prompt = tpl.replace("{{MODE_INSTRUCTIONS}}", read_prompt("mode-snapshot-tools.md")).replace(
            "{{INPUT}}", "\n".join(f"- {p}" for p in scope))
        return prompt, tree, "Read,Grep,Glob", 80
    parts, total = [], 0
    for p in scope:
        fp = os.path.join(tree, p)
        if os.path.isdir(fp):
            files = sorted(os.path.join(dp, f) for dp, _, fs in os.walk(fp) for f in fs)
        else:
            files = [fp]
        for f in files:
            with open(f, errors="replace") as fh:
                body = fh.read()
            total += len(body)
            parts.append(f"==== FILE: {os.path.relpath(f, tree)}\n{body}")
    if total > INLINE_CAP:
        raise RuntimeError(f"scope too large to inline ({total} bytes); use an arm with tools")
    prompt = tpl.replace("{{MODE_INSTRUCTIONS}}", read_prompt("mode-snapshot-inline.md")).replace(
        "{{INPUT}}", "\n\n".join(parts))
    return prompt, work, "", 8

def run_one(run_id, arm, cid, model, effort, max_turns_override):
    out_dir = os.path.join(RESULTS, run_id, arm)
    findings_path = os.path.join(out_dir, f"{cid}.findings.json")
    if os.path.exists(findings_path):
        log(f"[{arm}] {cid}: exists, skipping")
        return cid, "skipped"
    case = load_case(cid)
    work, rev = materialize(cid)
    try:
        prompt, cwd, tools, max_turns = build(case, arm, work)
    except RuntimeError as e:
        log(f"[{arm}] {cid}: {e}")
        write_json(findings_path, {"case": cid, "arm": arm, "error": str(e), "findings": []})
        return cid, "error"
    t0 = time.time()
    log(f"[{arm}] {cid}: running ({case['kind']}, tools='{tools or 'none'}')")
    parsed, raw = run_claude(prompt, cwd=cwd, schema_path=SCHEMA, tools=tools,
                             max_turns=max_turns_override or max_turns, model=model, effort=effort)
    raw.update({"case": cid, "arm": arm, "input_rev": rev, "wall_s": round(time.time() - t0, 1),
                "prompt_chars": len(prompt)})
    write_json(os.path.join(out_dir, "raw", f"{cid}.json"), raw)
    findings = (parsed or {}).get("findings") if isinstance(parsed, dict) else None
    if findings is None:
        log(f"[{arm}] {cid}: NO PARSEABLE OUTPUT (exit {raw.get('exit_code')}, {raw.get('subtype')})")
        write_json(findings_path, {"case": cid, "arm": arm, "input_rev": rev,
                                   "error": "no parseable output", "findings": []})
        return cid, "error"
    for i, f in enumerate(findings, 1):
        f["id"] = f"R{i}"
    write_json(findings_path, {"case": cid, "arm": arm, "input_rev": rev, "model": model,
                               "findings": findings})
    log(f"[{arm}] {cid}: {len(findings)} findings, {raw.get('num_turns')} turns, "
        f"{raw['wall_s']}s, ${raw.get('total_cost_usd') or 0:.2f}")
    return cid, "ok"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_id")
    ap.add_argument("--arm", required=True, choices=sorted(ARMS))
    ap.add_argument("--cases", help="comma-separated case ids (default: all promoted)")
    ap.add_argument("--parallel", type=int, default=3)
    ap.add_argument("--model")
    ap.add_argument("--effort")
    ap.add_argument("--max-turns", type=int)
    a = ap.parse_args()
    ids = a.cases.split(",") if a.cases else promoted_case_ids()
    write_json(os.path.join(RESULTS, a.run_id, "run.json"),
               {"run_id": a.run_id, "model": a.model, "effort": a.effort, "arms": sorted(ARMS)})
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.parallel) as ex:
        futs = [ex.submit(run_one, a.run_id, a.arm, c, a.model, a.effort, a.max_turns) for c in ids]
        statuses = [f.result() for f in futs]
    bad = [c for c, s in statuses if s == "error"]
    log(f"done: {len(statuses)} cases, {len(bad)} errors" + (f": {', '.join(bad)}" if bad else ""))
    sys.exit(1 if bad else 0)

if __name__ == "__main__":
    main()
