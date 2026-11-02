#!/usr/bin/env python3
"""Grade a run's findings against each case's expected findings with an LLM judge.

  judge.py <run-id> --arm A [--cases id,...] [--parallel N] [--model M]

Reads  evals/review/results/<run-id>/<arm>/<case>.findings.json
Writes evals/review/results/<run-id>/<arm>/<case>.verdict.json

Pass 1: a tool-less call that sees the case YAML (the answer key) and the reported findings.
Pass 2 (v2): one tool-less call per unmatched report graded `real` that carries a
`verification` caveat, with the cited file (snapshot) or that file's diff hunks (diff case)
inlined, to confirm or refute it. The judge never sees the fix. A case with zero reported
findings is graded without a model call.
"""
import argparse, concurrent.futures, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import *  # noqa

SCHEMA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verdict.schema.json")
CHECK_SCHEMA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check.schema.json")
# v1: tool-less, graded from text alone (results/baseline-2026-11-02).
# v2: same pass 1, plus one tool-less check call per `real` unmatched report that carries a
# `verification` caveat, with the cited code inlined (prompts/judge-check.md). Matching is
# unchanged, so recall is comparable across v1 and v2; only precision tightens.
JUDGE_VERSION = 2

def fmt_expected(case):
    out = []
    for f in case["expected_findings"]:
        anchors = "; ".join(f"{a.get('file')}" + (f" :: {a['symbol']}" if a.get("symbol") else "")
                            for a in f.get("anchors") or [])
        out.append(f"[{f['id']}] ({f['category']}, {f['severity']}) {f['title']}\n"
                   f"    where: {anchors}\n    {str(f['description']).strip()}")
    return "\n\n".join(out)

def fmt_non_findings(case):
    nf = case.get("non_findings") or []
    if not nf:
        return "(none)"
    return "\n".join(f"- {n.get('title')}: {str(n.get('reason','')).strip()}" for n in nf)

def fmt_reported(findings):
    out = []
    for f in findings:
        loc = f.get("file", "?") + (f" :: {f['symbol']}" if f.get("symbol") else "")
        v = f"\n    verification: {f['verification']}" if f.get("verification") else ""
        out.append(f"[{f['id']}] ({f.get('category')}, {f.get('severity')}) {f.get('title')}\n"
                   f"    where: {loc}\n    {str(f.get('description','')).strip()}{v}")
    return "\n\n".join(out) or "(the reviewer reported nothing)"

CITED_CAP = 120_000  # bytes of cited code inlined into a check call

def cited_code(case, work, finding):
    """Return (text, description) of the code a check call should see for one finding.

    Snapshot case: the cited file from the exported tree. Diff case: only that file's hunks of
    the PR diff (the reviewer never saw more than the diff either). None if unavailable.
    """
    path = (finding.get("file") or "").strip()
    if case["kind"] == "introducing-diff":
        with open(os.path.join(work, "input.diff")) as fh:
            diff = fh.read()
        hunks, keep = [], False
        for line in diff.splitlines(keepends=True):
            if line.startswith("diff --git "):
                keep = path and (f" b/{path}" in line or line.rstrip().endswith(path))
            if keep:
                hunks.append(line)
        text = "".join(hunks)
        if not text:
            return None, f"{path} is not part of the PR diff"
        where = f"the PR diff hunks for {path} (code outside the diff is not available)"
    else:
        fp = os.path.join(work, "tree", path)
        if not os.path.isfile(fp):
            return None, f"{path} does not exist at the input revision"
        with open(fp, errors="replace") as fh:
            text = fh.read()
        where = f"the full file {path} at the input revision"
    if len(text) > CITED_CAP:
        return None, f"{path} is too large to inline ({len(text)} bytes)"
    return text, where

