#!/usr/bin/env python3
"""Summarize the changelog since the last published stable version."""
import os
from pathlib import Path
import re

text = Path('更新日志/CHANGELOG.md').read_text(encoding='utf-8')
previous = os.environ.get('PREVIOUS_VERSION', '')
sections = re.split(r'(?=^## v)', text, flags=re.M)
selected = []
for section in sections[1:]:
    if previous and re.match(r'## ' + re.escape(previous) + r'(?:\s|$)', section):
        break
    selected.append(section)
print('# 本次更新\n\n' + ''.join(selected).strip())
tag = os.environ.get('PREVIOUS_TAG', '')
if tag:
    print(f"\n\n完整对比：https://github.com/{os.environ['GITHUB_REPOSITORY']}/compare/{tag}...{os.environ['SOURCE_SHA']}")
