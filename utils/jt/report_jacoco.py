#!/usr/bin/env python3
"""
report_jacoco.py — compact summary of the last :app:jacocoTestReport run.

Parses app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml and
prints per-package line + branch coverage, sorted low → high so the
under-tested packages show first (where attention is needed).

Usage:
    ./jt report jacoco              # per-package rollup
    ./jt report jacoco --all        # also show every class
    ./jt report jacoco --package <prefix>   # filter to package(s)

Exit codes:
    0 — parsed successfully
    1 — no report file found
    2 — usage error
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Coverage:
    line_covered: int = 0
    line_missed: int = 0
    branch_covered: int = 0
    branch_missed: int = 0

    @property
    def line_pct(self) -> float:
        total = self.line_covered + self.line_missed
        return 100.0 * self.line_covered / total if total else 0.0

    @property
    def branch_pct(self) -> float:
        total = self.branch_covered + self.branch_missed
        return 100.0 * self.branch_covered / total if total else 0.0

    @property
    def lines_total(self) -> int:
        return self.line_covered + self.line_missed

    def absorb(self, counter: ET.Element) -> None:
        ctype = counter.get("type", "")
        covered = int(counter.get("covered", 0))
        missed = int(counter.get("missed", 0))
        if ctype == "LINE":
            self.line_covered += covered
            self.line_missed += missed
        elif ctype == "BRANCH":
            self.branch_covered += covered
            self.branch_missed += missed


def parse_report(report_path: Path) -> tuple[Coverage, dict[str, Coverage], dict[str, dict[str, Coverage]]]:
    """Return (overall, per_package, per_class_by_package)."""
    overall = Coverage()
    per_package: dict[str, Coverage] = {}
    per_class: dict[str, dict[str, Coverage]] = {}

    # ElementTree's default XML resolver chokes on JaCoCo's DOCTYPE without
    # disabling external entity resolution. We use the bundled "no_subset"
    # parser to skip the DTD entirely.
    try:
        parser = ET.XMLParser()
        # Best-effort DOCTYPE skip: read text and strip the doctype line.
        text = report_path.read_text(encoding="utf-8")
        # Strip <!DOCTYPE ...> declaration; jacoco's references the report DTD
        # which isn't fetched offline.
        if "<!DOCTYPE" in text:
            start = text.index("<!DOCTYPE")
            end = text.index(">", start) + 1
            text = text[:start] + text[end:]
        root = ET.fromstring(text)
    except ET.ParseError as e:
        print(f"jt-report: malformed jacoco XML at {report_path}: {e}",
              file=sys.stderr)
        return overall, per_package, per_class

    for counter in root.findall("counter"):
        overall.absorb(counter)

    for pkg in root.findall("package"):
        pkg_name = pkg.get("name", "").replace("/", ".") or "(default)"
        pkg_cov = per_package.setdefault(pkg_name, Coverage())
        for counter in pkg.findall("counter"):
            pkg_cov.absorb(counter)

        classes = per_class.setdefault(pkg_name, {})
        for cls in pkg.findall("class"):
            cls_name = cls.get("name", "").rsplit("/", 1)[-1]
            cls_cov = classes.setdefault(cls_name, Coverage())
            for counter in cls.findall("counter"):
                cls_cov.absorb(counter)

    return overall, per_package, per_class


def _fmt_pct(p: float) -> str:
    return f"{p:5.1f}%"


def print_summary(
    overall: Coverage,
    per_package: dict[str, Coverage],
    per_class: dict[str, dict[str, Coverage]],
    *,
    include_classes: bool,
    package_filter: str | None,
) -> int:
    print(
        f"Coverage: {_fmt_pct(overall.line_pct).strip()} line "
        f"({overall.line_covered}/{overall.lines_total}), "
        f"{_fmt_pct(overall.branch_pct).strip()} branch"
    )

    if not per_package:
        return 0

    # Filter + sort packages by line coverage ascending.
    items = list(per_package.items())
    if package_filter:
        items = [(n, c) for n, c in items if n.startswith(package_filter)]
    items.sort(key=lambda kv: (kv[1].line_pct, -kv[1].lines_total))

    print()
    print("By package (low → high line coverage):")
    print(f"  {'package':<55}  {'line':>7}   {'branch':>7}   {'lines':>10}")
    for name, cov in items:
        print(
            f"  {name:<55}  {_fmt_pct(cov.line_pct)}   {_fmt_pct(cov.branch_pct)}   "
            f"{cov.line_covered:>4}/{cov.lines_total:<5}"
        )
        if include_classes:
            classes = per_class.get(name, {})
            class_items = sorted(classes.items(),
                                 key=lambda kv: (kv[1].line_pct, -kv[1].lines_total))
            for cls_name, cls_cov in class_items:
                print(
                    f"    {cls_name:<53}  {_fmt_pct(cls_cov.line_pct)}   "
                    f"{_fmt_pct(cls_cov.branch_pct)}   "
                    f"{cls_cov.line_covered:>4}/{cls_cov.lines_total:<5}"
                )

    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Compact summary of the last JaCoCo coverage report.",
    )
    parser.add_argument(
        "--report",
        default="app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml",
        help="Path to JaCoCo XML report (default: %(default)s).",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="Also list every class under each package.",
    )
    parser.add_argument(
        "--package",
        default=None,
        help="Filter to packages starting with this prefix.",
    )
    args = parser.parse_args(argv)

    report_path = Path(args.report)
    if not report_path.is_file():
        print(f"jt-report: no jacoco report at {report_path}", file=sys.stderr)
        print(
            "Did `./jt jacoco` run? Coverage XML is only written by that task.",
            file=sys.stderr,
        )
        return 1

    overall, per_package, per_class = parse_report(report_path)
    return print_summary(
        overall, per_package, per_class,
        include_classes=args.all,
        package_filter=args.package,
    )


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
