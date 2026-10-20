# AGENTS.md
<!-- fork-agents -->

SferaDev/android-tv: a personal fork of Gathin23/cleanyourtv (debloat lists plus a launcher in `launcher/`), with a Google TV style launcher tuned for one TV (Xiaomi MiTV-MOEU0, Google TV 14). Skills are in `.claude/skills/`.

## Branches

- `origin` is the fork, `upstream` is Gathin23/cleanyourtv.
- `main` mirrors `upstream/main`, fast-forward only; never commit to it.
- `dev` is `upstream/main` plus one commit per feature, the docs commit last. It is rebased on request and never merged (not even GitHub's "Sync fork").
- `pr/<topic>` branches start from `upstream/main`, never from `dev`, and carry one commit each for an upstream PR.

## Commits

- A fix goes into the commit it fixes: `git commit --fixup=<sha>`, then `GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash upstream/main`.
- Rebasing `dev` on upstream (skill `sync-upstream`): port upstream changes that fit our UI and features one by one, drop the ones that only touch code the redesign removed, and list each decision for the user.
- After any rebase, refresh the README's commit links (below), then `git push --force-with-lease origin dev` once the user agrees.
- Work in a worktree, never the main checkout (`~/Workspace/other/cleanyourtv`): `git fetch --multiple origin upstream`, then `git worktree add -b <branch> ~/Workspace/other/cleanyourtv-worktrees/<name> origin/dev`.
- Keep upstream files untouched where possible, unused upstream code included, so rebases stay clean. Config.java and config.xml keep every upstream key even where the fork's Home ignores it; fork keys are added after them.

## Upstream candidates

A commit can go upstream as a one-commit PR (skill `upstream-pr`) if upstream's CONTRIBUTING.md accepts it: lists, fixes, small opt-in launcher features with no network, background service or new permission. None qualifies today: the MiTV-MOEU0 debloat list (#651) and the SHOWCASE row (#664) are merged, and every Launcher commit is fork-only (self-adb, usage and notification access, network artwork, GameStream, the restyle). Once upstream merges a PR, drop its commit from `dev` and list it under the README's Contributed upstream line.

## Build and test

```sh
python3 -m unittest discover -s tests
cd launcher && ./gradlew testDebugUnitTest lintDebug assembleRelease
```

- Gradle needs `JAVA_HOME=/opt/homebrew/opt/openjdk@17` and `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- Every commit must build and pass: `git rebase upstream/main -x 'cd launcher && ./gradlew testDebugUnitTest -q'`.
- Upstream's CI size gate does not apply: the fork's APK is expected to exceed upstream's limit.
- A new string goes in `values/strings.xml` and the `values-*` folders the commit already covers; nothing checks this (lint's MissingTranslation is informational).

## The README

README.md is the fork's own and carries `<!-- fork-readme -->`: a title, one line on what the fork is, a heading per feature commit with a short line, a commit link and, where there is one, a screenshot from `docs/fork/`, and a closing licence line. Upstream tests that read upstream's README carry `@upstream_readme` (from `tests/fork_readme.py`), which skips them on the fork's README.

AGENTS.md is the fork's own too (it carries `<!-- fork-agents -->`); CLAUDE.md stays upstream's and imports it. Upstream tests of upstream's AGENTS.md carry `@upstream_agents`, tests of upstream's header and dock `@upstream_home`, and fork theme keys are listed in `FORK_THEME_KEYS` so `tests/test_switches.py` asks no screenshot for them.

Each feature link ends in `<!-- commit: <subject> -->`. After every rebase run `python3 tools/fork_readme_commits.py` and amend the result into the docs commit; it fails if a subject is no longer on `dev`. README.md, AGENTS.md, the skills, `docs/fork/`, that script and the test skips all belong to the docs commit.

## TV workflow

- The TV's address is in `backup/README.md` (gitignored); export it as `TV_HOST`. macOS blocks stock `adb` on the local network, so use `tools/venv/bin/python tools/tv.py '<shell command>'` (skill `tv-deploy`).
- Check the launcher is in the foreground before installing or injecting keys; never interrupt another app or a Moonlight stream.
- Keys injected over adb never reach accessibility services: remote-button behaviour needs a human press.

## Device facts

- While enabled, `com.google.android.apps.tv.launcherx` owns Home on Google TV 14; `set-home-activity` and the HOME role do not override it.
- The shell cannot change components of system apps. Per-show Watch Next needs a privileged permission.
- USB debugging stays on by the user's choice; do not suggest turning it off.

## Privacy and style

- `backup/` is never committed or quoted. No names, accounts, tokens, Steam IDs, addresses or Wi-Fi details in commits, PRs or issues.
- Match the surrounding code; comments are one line and say why. No em dashes anywhere.
- Commit messages: imperative, explain why. No Claude attribution. PR bodies are minimal; the user rewrites them.
- Do not push, force-push or open PRs without telling the user first.
