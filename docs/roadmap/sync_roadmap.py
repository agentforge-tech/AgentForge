#!/usr/bin/env python3
"""Render the Roadmap markdown for GitHub/Gitee issues and optionally sync them.

The local roadmap uses relative links. Issues need absolute links, so this
script resolves them against each platform's repository URL and URL-encodes the
target path.
"""

import argparse
import pathlib
import posixpath
import re
import subprocess
import urllib.parse

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = REPO_ROOT / "docs/roadmap/2026年AgentForge的RoadMap.md"
SOURCE_DIR = "docs/roadmap"

PLATFORMS = {
    "github": {
        "base": "https://github.com/agentforge-tech/AgentForge",
        "command": ["gh", "issue", "edit", "1", "--repo", "agentforge-tech/AgentForge"],
    },
    "gitee": {
        "base": "https://gitee.com/changluJava/AgentForge",
        "command": ["gitee", "issue", "edit", "IKS14I", "-R", "changluJava/agent-forge"],
    },
}


def render(platform: str) -> str:
    base = PLATFORMS[platform]["base"]
    text = SOURCE.read_text(encoding="utf-8")

    def repl(match: re.Match) -> str:
        target = match.group(1)
        repo_path = posixpath.normpath(posixpath.join(SOURCE_DIR, target))
        kind = "blob" if repo_path.endswith(".md") else "tree"
        encoded = urllib.parse.quote(repo_path, safe="/%")
        return f"]({base}/{kind}/master/{encoded})"

    return re.sub(r"\]\((\.\./[^)]*)\)", repl, text)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["render", "sync"])
    parser.add_argument("platform", nargs="?", choices=PLATFORMS)
    args = parser.parse_args()

    if args.action == "render":
        if not args.platform:
            parser.error("render requires a platform: github or gitee")
        print(render(args.platform), end="")
        return

    platforms = [args.platform] if args.platform else list(PLATFORMS)
    for platform in platforms:
        command = PLATFORMS[platform]["command"] + ["-b", render(platform)]
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
