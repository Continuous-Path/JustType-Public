#!/usr/bin/env python3
"""
report_test.py — compact summary of the last :app:testDebugUnitTest run.

Parses every JUnit XML under app/build/test-results/testDebugUnitTest/ and
prints a terse, AI-friendly summary:

    Test results: 804 total, 7 failed, 1 skipped, 796 passed (12.4s)

    FAILED:
      ClassName > test method name
        TestFile.kt:142
        expected: X but was: Y
      ... (truncated to N by default; pass --all to see everything)

Design goals:
  - Zero non-stdlib deps. Runs on macOS python3.9+.
  - Output is greppable, short, and structured for an AI agent to read.
  - Failures show: fully-qualified test, file:line, one-line error message.
    Full stack traces are NOT shown by default — pass --verbose for that.
  - If no test results exist (no run yet, or `clean` was run after), say so.

Exit codes:
  0 — parsed successfully (regardless of pass/fail count)
  1 — no test results found
  2 — usage error
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

# Default cap on number of failures to print fully. The rest are listed by
# name only. --all overrides.
DEFAULT_FAILURE_LIMIT = 20


@dataclass
class TestFailure:
    classname: str
    method: str
    failure_type: str  # 'failure' or 'error'
    message: str       # one-line summary
    file_line: str     # 'File.kt:142' if extractable from stack, else ''
    full_text: str     # original CDATA contents

    @property
    def display(self) -> str:
        # Short class name (drop package) for the first line.
        short = self.classname.rsplit(".", 1)[-1]
        return f"{short} > {self.method}"


@dataclass
class SuiteTiming:
    name: str
    tests: int
    time_seconds: float
    mtime: float


@dataclass
class Totals:
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    time_seconds: float = 0.0
    failure_list: list[TestFailure] = field(default_factory=list)
    suites: list[SuiteTiming] = field(default_factory=list)


# Match common stack-frame patterns to extract a source file:line tag.
# We prefer frames in the project's own packages, but fall back to any
# frame ending in (.kt:N) or (.java:N).
_FILE_LINE_RE = re.compile(r"\(([^():\s]+\.(?:kt|java)):(\d+)\)")
_PROJECT_PKG = "org.continuouspath.justtype"


def _extract_file_line(stack_text: str) -> str:
    """Pull the most-relevant 'File.kt:line' from a stack trace."""
    if not stack_text:
        return ""
    matches = list(_FILE_LINE_RE.finditer(stack_text))
    if not matches:
        return ""
    # Prefer the topmost project frame.
    project_lines: list[str] = []
    for m in matches:
        # Look back for the class qualifier preceding the (file:line) tuple.
        idx = m.start()
        # Crude but effective: check if the project pkg appears in the
        # ~120 chars preceding the file marker.
        window = stack_text[max(0, idx - 120):idx]
        if _PROJECT_PKG in window:
            project_lines.append(f"{m.group(1)}:{m.group(2)}")
    if project_lines:
        return project_lines[0]
    # Fall back to the first non-test-framework frame.
    skip_prefixes = (
        "junit.", "org.junit.", "org.mockito.", "sun.", "java.",
        "jdk.", "kotlin.", "kotlinx.", "org.robolectric.",
    )
    for m in matches:
        window = stack_text[max(0, m.start() - 200):m.start()]
        if not any(p in window for p in skip_prefixes):
            return f"{m.group(1)}:{m.group(2)}"
    # Worst case: first match.
    return f"{matches[0].group(1)}:{matches[0].group(2)}"


def _one_line(text: str, max_len: int = 200) -> str:
    """Collapse whitespace to a single line, truncate to max_len."""
    if not text:
        return ""
    collapsed = " ".join(text.split())
    if len(collapsed) > max_len:
        return collapsed[:max_len - 1] + "…"
    return collapsed


def parse_results_dir(results_dir: Path) -> Totals:
    totals = Totals()
    xml_files = sorted(results_dir.glob("TEST-*.xml"))
    for xml in xml_files:
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError as e:
            print(f"warn: skipping malformed XML {xml}: {e}", file=sys.stderr)
            continue

        # <testsuite> attributes carry the aggregate counts.
        totals.tests += int(root.get("tests", 0))
        totals.failures += int(root.get("failures", 0))
        totals.errors += int(root.get("errors", 0))
        totals.skipped += int(root.get("skipped", 0))
        suite_time = 0.0
        try:
            suite_time = float(root.get("time", 0))
        except ValueError:
            pass
        totals.time_seconds += suite_time
        totals.suites.append(SuiteTiming(
            name=root.get("name", xml.stem),
            tests=int(root.get("tests", 0)),
            time_seconds=suite_time,
            mtime=xml.stat().st_mtime,
        ))

        for tc in root.findall("testcase"):
            classname = tc.get("classname", "")
            method = tc.get("name", "")
            for kind in ("failure", "error"):
                el = tc.find(kind)
                if el is None:
                    continue
                stack_text = (el.text or "").strip()
                msg = el.get("message", "") or ""
                totals.failure_list.append(TestFailure(
                    classname=classname,
                    method=method,
                    failure_type=kind,
                    message=_one_line(msg) or _one_line(stack_text.split("\n", 1)[0]),
                    file_line=_extract_file_line(stack_text),
                    full_text=stack_text,
                ))
    return totals


# Slack for XML-mtime vs invocation-start comparison (clock granularity,
# result files written just as the marker lands).
_FRESHNESS_SLACK_SECONDS = 2.0
# A full run producing fewer classes than this fraction of the last known
# full run is reported as suspicious (wedge killed the run mid-suite, or
# results were clobbered by a filtered run).
_SHRINK_THRESHOLD = 0.9


def check_freshness(totals: Totals, marker_file: Path, state_file: Path) -> None:
    """Warn when the XML set is stale or smaller than the last full run.

    marker_file is written by ./jt before each test invocation: '<epoch> full|filtered'.
    state_file records the class count of the last complete full run.
    """
    marker_epoch, scope, status = None, None, "started"
    try:
        raw = marker_file.read_text().split()
        marker_epoch, scope = float(raw[0]), raw[1]
        if len(raw) > 2:
            status = raw[2]
    except (OSError, IndexError, ValueError):
        return  # no marker (raw gradle run, or pre-marker jt) — nothing to check

    # 'completed' = gradle exited cleanly, so on-disk XMLs are trustworthy even
    # when the task was UP-TO-DATE and wrote nothing new. Anything else means
    # the invocation died or a composite task (spotless/detekt/lint) failed —
    # never update the class-count baseline from such a run.
    if status != "completed":
        cutoff = marker_epoch - _FRESHNESS_SLACK_SECONDS
        stale = [s for s in totals.suites if s.mtime < cutoff]
        if len(stale) == len(totals.suites):
            print(
                "jt-report: WARNING — the last invocation did not complete "
                "cleanly and every result file predates it. Either the run "
                "died before any test wrote results (summary below is from "
                "an OLDER run), or the test task was UP-TO-DATE and a "
                "non-test task in a composite run failed.",
                file=sys.stderr,
            )
            return
        if stale:
            print(
                f"jt-report: WARNING — the last invocation did not complete "
                f"cleanly; {len(stale)} of {len(totals.suites)} result files "
                "predate it (killed mid-suite?). Totals below MIX runs.",
                file=sys.stderr,
            )
            return
        print(
            "jt-report: note — the last invocation did not complete cleanly "
            "(test failure, composite-task failure, or a killed run). Results "
            "are fresh but may be partial; class-count baseline not updated.",
            file=sys.stderr,
        )
        return

    if scope == "filtered":
        print(
            f"jt-report: note — filtered run ({len(totals.suites)} class"
            f"{'es' if len(totals.suites) != 1 else ''}); totals are not the "
            "full suite.",
            file=sys.stderr,
        )
        return

    # Complete, unfiltered run: its class count is authoritative. Warn once on
    # a big drop (deliberate deletions are expected; anything else deserves a
    # look), then adopt the new count so the warning cannot get stuck.
    previous = None
    try:
        previous = int(state_file.read_text().strip())
    except (OSError, ValueError):
        pass
    current = len(totals.suites)
    if previous is not None and current < previous * _SHRINK_THRESHOLD:
        print(
            f"jt-report: note — full run recorded {current} test classes vs "
            f"{previous} in the last complete run. Expected after deliberate "
            "test deletions; otherwise investigate. Baseline updated.",
            file=sys.stderr,
        )
    try:
        state_file.write_text(f"{current}\n")
    except OSError:
        pass


def print_slowest(totals: Totals, count: int) -> None:
    ranked = sorted(totals.suites, key=lambda s: s.time_seconds, reverse=True)
    shown = ranked[:count]
    total = totals.time_seconds or 1.0
    share = sum(s.time_seconds for s in shown) / total * 100
    print(f"\nSlowest classes (top {len(shown)} = {share:.0f}% of {_format_duration(total)}):")
    for s in shown:
        short = s.name.rsplit(".", 1)[-1]
        print(f"  {s.time_seconds:7.2f}s  {s.tests:4d} tests  {short}")


def _format_duration(seconds: float) -> str:
    if seconds < 60:
        return f"{seconds:.1f}s"
    mins = int(seconds // 60)
    secs = seconds - mins * 60
    return f"{mins}m{secs:.0f}s"


def print_summary(totals: Totals, *, limit: int, verbose: bool) -> int:
    passed = totals.tests - totals.failures - totals.errors - totals.skipped
    duration = _format_duration(totals.time_seconds)
    fail_count = totals.failures + totals.errors

    print(
        f"Test results: {totals.tests} total, "
        f"{fail_count} failed, {totals.skipped} skipped, {passed} passed "
        f"({duration})"
    )

    if fail_count == 0:
        return 0

    print()
    print("FAILED:")
    shown = totals.failure_list[:limit] if not verbose else totals.failure_list
    for f in shown:
        print(f"  {f.display}")
        if f.file_line:
            print(f"    {f.file_line}")
        if f.message:
            print(f"    {f.message}")
        if verbose and f.full_text:
            for line in f.full_text.splitlines():
                print(f"      {line}")

    remaining = len(totals.failure_list) - len(shown)
    if remaining > 0:
        print(f"  ... and {remaining} more (pass --all to see all)")

    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Compact summary of the last test run.",
    )
    parser.add_argument(
        "--results-dir",
        default="app/build/test-results/testDebugUnitTest",
        help="Path to JUnit XML directory (default: %(default)s).",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="Show every failure (default: cap at "
             f"{DEFAULT_FAILURE_LIMIT}).",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Include full stack traces for shown failures.",
    )
    parser.add_argument(
        "--slowest",
        nargs="?",
        const=15,
        type=int,
        metavar="N",
        help="Also print the top-N slowest test classes (default N=15).",
    )
    parser.add_argument(
        "--marker-file",
        default=".gradle/jt-test-invocation",
        help="Invocation marker written by ./jt (default: %(default)s).",
    )
    args = parser.parse_args(argv)

    results_dir = Path(args.results_dir)
    if not results_dir.is_dir():
        print(f"jt-report: no test results at {results_dir}", file=sys.stderr)
        print(
            "Did the test task run? Try `./jt test` first, or check the path.",
            file=sys.stderr,
        )
        return 1

    totals = parse_results_dir(results_dir)
    if totals.tests == 0:
        # Most likely cause: gradle failed before any test executed
        # (config error, no matching --tests pattern, dependency
        # resolution failure). The real failure is in gradle's stdout
        # above, not here.
        print(
            "jt-report: no testcases recorded — gradle failed before any "
            "test ran (see gradle output above for the real error).",
            file=sys.stderr,
        )
        return 1

    check_freshness(
        totals,
        marker_file=Path(args.marker_file),
        state_file=Path(args.marker_file).with_name("jt-test-full-class-count"),
    )

    limit = 10**9 if args.all else DEFAULT_FAILURE_LIMIT
    rc = print_summary(totals, limit=limit, verbose=args.verbose)
    if args.slowest:
        print_slowest(totals, args.slowest)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
