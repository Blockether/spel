#!/usr/bin/env python3

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def parse_args():
    parser = argparse.ArgumentParser(description="Run spel agent scaffold evals")
    parser.add_argument("--binary", default="./target/spel", help="Path to spel binary")
    parser.add_argument(
        "--config", default="evals/evals.json", help="Path to eval config JSON"
    )
    parser.add_argument(
        "--case", action="append", help="Run only the named eval case (repeatable)"
    )
    parser.add_argument(
        "--json", action="store_true", help="Emit JSON instead of plain text"
    )
    parser.add_argument(
        "--strict-advisory", action="store_true", help="Fail when advisory checks fail"
    )
    parser.add_argument(
        "--keep-workdir", action="store_true", help="Keep temp workdirs for inspection"
    )
    return parser.parse_args()


def load_config(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def ensure_force(args):
    if "--force" in args:
        return list(args)
    return list(args) + ["--force"]


def read_text(path):
    return path.read_text(encoding="utf-8")


def evaluate_check(workdir, check):
    path = workdir / check["path"]
    kind = check["kind"]

    if kind == "exists":
        passed = path.exists()
        detail = f"exists={passed} path={check['path']}"
    elif kind == "absent":
        passed = not path.exists()
        detail = f"absent={passed} path={check['path']}"
    elif kind == "contains":
        if not path.exists():
            passed = False
            detail = f"missing file: {check['path']}"
        else:
            content = read_text(path)
            passed = check["value"] in content
            detail = f"contains={passed} path={check['path']} needle={check['value']}"
    elif kind == "all_contains":
        if not path.exists():
            passed = False
            detail = f"missing file: {check['path']}"
        else:
            content = read_text(path)
            missing = [value for value in check["values"] if value not in content]
            passed = not missing
            detail = f"all_contains={passed} path={check['path']}" + (
                " missing=" + ", ".join(missing) if missing else ""
            )
    else:
        raise ValueError(f"Unsupported check kind: {kind}")

    return {
        "id": check["id"],
        "required": bool(check.get("required", False)),
        "passed": passed,
        "why": check.get("why", ""),
        "detail": detail,
    }


def run_case(binary, case, keep_workdir):
    temp_dir = Path(tempfile.mkdtemp(prefix=f"spel-eval-{case['id']}-"))
    cmd = [str(binary)] + ensure_force(case["args"])
    proc = subprocess.run(
        cmd,
        cwd=temp_dir,
        capture_output=True,
        text=True,
        timeout=180,
    )

    checks = [evaluate_check(temp_dir, check) for check in case["checks"]]
    required_failed = sum(
        1 for check in checks if check["required"] and not check["passed"]
    )
    advisory_failed = sum(
        1 for check in checks if (not check["required"]) and not check["passed"]
    )

    result = {
        "id": case["id"],
        "description": case["description"],
        "command": cmd,
        "workdir": str(temp_dir),
        "init_agents": {
            "returncode": proc.returncode,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
        },
        "checks": checks,
        "required_failed": required_failed,
        "advisory_failed": advisory_failed,
        "status": "pass" if proc.returncode == 0 and required_failed == 0 else "fail",
    }

    if not keep_workdir:
        shutil.rmtree(temp_dir)

    return result


def print_human(results, strict_advisory):
    print("spel agent evals")
    print("")
    for case in results["cases"]:
        print(f"- {case['id']}: {case['status']}")
        print(f"  {case['description']}")
        print(f"  init-agents rc={case['init_agents']['returncode']}")
        for check in case["checks"]:
            marker = "PASS" if check["passed"] else "FAIL"
            level = "required" if check["required"] else "advisory"
            print(f"    [{marker}] {check['id']} ({level})")
            if not check["passed"]:
                print(f"      why: {check['why']}")
                print(f"      detail: {check['detail']}")
        print("")

    summary = results["summary"]
    print("summary")
    print(f"- cases: {summary['case_count']}")
    print(f"- required failed: {summary['required_failed']}")
    print(f"- advisory failed: {summary['advisory_failed']}")
    print(f"- strict advisory: {strict_advisory}")


def main():
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    binary = (
        (repo_root / args.binary).resolve()
        if not Path(args.binary).is_absolute()
        else Path(args.binary)
    )
    config_path = (
        (repo_root / args.config).resolve()
        if not Path(args.config).is_absolute()
        else Path(args.config)
    )

    if not binary.exists():
        print(f"Binary not found: {binary}", file=sys.stderr)
        return 2

    config = load_config(config_path)
    cases = config["cases"]
    if args.case:
        requested = set(args.case)
        cases = [case for case in cases if case["id"] in requested]

    if not cases:
        print("No eval cases selected", file=sys.stderr)
        return 2

    results = [run_case(binary, case, args.keep_workdir) for case in cases]
    summary = {
        "case_count": len(results),
        "required_failed": sum(case["required_failed"] for case in results),
        "advisory_failed": sum(case["advisory_failed"] for case in results),
    }
    payload = {"cases": results, "summary": summary}

    if args.json:
        print(json.dumps(payload, indent=2))
    else:
        print_human(payload, args.strict_advisory)

    if summary["required_failed"] > 0:
        return 1
    if args.strict_advisory and summary["advisory_failed"] > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
