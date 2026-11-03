#!/usr/bin/env python3
"""Validate review-eval case files against the schema in evals/review/README.md.

Checks: YAML parses; required keys per kind; enum values; id matches filename;
merge commits resolve; every anchor file exists at the input revision and, when a
symbol is given, the symbol occurs in that file; scope paths exist at the input revision.

Usage: evals/review/bin/validate.py [case.yaml ...]   (default: evals/review/cases/*.yaml)
Exit 1 on any problem. Prints one line per problem, then a summary.
"""
import subprocess, sys, glob, os, re, collections
import yaml

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
KINDS = {"introducing-diff", "pre-fix-snapshot"}
CATS = {"security", "logic", "concurrency", "database", "edge-case", "architecture", "performance"}
SEVS = {"high", "medium", "low"}
CONF = {"high", "medium", "low"}

_show_cache = {}
def git_show(rev, path):
    key = (rev, path)
    if key not in _show_cache:
        r = subprocess.run(["git", "-C", ROOT, "show", f"{rev}:{path}"], capture_output=True, text=True, errors="replace")
        _show_cache[key] = r.stdout if r.returncode == 0 else None
    return _show_cache[key]

def rev_ok(rev):
    return subprocess.run(["git", "-C", ROOT, "rev-parse", "--verify", "-q", f"{rev}^{{commit}}"], capture_output=True).returncode == 0

def path_exists(rev, path):
    return subprocess.run(["git", "-C", ROOT, "cat-file", "-e", f"{rev}:{path}"], capture_output=True).returncode == 0

def symbol_missing(path, sym, content):
    """Return a problem string if `sym` is not present in `content`, else None.

    Code files: the symbol must occur as a whole word (regex word boundaries), so a short or
    common identifier cannot match inside a longer one.
    YAML files: the symbol is a dotted key path (e.g. services.redis.command); every segment
    must occur as a mapping key (`segment:`) or a compose list entry (`- SEGMENT=`) in the
    file. Nesting is not verified.
    """
    if path.endswith((".yml", ".yaml")):
        for seg in sym.split("."):
            # a mapping key (`seg:`) or a compose-style list entry (`- SEG=value`)
            if not re.search(r"^\s*-?\s*" + re.escape(seg) + r"\s*[:=]", content, re.M):
                return f"YAML key '{seg}' (from path '{sym}') not found"
        return None
    if not re.search(r"(?<![A-Za-z0-9_$])" + re.escape(sym) + r"(?![A-Za-z0-9_$])", content):
        return f"symbol '{sym}' not found as a whole word"
    return None

def check(path):
    probs = []
    p = lambda m: probs.append(f"{os.path.basename(path)}: {m}")
    try:
        c = yaml.safe_load(open(path))
    except Exception as e:
        return [f"{os.path.basename(path)}: YAML parse error: {e}"], None
    if not isinstance(c, dict):
        return [f"{os.path.basename(path)}: not a mapping"], None
    cid = c.get("id")
    if cid != os.path.basename(path)[:-5]:
        p(f"id '{cid}' does not match filename")
    kind = c.get("kind")
    if kind not in KINDS:
        p(f"kind '{kind}' not in {sorted(KINDS)}")
        return probs, c
    for k in ("fix_pr", "summary", "expected_findings"):
        if k not in c:
            p(f"missing key '{k}'")
    if c.get("confidence") not in CONF:
        p(f"confidence '{c.get('confidence')}' not in {sorted(CONF)}")
    fix_merge = str(c.get("fix_merge") or "")
    pinned = str(c.get("input_rev") or "")
    # A case is "unmerged" while no fix has landed: either no fix exists at all (fix_pr: pending) or
    # the fix PR is open but not squashed yet, so its merge sha cannot exist to be named. Both pin
    # input_rev instead. skillars-deferred-166 added the second form: the fix PR itself has to set
    # fix_pr to its own number and still validate, and fix_merge arrives in a follow-up commit.
    pending = str(c.get("fix_pr")) == "pending" or (not fix_merge and bool(pinned))
    if pending:
        if not pinned:
            p("a case with no fix_merge requires input_rev (fix_pr: pending, or a PR not yet merged)")
        elif not rev_ok(pinned):
            p(f"input_rev {pinned} does not resolve")
    else:
        if not fix_merge:
            p("missing key 'fix_merge'")
        elif not rev_ok(fix_merge):
            p(f"fix_merge {fix_merge} does not resolve")
    if kind == "introducing-diff":
        im = str(c.get("introducing_merge", ""))
        if not im:
            p("introducing-diff needs introducing_merge")
            input_rev = None
        elif not rev_ok(im):
            p(f"introducing_merge {im} does not resolve"); input_rev = None
        else:
            input_rev = im
        if "introducing_pr" not in c:
            p("introducing-diff needs introducing_pr")
    else:
        if pending:
            input_rev = pinned if pinned and rev_ok(pinned) else None
        else:
            input_rev = f"{fix_merge}^1" if fix_merge and rev_ok(fix_merge) else None
        scope = c.get("scope") or []
        if not scope:
            p("pre-fix-snapshot needs a non-empty scope")
        for s in scope:
            if input_rev and not path_exists(input_rev, s):
                p(f"scope path missing at {input_rev}: {s}")
    finds = c.get("expected_findings") or []
    if not finds:
        p("no expected_findings")
    ids = collections.Counter(f.get("id") for f in finds)
    for fid, n in ids.items():
        if n > 1:
            p(f"duplicate finding id {fid}")
    for f in finds:
        fid = f.get("id", "?")
        for k in ("title", "category", "severity", "description", "anchors"):
            if k not in f:
                p(f"{fid}: missing '{k}'")
        if f.get("category") not in CATS:
            p(f"{fid}: category '{f.get('category')}' not in {sorted(CATS)}")
        if f.get("severity") not in SEVS:
            p(f"{fid}: severity '{f.get('severity')}' not in {sorted(SEVS)}")
        for a in f.get("anchors") or []:
            fp = a.get("file"); sym = a.get("symbol")
            if not fp:
                p(f"{fid}: anchor without file"); continue
            if input_rev is None:
                continue
            content = git_show(input_rev, fp)
            if content is None:
                p(f"{fid}: anchor file missing at {input_rev}: {fp}")
            elif sym:
                missing = symbol_missing(fp, str(sym), content)
                if missing:
                    p(f"{fid}: {missing} in {fp} at {input_rev}")
    return probs, c

def main(argv):
    files = argv or sorted(glob.glob(os.path.join(ROOT, "evals/review/cases/*.yaml")))
    all_probs = []; cases = 0; findings = 0; cats = collections.Counter(); kinds = collections.Counter()
    for f in files:
        probs, c = check(f)
        all_probs += probs
        if c and isinstance(c, dict):
            cases += 1
            kinds[c.get("kind")] += 1
            for ef in c.get("expected_findings") or []:
                findings += 1; cats[ef.get("category")] += 1
    for m in all_probs:
        print("PROBLEM", m)
    print(f"\n{cases} cases, {findings} expected findings, {len(all_probs)} problems")
    print("kinds:", dict(kinds))
    print("categories:", dict(cats.most_common()))
    return 1 if all_probs else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
