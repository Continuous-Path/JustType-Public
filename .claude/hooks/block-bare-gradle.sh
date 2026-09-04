#!/usr/bin/env bash
# PreToolUse hook for Bash. Blocks bare gradle invocations so all gradle
# work flows through ./jt (which provides flock + cleanup).
#
# Triggered for every Bash tool call; reads the JSON payload on stdin.
# Exit 0 = allow, exit 2 = deny (Claude-visible reason on stderr).
#
# We're permissive: only block commands that clearly invoke gradle.
# Reading docs about gradle, grep'ing for gradle in files, etc. are fine.

set -euo pipefail

payload="$(cat)"
# Extract `.tool_input.command` without requiring jq.
cmd="$(printf '%s' "$payload" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("command",""))' 2>/dev/null || true)"

if [[ -z "$cmd" ]]; then
	exit 0
fi

# Match bare gradle invocations only when they appear as the first
# command in the bash payload. This deliberately ignores chained gradle
# calls (`cd app && ./gradlew test`) and embedded literals
# (`echo './gradlew foo'`). The chained form is rare and intentional; the
# literal form is the false-positive risk we want to avoid.
#
# Allowed (do NOT match):
#   ./jt test
#   grep gradle README.md
#   echo '{"command":"./gradlew assembleDebug"}'   ← in quoted arg
#   cd app && ./gradlew test                        ← chained, considered intentional
#
# Blocked (do match):
#   ./gradlew assembleDebug
#   gradle build
#   gradlew test
#   ./gradlew.bat assembleDebug
first_word="$(printf '%s\n' "$cmd" | sed -E 's/^[[:space:]]+//' | awk '{print $1}')"
blocked=false
case "$first_word" in
	./gradlew|gradle|gradlew|./gradlew.bat)
		blocked=true
		;;
esac

if $blocked; then
	cat <<'EOF' >&2
Bare gradle invocations are blocked on this project.

Use ./jt (or /jt) instead — it provides:
  - flock so concurrent Claude sessions can't collide
  - automatic orphan worker cleanup
  - convenience subcommands (jt test, jt detekt, jt check, etc.)

Examples:
  ./jt test                     # all debug unit tests
  ./jt test "*FooBarTest*"      # filtered
  ./jt check                    # tests + spotless + detekt
  ./jt help                     # full reference
  ./jt raw -- <gradle args>     # escape hatch
EOF
	exit 2
fi

exit 0
