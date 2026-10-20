---
name: sync-upstream
description: Use when upstream Gathin23/cleanyourtv has new commits and the fork needs to catch up, or when asked to sync, rebase dev, or update main from upstream.
---

# Sync with upstream

Only on request, in a dev worktree.

1. `git fetch --multiple origin upstream` (plain `git fetch origin upstream` fetches a branch named upstream from origin). Push only if main fast-forwards: `git merge-base --is-ancestor origin/main upstream/main && git push origin upstream/main:main`.
2. `git rebase upstream/main`. For each upstream change: port it if it fits our UI and features, drop it if it only touches code the redesign removed. List every decision for the user.
3. Conflicts (`--ours` is upstream plus replayed commits, `--theirs` is our commit):
   - `README.md`: in the docs commit keep ours (`git checkout --theirs README.md`); in a debloat list commit, if dev carries one, take upstream's and re-add our row.
   - Upstream test files that read README.md: take upstream's (`git checkout --ours <file>`) and put `@upstream_readme` back on its README tests, with `from fork_readme import upstream_readme`.
   - `examples/README.md`: both sides' rows, sorted. `values-*/strings.xml`: upstream's strings plus ours, in every folder.
   - `AGENTS.md` (add/add): keep ours (`git checkout --theirs AGENTS.md`). `CLAUDE.md`: take upstream's, which imports AGENTS.md.
   - `Config.java` and `values/config.xml`: start from upstream's side (`git checkout --ours`) and re-add the commit's own fields and keys; never drop upstream keys.
   - `MainActivity.java` in the Home redesign: our side wins where the redesign replaced upstream code; port upstream changes to code we kept (inputs, settings, boot actions). Then check lifecycle pairs by hand (`registerReceiver`/`unregisterReceiver`, listeners): a clean auto-merge can register in one callback and unregister in another.
   - A new upstream `values-<locale>` folder gets the strings of every commit that covers all locales.
   - A new upstream test that reads README.md, AGENTS.md or upstream's Home code gets `@upstream_readme`, `@upstream_agents` or `@upstream_home` in the docs commit. A fork key in `themes/README.md` also goes in `FORK_THEME_KEYS` (`tests/fork_readme.py`).
   - Fix a conflict in the commit it belongs to; later fixes go in with `--fixup` and `--autosquash`.
4. Every commit must build: `git rebase upstream/main -x 'cd launcher && ./gradlew testDebugUnitTest -q'`. Then `python3 -m unittest discover -s tests` and `./gradlew lintDebug assembleRelease` (the APK size gate is upstream's; the fork exceeds it).
5. `git log --merges upstream/main..HEAD` prints nothing; drop commits upstream merged.
6. On the docs commit: `python3 tools/fork_readme_commits.py && git commit -a --amend --no-edit` (skill `update-fork-readme`).
7. Tell the user what changed; then `git push --force-with-lease origin dev`.
