#!/usr/bin/env python3
"""
report_detekt.py — compact summary of the last :app:detekt run.

Parses app/build/reports/detekt/detekt.xml (Checkstyle format) and prints
a per-file rollup of issues. Each issue: file:line, rule id, message.

Usage:
    ./jt report detekt              # default summary
    ./jt report detekt --all        # don't cap at DEFAULT_ISSUE_LIMIT
    ./jt report detekt --verbose    # also show source attribute

Exit codes:
    0 — parsed successfully (regardless of issue count)
    1 — no report file found
    2 — usage error
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

DEFAULT_ISSUE_LIMIT = 30


@dataclass
class DetektIssue:
    file: str
    line: int
    column: int
    severity: str
    message: str
    rule: str  # 'source' attribute, e.g. "detekt.LongMethod"


def _short_path(full_path: str, project_root: Path) -> str:
    try:
        return str(Path(full_path).resolve().relative_to(project_root))
    except (ValueError, OSError):
        # Already relative, or outside project root.
        return full_path


def _short_rule(source: str) -> str:
    # "detekt.LongMethod" → "LongMethod"
    return source.rsplit(".", 1)[-1] if source else "?"


def parse_report(report_path: Path, project_root: Path) -> list[DetektIssue]:
    issues: list[DetektIssue] = []
    try:
        root = ET.parse(report_path).getroot()
    except ET.ParseError as e:
        print(f"jt-report: malformed detekt XML at {report_path}: {e}",
              file=sys.stderr)
        return issues

    for file_el in root.findall("file"):
        file_name = file_el.get("name", "")
        short = _short_path(file_name, project_root)
        for err in file_el.findall("error"):
            issues.append(DetektIssue(
                file=short,
                line=int(err.get("line", 0)),
                column=int(err.get("column", 0)),
                severity=err.get("severity", ""),
                message=err.get("message", "").strip(),
                rule=_short_rule(err.get("source", "")),
            ))
    return issues


def print_summary(issues: list[DetektIssue], *, limit: int,
                  verbose: bool) -> int:
    if not issues:
        print("Detekt: 0 issues")
        return 0

    # Group by file for readability.
    by_file: dict[str, list[DetektIssue]] = defaultdict(list)
    for i in issues:
        by_file[i.file].append(i)

    print(f"Detekt: {len(issues)} issues across {len(by_file)} files")
    print()

    shown_total = 0
    shown_files = 0
    for file, file_issues in by_file.items():
        if not verbose and shown_total >= limit:
            break
        print(file)
        for i in file_issues:
            if not verbose and shown_total >= limit:
                print(f"  ... and {len(file_issues) - (shown_total - sum(len(v) for k, v in list(by_file.items())[:shown_files]))} more in this file")
                break
            print(f"  {i.line}: {i.rule} — {i.message}")
            shown_total += 1
        shown_files += 1

    remaining_files = len(by_file) - shown_files
    if remaining_files > 0:
        remaining_issues = sum(len(v) for k, v in list(by_file.items())[shown_files:])
        print()
        print(f"... and {remaining_issues} more issues in {remaining_files} files (pass --all to see all)")

    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Compact summary of the last detekt run.",
    )
    parser.add_argument(
        "--report",
        default="app/build/reports/detekt/detekt.xml",
        help="Path to detekt XML report (default: %(default)s).",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help=f"Show every issue (default: cap at {DEFAULT_ISSUE_LIMIT}).",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Show all issues even past the cap.",
    )
    args = parser.parse_args(argv)

    report_path = Path(args.report)
    if not report_path.is_file():
        print(f"jt-report: no detekt report at {report_path}", file=sys.stderr)
        print(
            "Did the detekt task run? Try `./jt detekt` first.",
            file=sys.stderr,
        )
        return 1

    project_root = Path.cwd()
    issues = parse_report(report_path, project_root)
    limit = 10**9 if args.all or args.verbose else DEFAULT_ISSUE_LIMIT
    return print_summary(issues, limit=limit, verbose=args.verbose)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
