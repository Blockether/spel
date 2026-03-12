#!/usr/bin/env python3

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


AUTH_ENV_VARS = [
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "GITHUB_TOKEN",
    "Z_AI_API_KEY",
]


def parse_args():
    parser = argparse.ArgumentParser(
        description="Run real OpenCode behavioral evals for spel agents"
    )
    parser.add_argument("--binary", default="./target/spel", help="Path to spel binary")
    parser.add_argument(
        "--config", default="evals/real_evals.json", help="Path to real eval config"
    )
    parser.add_argument(
        "--case", action="append", help="Run only the named real eval case"
    )
    parser.add_argument("--model", help="Override model for all real eval cases")
    parser.add_argument("--json", action="store_true", help="Emit JSON output")
    parser.add_argument(
        "--keep-workdir", action="store_true", help="Keep temp workdirs for inspection"
    )
    return parser.parse_args()


def load_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def detect_auth_state():
    present = [name for name in AUTH_ENV_VARS if os.getenv(name)]
    return {"available": bool(present), "vars": present}


def copy_if_exists(src: Path, dest: Path):
    if src.exists():
        shutil.copy(src, dest)


def scaffold_workspace(repo_root: Path, binary: Path, case):
    workdir = Path(tempfile.mkdtemp(prefix=f"spel-real-eval-{case['id']}-"))
    copy_if_exists(repo_root / "opencode.json", workdir / "opencode.json")
    copy_if_exists(repo_root / "AGENTS.md", workdir / "AGENTS.md")

    cmd = [
        str(binary),
        "init-agents",
        "--ns",
        "spel.evals",
        f"--loop={case['loop']}",
        f"--only={case['only']}",
        "--no-tests",
        "--force",
    ]
    proc = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True, timeout=180)
    return workdir, proc


def run_opencode_case(workdir: Path, case, model_override=None):
    model = model_override or case.get("model", "openai/gpt-5.3-codex")
    cmd = [
        "opencode",
        "run",
        "--format",
        "json",
        "-m",
        model,
        "--agent",
        case["agent"],
        "--dir",
        str(workdir),
        case["message"],
    ]
    timeout_seconds = int(case.get("timeout_seconds", 45))
    try:
        proc = subprocess.run(
            cmd, cwd=workdir, capture_output=True, text=True, timeout=timeout_seconds
        )
        return {
            "status": "completed",
            "returncode": proc.returncode,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
            "command": cmd,
        }
    except subprocess.TimeoutExpired as exc:
        stdout = (
            exc.stdout.decode("utf-8", errors="replace")
            if isinstance(exc.stdout, bytes)
            else (exc.stdout or "")
        )
        stderr = (
            exc.stderr.decode("utf-8", errors="replace")
            if isinstance(exc.stderr, bytes)
            else (exc.stderr or "")
        )
        return {
            "status": "timeout",
            "returncode": None,
            "stdout": stdout,
            "stderr": stderr,
            "command": cmd,
        }


def inspect_artifacts(workdir: Path, case):
    findings = []
    all_present = True
    contains_ok = True
    for artifact in case.get("expected_artifacts", []):
        path = workdir / artifact
        exists = path.exists()
        all_present = all_present and exists
        entry = {
            "path": artifact,
            "exists": exists,
            "contains": {},
        }
        if exists and path.is_file():
            content = path.read_text(encoding="utf-8")
            for needle in case.get("artifact_contains", {}).get(artifact, []):
                ok = needle in content
                entry["contains"][needle] = ok
                contains_ok = contains_ok and ok
        else:
            for needle in case.get("artifact_contains", {}).get(artifact, []):
                entry["contains"][needle] = False
                contains_ok = False
        findings.append(entry)
    return findings, all_present, contains_ok


def classify_result(auth_state, opencode_run, artifacts_present, contains_ok):
    combined_output = (
        opencode_run.get("stdout", "") + "\n" + opencode_run.get("stderr", "")
    ).lower()
    if artifacts_present and contains_ok:
        return "pass"
    if "insufficient balance" in combined_output or "creditserror" in combined_output:
        return "blocked_runtime_billing"
    if not auth_state["available"] and opencode_run["status"] == "timeout":
        return "blocked_runtime_auth"
    if opencode_run["status"] == "timeout":
        return "blocked_runtime_timeout"
    return "fail"


def run_case(
    repo_root: Path,
    binary: Path,
    auth_state,
    case,
    keep_workdir: bool,
    model_override=None,
):
    workdir, scaffold = scaffold_workspace(repo_root, binary, case)
    opencode_run = run_opencode_case(workdir, case, model_override=model_override)
    artifacts, artifacts_present, contains_ok = inspect_artifacts(workdir, case)
    status = classify_result(auth_state, opencode_run, artifacts_present, contains_ok)

    result = {
        "id": case["id"],
        "description": case["description"],
        "workdir": str(workdir),
        "scaffold": {
            "returncode": scaffold.returncode,
            "stdout": scaffold.stdout,
            "stderr": scaffold.stderr,
        },
        "opencode_run": opencode_run,
        "artifacts": artifacts,
        "status": status,
    }

    if not keep_workdir:
        shutil.rmtree(workdir)

    return result


def print_human(payload):
    print("spel real agent evals")
    print("")
    print(f"auth available: {payload['auth']['available']}")
    print(
        f"auth vars: {', '.join(payload['auth']['vars']) if payload['auth']['vars'] else 'none'}"
    )
    print("")
    for case in payload["cases"]:
        print(f"- {case['id']}: {case['status']}")
        print(f"  {case['description']}")
        print(f"  scaffold rc={case['scaffold']['returncode']}")
        print(f"  opencode status={case['opencode_run']['status']}")
        for artifact in case["artifacts"]:
            print(
                f"    artifact {artifact['path']} exists={artifact['exists']} contains={artifact['contains']}"
            )
        print("")


def main():
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    binary = (
        (repo_root / args.binary).resolve()
        if not Path(args.binary).is_absolute()
        else Path(args.binary)
    )
    config = load_json(
        (repo_root / args.config).resolve()
        if not Path(args.config).is_absolute()
        else Path(args.config)
    )
    auth_state = detect_auth_state()

    cases = config["cases"]
    if args.case:
        requested = set(args.case)
        cases = [case for case in cases if case["id"] in requested]

    results = [
        run_case(
            repo_root,
            binary,
            auth_state,
            case,
            args.keep_workdir,
            model_override=args.model,
        )
        for case in cases
    ]
    summary = {
        "case_count": len(results),
        "pass": sum(1 for case in results if case["status"] == "pass"),
        "blocked_runtime_billing": sum(
            1 for case in results if case["status"] == "blocked_runtime_billing"
        ),
        "blocked_runtime_auth": sum(
            1 for case in results if case["status"] == "blocked_runtime_auth"
        ),
        "blocked_runtime_timeout": sum(
            1 for case in results if case["status"] == "blocked_runtime_timeout"
        ),
        "fail": sum(1 for case in results if case["status"] == "fail"),
    }
    payload = {"auth": auth_state, "cases": results, "summary": summary}

    if args.json:
        print(json.dumps(payload, indent=2))
    else:
        print_human(payload)

    return 0 if summary["fail"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
