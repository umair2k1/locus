#!/usr/bin/env python3
"""Locus Vibe-Coding Orchestrator & Software Factory Loop.

Adapted from StoreOS architecture for Locus Android.
Parses doc/PROMPTS.md, enforces sequential execution, executes prompts via CLI
agent (omp), verifies with CI gates (spotless, import-hygiene, detekt, test),
and commits per-prompt deliverables.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

ROOT = Path(__file__).resolve().parent.parent
PROMPTS_FILE = ROOT / "doc" / "PROMPTS.md"
BUILD_LOG_FILE = ROOT / "doc" / "BUILD_LOG.md"


def get_git_status() -> Dict[str, Any]:
    """Inspect local git state."""
    try:
        branch = subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=ROOT,
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
    except Exception:
        branch = "unknown"

    try:
        porcelain = subprocess.check_output(
            ["git", "status", "--porcelain"],
            cwd=ROOT,
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
        is_clean = len(porcelain) == 0
    except Exception:
        is_clean = False

    try:
        last_commit = subprocess.check_output(
            ["git", "log", "-1", "--oneline"],
            cwd=ROOT,
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
    except Exception:
        last_commit = "none"

    return {
        "branch": branch,
        "is_clean": is_clean,
        "last_commit": last_commit,
    }


def parse_prompts_file() -> List[Dict[str, Any]]:
    """Parse doc/PROMPTS.md into structured prompt objects."""
    if not PROMPTS_FILE.exists():
        sys.stderr.write(f"Error: {PROMPTS_FILE} not found.\n")
        sys.exit(1)

    text = PROMPTS_FILE.read_text(encoding="utf-8")
    # Matches: # PROMPT <num> — <title>
    pattern = re.compile(
        r"^# PROMPT (\d+)\s+—\s+(.*?)$", re.M
    )
    matches = list(pattern.finditer(text))
    prompts = []
    for i, m in enumerate(matches):
        num = int(m.group(1))
        title = m.group(2).strip()
        start_pos = m.start()
        end_pos = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        content = text[start_pos:end_pos].strip()

        # Extract commit message
        commit_m = re.search(r"## Commit\s+`([^`]+)`", content)
        commit_msg = commit_m.group(1) if commit_m else f"feat: implement prompt {num} ({title})"

        # Extract verify command if any
        verify_m = re.search(r"## Verify\s+`([^`]+)`", content)
        verify_cmd = verify_m.group(1) if verify_m else None

        prompts.append({
            "num": num,
            "title": title,
            "commit_msg": commit_msg,
            "verify_cmd": verify_cmd,
            "content": content,
        })
    return prompts


def get_git_completed_prompts(prompts: List[Dict[str, Any]]) -> List[int]:
    """Determine completed prompt numbers from git log commits."""
    try:
        log_output = subprocess.check_output(
            ["git", "log", "--oneline", "-n", "300"],
            cwd=ROOT,
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except Exception:
        log_output = ""

    completed = set()
    for p in prompts:
        # Check if the commit message or prefix appears in git log
        # Typically commits follow: feat(domain,ai): nine write-tool contracts...
        # or have the prompt commit string
        key_phrase = p["commit_msg"].split("—")[0].strip()
        # Clean conventional commit prefix for fuzzy match
        clean_msg = re.sub(r"^[a-z]+(\([^\)]+\))?:\s*", "", p["commit_msg"]).strip()
        clean_first_words = " ".join(clean_msg.split()[:4])

        if clean_first_words.lower() in log_output.lower():
            completed.add(p["num"])

    # Any prompt prior to max completed is considered completed in strict sequential order
    if completed:
        max_done = max(completed)
        return [p["num"] for p in prompts if p["num"] <= max_done]
    return []


def get_state() -> Dict[str, Any]:
    """Compute current runner state."""
    prompts = parse_prompts_file()
    completed = get_git_completed_prompts(prompts)
    completed_set = set(completed)

    next_prompt = None
    for p in prompts:
        if p["num"] not in completed_set:
            next_prompt = p
            break

    git = get_git_status()
    return {
        "prompts": prompts,
        "completed": completed,
        "next_prompt": next_prompt,
        "git": git,
        "total": len(prompts),
    }


def cmd_status() -> int:
    """Print progress status."""
    st = get_state()
    total = st["total"]
    done_count = len(st["completed"])
    pct = (done_count / total * 100) if total else 0.0

    print("=================================================================")
    print(f"  Locus Software Factory - Autonomous Runner")
    print(f"  Progress:    {done_count}/{total} prompts ({pct:.1f}%)")
    print(f"  Git Branch:  {st['git']['branch']} (clean: {st['git']['is_clean']})")
    print(f"  Last Commit: {st['git']['last_commit']}")
    print("-----------------------------------------------------------------")
    if st["next_prompt"] is None:
        print("  ALL PROMPTS COMPLETED! Repo is fully built.")
    else:
        np = st["next_prompt"]
        print(f"  NEXT UP:     Prompt {np['num']} — {np['title']}")
        print(f"  Target Msg:  {np['commit_msg']}")
    print("=================================================================")
    return 0


def run_ci_verification(specific_cmd: Optional[str] = None) -> bool:
    """Run verification checks."""
    print("\n[VERIFY] Running CI checks...")

    # 1. Spotless apply + check
    print("  -> Spotless check...", end=" ", flush=True)
    res = subprocess.run(["sh", "./gradlew", "spotlessApply", "spotlessCheck"], cwd=ROOT, capture_output=True, text=True)
    if res.returncode != 0:
        print("FAIL")
        print(res.stdout + res.stderr)
        return False
    print("PASS")

    # 2. Import hygiene
    print("  -> Import hygiene...", end=" ", flush=True)
    res = subprocess.run(["sh", "./scripts/import-hygiene.sh"], cwd=ROOT, capture_output=True, text=True)
    if res.returncode != 0:
        print("FAIL")
        print(res.stdout + res.stderr)
        return False
    print("PASS")

    # 3. Specific verify command if given in prompt
    if specific_cmd:
        print(f"  -> Prompt verification (`{specific_cmd}`)...", end=" ", flush=True)
        # Parse command
        cmd_parts = specific_cmd.split()
        if cmd_parts[0] == "./gradlew":
            cmd_parts = ["sh", "./gradlew"] + cmd_parts[1:]
        res = subprocess.run(cmd_parts, cwd=ROOT, capture_output=True, text=True)
        if res.returncode != 0:
            print("FAIL")
            print(res.stdout + res.stderr)
            return False
        print("PASS")

    # 4. Detekt
    print("  -> Detekt...", end=" ", flush=True)
    res = subprocess.run(["sh", "./gradlew", "detekt"], cwd=ROOT, capture_output=True, text=True)
    if res.returncode != 0:
        print("FAIL")
        print(res.stdout + res.stderr)
        return False
    print("PASS")

    # 5. Compile both flavors
    print("  -> Assemble flavors...", end=" ", flush=True)
    res = subprocess.run(["sh", "./gradlew", ":app:assembleOssDebug", ":app:assembleFullDebug"], cwd=ROOT, capture_output=True, text=True)
    if res.returncode != 0:
        print("FAIL")
        print(res.stdout + res.stderr)
        return False
    print("PASS")

    return True


def execute_prompt_with_omp(prompt: Dict[str, Any], max_retries: int = 2) -> bool:
    """Execute a single prompt using omp agent CLI."""
    num = prompt["num"]
    title = prompt["title"]
    content = prompt["content"]
    commit_msg = prompt["commit_msg"]

    system_instruction = (
        "You are an expert autonomous software engineer working on the Locus Android project. "
        "Strictly adhere to the instructions in the prompt. "
        "Create or edit only the files required. "
        "Ensure all tests pass and code adheres to zero-allocation/clean architecture guidelines. "
        "Do not stop until the implementation is complete."
    )

    prompt_file = ROOT / "tools" / f"_current_prompt_{num}.md"
    prompt_file.write_text(content, encoding="utf-8")

    omp_cmd = [
        "omp",
        "--auto-approve",
        "-p",
        f"Implement Prompt {num} from file @tools/_current_prompt_{num}.md. Follow every instruction, create/modify all files specified, and make sure code compiles."
    ]

    for attempt in range(1, max_retries + 1):
        print(f"\n=================================================================")
        print(f"  RUNNING PROMPT {num} (Attempt {attempt}/{max_retries}): {title}")
        print(f"=================================================================")

        start_time = time.time()
        res = subprocess.run(omp_cmd, cwd=ROOT)
        elapsed = time.time() - start_time
        print(f"\nAgent run finished in {elapsed:.1f}s (Exit code: {res.returncode})")

        # Verify
        if run_ci_verification(prompt["verify_cmd"]):
            print(f"\n[SUCCESS] Prompt {num} passed verification!")
            # Git commit
            try:
                subprocess.check_call(["git", "add", "-A"], cwd=ROOT)
                subprocess.check_call(["git", "commit", "-m", commit_msg], cwd=ROOT)
                print(f"[GIT COMMIT] {commit_msg}")
            except Exception as e:
                print(f"[WARN] Git commit failed (maybe clean?): {e}")

            # Clean temp file
            if prompt_file.exists():
                prompt_file.unlink()
            return True
        else:
            print(f"\n[FAILURE] Verification failed for Prompt {num} (Attempt {attempt}).")
            if attempt < max_retries:
                # Ask agent to fix errors
                fix_cmd = [
                    "omp",
                    "--auto-approve",
                    "-p",
                    f"The previous implementation of Prompt {num} failed CI checks. Please inspect the code, run `./gradlew detekt` and `./gradlew test` to find the exact errors, and fix all compilation, detekt, or test errors."
                ]
                subprocess.run(fix_cmd, cwd=ROOT)

    if prompt_file.exists():
        prompt_file.unlink()
    return False


def cmd_loop(max_prompts: Optional[int] = None) -> int:
    """Run autonomous execution loop across prompts."""
    count = 0
    while True:
        if max_prompts is not None and count >= max_prompts:
            print(f"\nReached max prompt count limit ({max_prompts}). Stopping loop.")
            break

        st = get_state()
        np = st["next_prompt"]
        if np is None:
            print("\nAll prompts in doc/PROMPTS.md are completed! Done.")
            break

        print(f"\n>>> Starting next prompt in sequence: Prompt {np['num']} — {np['title']}")
        success = execute_prompt_with_omp(np)
        if not success:
            print(f"\n[HALT] Prompt {np['num']} failed verification after retries. Pausing loop to avoid corrupting codebase.")
            return 1

        count += 1
        print(f"\nPrompt {np['num']} complete. Cooling down 5s before next prompt...")
        time.sleep(5)

    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Locus Vibe-Coding Factory Runner")
    sub = parser.add_subparsers(dest="command")

    sub.add_parser("status", help="Show current prompt progress and status")
    loop_parser = sub.add_parser("loop", help="Run autonomous loop over prompts")
    loop_parser.add_argument("--count", type=int, default=None, help="Max number of prompts to run before stopping")
    verify_parser = sub.add_parser("verify", help="Run CI verification gates")

    args = parser.parse_args()
    if args.command == "status" or args.command is None:
        return cmd_status()
    elif args.command == "loop":
        return cmd_loop(max_prompts=args.count)
    elif args.command == "verify":
        success = run_ci_verification()
        return 0 if success else 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
