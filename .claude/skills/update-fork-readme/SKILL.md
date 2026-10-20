---
name: update-fork-readme
description: Use when a feature commit on dev is added, dropped, renamed or sent upstream, or after any rebase, to keep the fork README's features, commit links and screenshots current.
---

# Keep the fork README current

1. List the features: `git log --reverse --format='%h %s' upstream/main..HEAD~1` (HEAD is the docs commit).
2. One `### Name` per commit, in that order: a short line on what the user gets, then
   `[Commit](https://github.com/SferaDev/android-tv/commit/<sha>) <!-- commit: <exact subject> -->`.
   A renamed commit needs its `commit:` subject updated; a commit upstream merged loses its entry.
3. Screenshots: 1920x1080 from the TV (skill `tv-deploy`), then
   `sips -s format jpeg -s formatOptions 85 -Z 1280 <png> --out docs/fork/<name>.jpg`. Keep `docs/fork/` small.
   Never capture another app, playback you started, or the hidden-items list.
4. Refresh the links and check:
   ```sh
   python3 tools/fork_readme_commits.py
   python3 -m unittest discover -s tests
   ```
5. Fold it into the docs commit, which stays last: `git add -A README.md docs/fork && git commit --amend --no-edit` at HEAD,
   or `git commit --fixup=<docs sha>` plus `--autosquash`. Do not push without telling the user.
