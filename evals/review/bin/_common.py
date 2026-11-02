"""Shared helpers for the review-eval runner, judge and scorecard."""
import json, os, re, subprocess, sys
import yaml

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
# SKILLARS_REVIEW_EVALS_DIR lets the scripts run from a staging copy (prompts, results) while
# cases and materialize.sh always come from the repo.
EVALS = os.environ.get("SKILLARS_REVIEW_EVALS_DIR") or os.path.join(ROOT, "evals", "review")
CASES = os.path.join(ROOT, "evals", "review", "cases")
PROMPTS = os.path.join(EVALS, "prompts")
RESULTS = os.path.join(EVALS, "results")
WORK = os.environ.get("SKILLARS_REVIEW_EVALS_WORK") or os.path.join(
    os.environ.get("XDG_CACHE_HOME") or os.path.join(os.path.expanduser("~"), ".cache"),
    "skillars-review-evals")

def load_case(cid):
    path = os.path.join(CASES, f"{cid}.yaml")
    if not os.path.exists(path):
        path = os.path.join(CASES, "_drafts", f"{cid}.yaml")
    with open(path) as fh:
        return yaml.safe_load(fh)

def promoted_case_ids():
    return sorted(f[:-5] for f in os.listdir(CASES) if f.endswith(".yaml"))

def read_prompt(name):
    with open(os.path.join(PROMPTS, name)) as fh:
        return fh.read()

def materialize(cid):
    subprocess.run([os.path.join(ROOT, "evals", "review", "bin", "materialize.sh"), cid], check=True,
                   stdout=subprocess.DEVNULL)
    work = os.path.join(WORK, cid)
    with open(os.path.join(work, "input.rev")) as fh:
        rev = fh.read().strip()
    return work, rev

def headless_env():
    env = dict(os.environ)
    # Headless mode silently prefers an API key over the subscription login. Never let a
    # stray key from the shell decide what an eval run is billed to.
    env.pop("ANTHROPIC_API_KEY", None)
    return env

def run_claude(prompt, *, cwd, schema_path, tools, max_turns, model=None, effort=None,
               add_args=()):
    """Run `claude -p` and return (parsed structured output or None, raw result dict)."""
    with open(schema_path) as fh:
        schema = fh.read()
    cmd = ["claude", "-p", "--output-format", "json", "--json-schema", schema,
           "--setting-sources", "project", "--no-session-persistence",
           "--max-turns", str(max_turns), "--tools", tools]
    if model:
        cmd += ["--model", model]
    if effort:
        cmd += ["--effort", effort]
    cmd += list(add_args)
    proc = subprocess.run(cmd, input=prompt, cwd=cwd, env=headless_env(),
                          capture_output=True, text=True)
    raw = {"exit_code": proc.returncode, "stderr": proc.stderr[-4000:]}
    try:
        out = json.loads(proc.stdout)
    except json.JSONDecodeError:
        raw["stdout"] = proc.stdout[-8000:]
        return None, raw
    raw.update({k: out.get(k) for k in ("subtype", "is_error", "total_cost_usd", "duration_ms",
                                         "num_turns", "session_id")})
    parsed = out.get("structured_output")
    if parsed is None:
        text = out.get("result") or ""
        m = re.search(r"```(?:json)?\s*(\{.*\})\s*```", text, re.S) or re.search(r"(\{.*\})", text, re.S)
        if m:
            try:
                parsed = json.loads(m.group(1))
            except json.JSONDecodeError:
                parsed = None
        if parsed is None:
            raw["result_text"] = text[-8000:]
    return parsed, raw

def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as fh:
        json.dump(obj, fh, indent=2, sort_keys=True)
        fh.write("\n")

def read_json(path):
    with open(path) as fh:
        return json.load(fh)

def log(msg):
    print(msg, file=sys.stderr, flush=True)
