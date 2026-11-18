#!/usr/bin/env python3
"""Publish the curated release notes with a comparison to the last stable tag."""
import os
from pathlib import Path

print(Path(os.environ['NOTES_FILE']).read_text(encoding='utf-8').strip())
tag = os.environ.get('PREVIOUS_TAG', '')
if tag:
    print(f"\n\n完整对比：https://github.com/{os.environ['GITHUB_REPOSITORY']}/compare/{tag}...{os.environ['SOURCE_SHA']}")