def judge_one(run_id, arm, cid, model):
    d = os.path.join(RESULTS, run_id, arm)
    vpath = os.path.join(d, f"{cid}.verdict.json")
    if os.path.exists(vpath):
        return cid, "skipped"
    fpath = os.path.join(d, f"{cid}.findings.json")
    if not os.path.exists(fpath):
        log(f"[{arm}] {cid}: no findings file"); return cid, "missing"
    rep = read_json(fpath)
    case = load_case(cid)
    findings = rep.get("findings") or []
    if not findings:
        verdict = {"matches": [{"expected_id": f["id"], "match": "none", "reported_ids": [],
                                "note": "reviewer reported nothing"} for f in case["expected_findings"]],
                   "unmatched_reported": []}
        write_json(vpath, {"case": cid, "arm": arm, "judge_model": None, "verdict": verdict,
                           "reviewer_error": rep.get("error")})
        return cid, "ok"
    # Pass 1 (unchanged since v1): tool-less grading from the answer key and the report text.
    prompt = (read_prompt("judge.md").replace("{{EXPECTED}}", fmt_expected(case))
              .replace("{{NON_FINDINGS}}", fmt_non_findings(case))
              .replace("{{REPORTED}}", fmt_reported(findings)))
    parsed, raw = run_claude(prompt, cwd=WORK if os.path.isdir(WORK) else "/", schema_path=SCHEMA,
                             tools="", max_turns=3, model=model)
    write_json(os.path.join(d, "raw", f"{cid}.judge.json"), raw)
    if not isinstance(parsed, dict) or "matches" not in parsed:
        log(f"[{arm}] {cid}: judge produced no parseable verdict"); return cid, "error"
    # Every expected id must be covered; fill any the judge forgot as 'none'.
    seen = {m["expected_id"] for m in parsed["matches"]}
    for f in case["expected_findings"]:
        if f["id"] not in seen:
            parsed["matches"].append({"expected_id": f["id"], "match": "none", "reported_ids": [],
                                      "note": "not addressed by judge; defaulted to none"})
    # Pass 2 (v2): an unmatched report graded `real` whose own `verification` caveat names a
    # check is re-graded with the cited file's content inlined (no tools, one turn). A human
    # sample of v1 found that every false `real` had exactly that shape: the reviewer told the
    # judge how to refute it and the tool-less judge credited it anyway. Reports without a
    # caveat are left as pass 1 graded them.
    checks, check_cost = [], 0.0
    by_id = {f["id"]: f for f in findings}
    targets = [u for u in parsed["unmatched_reported"]
               if u["verdict"] == "real" and (by_id.get(u["reported_id"]) or {}).get("verification")]
    if targets:
        work, rev = materialize(cid)
        for u in targets:
            f = by_id[u["reported_id"]]
            code, where = cited_code(case, work, f)
            if code is None:
                checks.append({"reported_id": u["reported_id"], "check": "undetermined",
                               "note": f"cited file not available: {where}"})
                u["verdict"] = "unverified"
                u["note"] = f"[v2 check impossible: {where}] | pass-1: {u['note']}"
                continue
            cprompt = (read_prompt("judge-check.md").replace("{{CODE_LOCATION}}", where)
                       .replace("{{FINDING}}", fmt_reported([f])).replace("{{CODE}}", code))
            cparsed, craw = run_claude(cprompt, cwd=WORK, schema_path=CHECK_SCHEMA,
                                       tools="", max_turns=2, model=model)
            check_cost += craw.get("total_cost_usd") or 0
            ok = isinstance(cparsed, dict) and cparsed.get("verdict") in ("confirmed", "refuted", "undetermined")
            cv = cparsed["verdict"] if ok else "undetermined"
            note = cparsed.get("note", "") if ok else "check call failed"
            checks.append({"reported_id": u["reported_id"], "check": cv, "note": note})
            if cv == "refuted":
                u["verdict"] = "noise"
                u["note"] = f"[v2 check refuted] {note} | pass-1: {u['note']}"
            elif cv == "confirmed":
                u["note"] = f"[v2 check confirmed] {note} | pass-1: {u['note']}"
            else:
                # The finding rests on something the check could not see. It is not credited
                # as real (that is how the sampled false positives got through) and not called
                # noise either; the scorecard reports it separately.
                u["verdict"] = "unverified"
                u["note"] = f"[v2 check undetermined] {note} | pass-1: {u['note']}"
    write_json(vpath, {"case": cid, "arm": arm, "judge_model": model, "judge_version": JUDGE_VERSION,
                       "verdict": parsed, "judge_cost_usd": (raw.get("total_cost_usd") or 0) + check_cost,
                       "judge_turns": raw.get("num_turns"), "checks": checks})
    full = sum(m["match"] == "full" for m in parsed["matches"])
    part = sum(m["match"] == "partial" for m in parsed["matches"])
    refuted = sum(c["check"] == "refuted" for c in checks)
    log(f"[{arm}] {cid}: {full} full, {part} partial of {len(case['expected_findings'])}; "
        f"{len(parsed['unmatched_reported'])} unmatched reported, {len(checks)} checked, {refuted} refuted")
    return cid, "ok"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_id"); ap.add_argument("--arm", required=True)
    ap.add_argument("--cases"); ap.add_argument("--parallel", type=int, default=4)
    ap.add_argument("--model")
    a = ap.parse_args()
    ids = a.cases.split(",") if a.cases else promoted_case_ids()
    with concurrent.futures.ThreadPoolExecutor(max_workers=a.parallel) as ex:
        st = [f.result() for f in [ex.submit(judge_one, a.run_id, a.arm, c, a.model) for c in ids]]
    bad = [c for c, s in st if s in ("error",)]
    log(f"judged {len(st)} cases, {len(bad)} errors")
    sys.exit(1 if bad else 0)

if __name__ == "__main__":
    main()
