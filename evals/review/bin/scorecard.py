#!/usr/bin/env python3
"""Aggregate a run's verdicts into evals/review/results/<run-id>/scorecard.md.

Scoring: an expected finding counts 1 for a full match, 0.5 for partial, 0 for none.
  recall    = matched points / expected findings
  precision = (reported findings that matched something, or were judged real) / reported findings
Both are reported per arm, per category, per case kind, and per case.
"""
import collections, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _common import *  # noqa

def pct(n, d):
    return "-" if not d else f"{100.0 * n / d:.0f}%"

def main():
    run_id = sys.argv[1]
    run_dir = os.path.join(RESULTS, run_id)
    arms = sorted(d for d in os.listdir(run_dir) if os.path.isdir(os.path.join(run_dir, d)))
    lines = [f"# Scorecard: {run_id}", ""]
    meta = read_json(os.path.join(run_dir, "run.json")) if os.path.exists(os.path.join(run_dir, "run.json")) else {}
    if meta.get("model"):
        lines.append(f"Reviewer model: `{meta['model']}`" + (f", effort `{meta['effort']}`" if meta.get("effort") else ""))
        lines.append("")
    per_case_rows = []
    for arm in arms:
        d = os.path.join(run_dir, arm)
        agg = collections.defaultdict(lambda: {"exp": 0, "pts": 0.0, "rep": 0, "good": 0})
        cost = 0.0; cases = 0; errors = 0
        for f in sorted(os.listdir(d)):
            if not f.endswith(".verdict.json"):
                continue
            cid = f[:-len(".verdict.json")]
            v = read_json(os.path.join(d, f)); case = load_case(cid)
            rep = read_json(os.path.join(d, f"{cid}.findings.json"))
            cases += 1; errors += bool(rep.get("error"))
            rawp = os.path.join(d, "raw", f"{cid}.json")
            if os.path.exists(rawp):
                cost += (read_json(rawp).get("total_cost_usd") or 0)
            exp_by_id = {e["id"]: e for e in case["expected_findings"]}
            matched_reported = set()
            c_exp = len(exp_by_id); c_pts = 0.0
            for m in v["verdict"]["matches"]:
                e = exp_by_id.get(m["expected_id"])
                if not e:
                    continue
                pts = {"full": 1.0, "partial": 0.5}.get(m["match"], 0.0)
                c_pts += pts
                for key in ("all", f"cat:{e['category']}", f"kind:{case['kind']}"):
                    agg[key]["exp"] += 1; agg[key]["pts"] += pts
                if pts > 0:
                    matched_reported.update(m.get("reported_ids") or [])
            real = {u["reported_id"] for u in v["verdict"]["unmatched_reported"] if u["verdict"] == "real"}
            n_rep = len(rep.get("findings") or [])
            n_good = len(matched_reported | real)
            for key in ("all", f"kind:{case['kind']}"):
                agg[key]["rep"] += n_rep; agg[key]["good"] += n_good
            per_case_rows.append((arm, cid, case["kind"], c_exp, c_pts, n_rep, n_good))
        a = agg["all"]
        lines += [f"## Arm: {arm}", "",
                  f"{cases} cases ({errors} reviewer errors), ${cost:.2f} reviewer cost.", "",
                  "| slice | expected | recall | reported | precision |", "|---|---|---|---|---|",
                  f"| **all** | {a['exp']} | **{pct(a['pts'], a['exp'])}** | {a['rep']} | **{pct(a['good'], a['rep'])}** |"]
        for key in sorted(k for k in agg if k.startswith("kind:")):
            s = agg[key]; lines.append(f"| {key} | {s['exp']} | {pct(s['pts'], s['exp'])} | {s['rep']} | {pct(s['good'], s['rep'])} |")
        for key in sorted(k for k in agg if k.startswith("cat:")):
            s = agg[key]; lines.append(f"| {key} | {s['exp']} | {pct(s['pts'], s['exp'])} | - | - |")
        lines.append("")
    lines += ["## Per case", "", "| arm | case | kind | expected | recall | reported | precision |", "|---|---|---|---|---|---|---|"]
    for arm, cid, kind, e, p, r, g in per_case_rows:
        lines.append(f"| {arm} | {cid} | {kind.split('-')[0]} | {e} | {pct(p, e)} | {r} | {pct(g, r)} |")
    out = os.path.join(run_dir, "scorecard.md")
    with open(out, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print(out)

if __name__ == "__main__":
    main()
