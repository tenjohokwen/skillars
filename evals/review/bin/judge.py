#!/usr/bin/env python3
"""Grade a run's findings against each case's expected findings with an LLM judge.

  judge.py <run-id> --arm A [--cases id,...] [--parallel N] [--model M]

Reads  evals/review/results/<run-id>/<arm>/<case>.findings.json
Writes evals/review/results/<run-id>/<arm>/<case>.verdict.json

The judge sees the case YAML (the answer key) and the reported findings. It never sees the
fix and has no tools. A case with zero reported findings is graded without a model call.
"""
import argparse, concurrent.futures, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import *  # noqa

SCHEMA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verdict.schema.json")

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
    write_json(vpath, {"case": cid, "arm": arm, "judge_model": model, "verdict": parsed,
                       "judge_cost_usd": raw.get("total_cost_usd")})
    full = sum(m["match"] == "full" for m in parsed["matches"])
    part = sum(m["match"] == "partial" for m in parsed["matches"])
    log(f"[{arm}] {cid}: {full} full, {part} partial of {len(case['expected_findings'])}; "
        f"{len(parsed['unmatched_reported'])} unmatched reported")
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
