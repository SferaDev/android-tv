---
name: upstream-pr
description: Use when sending one fork commit to Gathin23/cleanyourtv as a pull request, such as a debloat list, a fix or a small opt-in launcher feature.
---

# Send one commit upstream

One commit, one PR. Confirm it fits upstream's CONTRIBUTING.md first.

1. A `pr/<topic>` worktree off upstream, never off `dev`:
   ```sh
   git fetch --multiple origin upstream
   git worktree add -b pr/<topic> ~/Workspace/other/cleanyourtv-worktrees/pr-<topic> upstream/main
   cd ~/Workspace/other/cleanyourtv-worktrees/pr-<topic> && git cherry-pick <sha>
   ```
2. Check `.github/pull_request_template.md`. Lists: model, Android version and firmware in the header, packages lived with for days (ask the user), nothing uninstalled, rows in `examples/README.md` and upstream's README table. Launcher: real-TV screenshot, D-pad from every edge, no network, background service or new permission, strings in every `values-*`. Nothing fork-only. If a check fails, stop and tell the user.
3. Test: `python3 -m unittest discover -s tests` and `cd launcher && ./gradlew testDebugUnitTest lintDebug`.
4. Tell the user, then push and open it with a minimal body:
   ```sh
   git push -u origin pr/<topic>
   gh pr create -R Gathin23/cleanyourtv --head SferaDev:pr/<topic> --base main \
     --title "<imperative title>" --body $'<what and why>\n\n**Tested on:** Xiaomi MiTV-MOEU0, Google TV 14'
   ```
5. After it merges: skill `sync-upstream`, then `git worktree remove`.
