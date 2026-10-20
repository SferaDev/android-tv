---
name: fork-hygiene
description: Use for periodic cleanup of the fork: stale worktrees and branches, dev drift from main, README accuracy, tracked secrets, gitignore gaps and TV-side drift.
---

# Fork hygiene

Report findings; do not delete or push without telling the user.

1. Worktrees and branches:
   ```sh
   git fetch --prune --multiple origin upstream
   git worktree list && git branch -vv && git branch -r --merged origin/main
   ```
   Propose removing merged worktrees (`git worktree remove <path>`) and branches.
2. `origin/main` equals `upstream/main`; `git rev-list --count origin/dev..origin/main` is 0, else skill `sync-upstream`.
3. `git log --merges upstream/main..origin/dev` prints nothing, the docs commit is last, and `python3 tools/fork_readme_commits.py` reports the links current (skill `update-fork-readme`).
4. Nothing private is tracked:
   ```sh
   git ls-files | grep -E '^backup/|adbkey|\.apk$|local\.properties'   # must print nothing
   git grep -nE '192\.168\.|android_id|api[_-]?key|token' -- ':!tests' ':!tools' | head
   ```
5. `tools/venv` as a symlink is not matched by `.gitignore`'s `tools/venv/`; suggest `tools/venv` as a fork-only fix.
6. TV drift: packages disabled on the TV but missing from `backup/restore.sh`:
   ```sh
   tools/venv/bin/python tools/tv.py 'pm list packages -d' | sort > $TMPDIR/disabled.txt
   grep -oE '[a-z][a-z0-9_.]+\.[a-z0-9_.]+' backup/restore.sh | sort -u | sed 's/^/package:/' > $TMPDIR/restore.txt
   comm -23 $TMPDIR/disabled.txt $TMPDIR/restore.txt
   ```
   Add them to `backup/restore.sh` (never commit it).
