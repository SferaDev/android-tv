#!/usr/bin/env python3
"""Point every feature link in the fork README at its commit on dev.

Each link is followed by `<!-- commit: <subject> -->`; the SHA is looked up by that subject among the commits below
the docs commit, so a rebase only changes the SHAs. Run after every rebase, then amend the docs commit:

    python3 tools/fork_readme_commits.py && git commit -a --amend --no-edit
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
LINK = re.compile(r"(https://github\.com/SferaDev/android-tv/commit/)[0-9a-f]*(\)\s*<!-- commit: (.+?) -->)")


def commits():
    log = subprocess.run(["git", "log", "--reverse", "--format=%H %s", "upstream/main..HEAD~1"],
                         cwd=ROOT, check=True, capture_output=True, text=True).stdout
    return {line[41:]: line[:40] for line in log.splitlines()}


def main():
    shas = commits()
    text = README.read_text(encoding="utf-8")
    missing = [m.group(3) for m in LINK.finditer(text) if m.group(3) not in shas]
    if missing:
        sys.exit("no commit on dev with subject:\n  " + "\n  ".join(missing))
    updated = LINK.sub(lambda m: m.group(1) + shas[m.group(3)] + m.group(2), text)
    if updated != text:
        README.write_text(updated, encoding="utf-8")
    print(f"{len(LINK.findall(updated))} links {'updated' if updated != text else 'already current'}")


if __name__ == "__main__":
    main()
