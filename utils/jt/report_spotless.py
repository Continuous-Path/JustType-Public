#!/usr/bin/env python3
"""
report_spotless.py — compact summary of the last spotlessKotlinCheck run.

Spotless emits violations to gradle stdout, not to a report file. The
./jt spotless subcommand pipes gradle's output through `tee` to
.gradle/jt-last-spotless.log; this parser reads from that capture.

Default output: just the file list — no inline diffs.
    Spotless: 35 files need formatting
      app/src/main/java/org/continuouspath/justtype/Constants.kt (12 lines)
      app/src/main/java/org/continuouspath/justtype/JustTypeIME.kt (24 lines)
      ...

--verbose adds the unified-diff blocks for each file.

Usage:
    ./jt report spotless              # default — file list only
    ./jt report spotless --verbose    # include diff blocks

Exit codes:
    0 — parsed successfully
    1 — no captured log found
    2 — usage error
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class SpotlessFile:
    path: str
    diff_lines: int = 0
    diff_block: list[str] = field(default_factory=list)


# Spotless output shape per file (gradle 8.x + spotless 6.x):
#
#     > The following files had format violations:
#         src/main/java/.../Foo.kt
#             @@ -1,5 +1,5 @@
#             -class Foo {
#             +class Foo {
#             ...
#         src/main/java/.../Bar.kt
#             @@ -1,3 +1,3 @@
#             ...
#     > Run './gradlew :spotlessApply' to fix these violations.
#
# Indentation: 4 spaces for the file path, 8 spaces for the diff content.
_FILE_PATH_RE = re.compile(r"^ {4,8}(\S.*\.(?:kt|kts))$")
_DIFF_LINE_RE = re.compile(r"^ {8,}([-+@ ].*)$")
_END_RE = re.compile(r"Run .* to fix")
# Spotless truncates after the first N files and says "Violations also
# present in M other files." — we count those too.
_OTHER_FILES_RE = re.compile(r"Violations also present in (\d+) other files?\.")


def parse_log(log_path: Path) -> tuple[bool, list[SpotlessFile], int]:
    """Returns (saw_violations_section, list_of_files, additional_unnamed_files).

    `additional_unnamed_files` is spotless's "Violations also present in N
    other files" count — those files are real violations but spotless
    truncates their per-file diff blocks, so we only know their count.
    """
    files: list[SpotlessFile] = []
    additional = 0
    in_section = False
    current: SpotlessFile | None = None

    with log_path.open(encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not in_section:
                if "had format violations" in line:
                    in_section = True
                continue
            m_other = _OTHER_FILES_RE.search(line)
            if m_other:
                additional = int(m_other.group(1))
                # Don't break — continue in case more violations follow.
                continue
            if _END_RE.search(line):
                break
            m_file = _FILE_PATH_RE.match(line)
            if m_file and not line.lstrip().startswith(("+", "-", "@")):
                if current is not None:
                    files.append(current)
                current = SpotlessFile(path=m_file.group(1).strip())
                continue
            m_diff = _DIFF_LINE_RE.match(line)
            if m_diff and current is not None:
                content = m_diff.group(1)
                current.diff_block.append(content)
                if content.startswith(("+", "-")) and not content.startswith(("+++", "---")):
                    current.diff_lines += 1
        if current is not None:
            files.append(current)

    return in_section, files, additional


def print_summary(files: list[SpotlessFile], additional: int,
                  *, verbose: bool) -> int:
    total = len(files) + additional
    if total == 0:
        print("Spotless: 0 files need formatting")
        return 0

    print(f"Spotless: {total} files need formatting")
    print()
    for f in files:
        print(f"  {f.path} ({f.diff_lines} lines)")
        if verbose and f.diff_block:
            for d in f.diff_block:
                print(f"    {d}")
            print()
    if additional > 0:
        print(f"  ... and {additional} more files (spotless truncated their diffs;")
        print("      run `./jt spotless` to see them all, or `./jt spotless-fix`)")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Compact summary of the last spotless run.",
    )
    parser.add_argument(
        "--log",
        default=".gradle/jt-last-spotless.log",
        help="Path to captured spotless gradle log (default: %(default)s).",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Include unified-diff block for each file.",
    )
    args = parser.parse_args(argv)

    log_path = Path(args.log)
    if not log_path.is_file():
        print(f"jt-report: no spotless log at {log_path}", file=sys.stderr)
        print(
            "Did `./jt spotless` run? The wrapper writes its output to "
            "this path; if it doesn't exist the task may not have been "
            "invoked through the wrapper.",
            file=sys.stderr,
        )
        return 1

    saw_section, files, additional = parse_log(log_path)
    if not saw_section and not files and additional == 0:
        print("Spotless: no formatting violations recorded in the last run.")
        return 0
    return print_summary(files, additional, verbose=args.verbose)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
