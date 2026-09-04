#!/usr/bin/env python3
"""
report_lint.py — compact summary of the last :app:lint run.

Parses app/build/reports/lint-results-debug.xml (AGP lint XML format).
Filters to Error + Warning severities by default — Information is noise.
Groups by severity; per-issue: file:line, issue id, message.

Usage:
    ./jt report lint                # default (Error + Warning)
    ./jt report lint --all          # include Information severity
    ./jt report lint --errors-only  # only Error severity

Exit codes:
    0 — parsed successfully
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
class LintIssue:
    id: str
    severity: str  # 'Error' | 'Warning' | 'Information'
    message: str
    file: str
    line: int


def _short_path(full_path: str, project_root: Path) -> str:
    try:
        return str(Path(full_path).resolve().relative_to(project_root))
    except (ValueError, OSError):
        return full_path


def parse_report(report_path: Path, project_root: Path) -> list[LintIssue]:
    issues: list[LintIssue] = []
    try:
        root = ET.parse(report_path).getroot()
    except ET.ParseError as e:
        print(f"jt-report: malformed lint XML at {report_path}: {e}",
              file=sys.stderr)
        return issues

    for issue_el in root.findall("issue"):
        issue_id = issue_el.get("id", "")
        severity = issue_el.get("severity", "")
        message = issue_el.get("message", "").strip()

        # An issue can have multiple <location> children. We only show the
        # first — additional ones are usually duplicates or related sites.
        loc = issue_el.find("location")
        file_name = loc.get("file", "") if loc is not None else ""
        try:
            line = int(loc.get("line", 0)) if loc is not None else 0
        except (ValueError, TypeError):
            line = 0

        issues.append(LintIssue(
            id=issue_id,
            severity=severity,
            message=message,
            file=_short_path(file_name, project_root) if file_name else "",
            line=line,
        ))
    return issues


def print_summary(issues: list[LintIssue], *, limit: int,
                  errors_only: bool, include_info: bool) -> int:
    # Severity filter.
    if errors_only:
        issues = [i for i in issues if i.severity == "Error"]
    elif not include_info:
        issues = [i for i in issues if i.severity in ("Error", "Warning")]

    counts: dict[str, int] = defaultdict(int)
    for i in issues:
        counts[i.severity] += 1

    if not issues:
        print("Lint: 0 issues" + (" (errors only)" if errors_only else ""))
        return 0

    summary = ", ".join(f"{counts[k]} {k.lower()}" for k in
                        ("Error", "Warning", "Information") if counts.get(k))
    print(f"Lint: {summary}")
    print()

    # Print Error first, then Warning, then Information.
    severity_order = {"Error": 0, "Warning": 1, "Information": 2}
    issues.sort(key=lambda i: (severity_order.get(i.severity, 9), i.id, i.file))

    shown = 0
    last_severity = ""
    for i in issues:
        if shown >= limit:
            break
        if i.severity != last_severity:
            print(f"{i.severity.upper()}:")
            last_severity = i.severity
        loc = f"{i.file}:{i.line}" if i.file else "(no location)"
        print(f"  {loc} — {i.id}: {i.message}")
        shown += 1

    remaining = len(issues) - shown
    if remaining > 0:
        print()
        print(f"... and {remaining} more (pass --all to see all)")

    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Compact summary of the last Android lint run.",
    )
    parser.add_argument(
        "--report",
        default="app/build/reports/lint-results-debug.xml",
        help="Path to lint XML report (default: %(default)s).",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="Include Information severity AND lift issue cap.",
    )
    parser.add_argument(
        "--errors-only",
        action="store_true",
        help="Only show Error severity.",
    )
    args = parser.parse_args(argv)

    report_path = Path(args.report)
    if not report_path.is_file():
        # Try alternate names (different AGP versions / variants).
        alts = [
            report_path.with_name("lint-results.xml"),
            Path("app/build/reports/lint-results.xml"),
        ]
        found = next((p for p in alts if p.is_file()), None)
        if found is not None:
            report_path = found
        else:
            print(f"jt-report: no lint report at {report_path}", file=sys.stderr)
            print(
                "Did `./jt lint` run? If it did, lint may not be emitting "
                "XML. Add `xmlReport = true` to the `lint {}` block in "
                "app/build.gradle.",
                file=sys.stderr,
            )
            return 1

    project_root = Path.cwd()
    issues = parse_report(report_path, project_root)
    limit = 10**9 if args.all else DEFAULT_ISSUE_LIMIT
    return print_summary(
        issues,
        limit=limit,
        errors_only=args.errors_only,
        include_info=args.all,
    )


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
