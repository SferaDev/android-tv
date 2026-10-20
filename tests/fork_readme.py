"""The fork replaces upstream's README.md, AGENTS.md and Home screen; tests of upstream's versions skip on the fork's."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MARKER = "<!-- fork-readme -->"
AGENTS_MARKER = "<!-- fork-agents -->"
README = ROOT / "README.md"
AGENTS = ROOT / "AGENTS.md"
# A file only the fork's Google TV style Home has.
FORK_HOME = ROOT / "launcher/app/src/main/java/com/gathin/tvhome/HomeNav.java"

upstream_readme = unittest.skipIf(MARKER in README.read_text(encoding="utf-8"), "README.md is the fork's own")
upstream_agents = unittest.skipIf(AGENTS_MARKER in AGENTS.read_text(encoding="utf-8"), "AGENTS.md is the fork's own")
upstream_home = unittest.skipIf(FORK_HOME.is_file(), "the fork's Home replaces upstream's header and dock")

# Fork-only theme keys: documented in themes/README.md, with no screenshot in upstream's docs/switches.md.
FORK_THEME_KEYS = {"home_rows", "online_artwork", "gamestream_host", "moonlight_host_uuid", "moonlight_host_name"}
