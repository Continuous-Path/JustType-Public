#!/usr/bin/env bash
# PreToolUse hook for Bash. Gates `git commit` on a fresh `./jt check` pass.
#
# "Fresh" = .gradle/jt-check-stamp (written by jt check / check-full on success)
# is newer than every STAGED code file. Docs/config-only commits pass freely.
# Escape hatch for genuine emergencies: JT_SKIP_CHECK=1 git commit ...
#
# Exit 0 = allow, exit 2 = deny (Claude-visible reason on stderr).

set -euo pipefail

payload="$(cat)"
cmd="$(printf '%s' "$payload" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("tool_input",{}).get("command",""))' 2>/dev/null || true)"

[[ -z "$cmd" ]] && exit 0

# Only gate commands that actually run `git commit` (any position in a chain).
printf '%s' "$cmd" | grep -Eq '(^|[;&|[:space:]])git[[:space:]]+(-[^[:space:]]+[[:space:]]+)*commit([[:space:]]|$)' || exit 0

# Documented escape hatch.
printf '%s' "$cmd" | grep -q 'JT_SKIP_CHECK=1' && exit 0

repo_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
[[ -z "$repo_root" ]] && exit 0
stamp_file="$repo_root/.gradle/jt-check-stamp"

# Workflow files skip the jt gate (jt check can't validate YAML) but get
# actionlint when it's installed, so CI config can't be committed broken.
workflows_staged="$(git -C "$repo_root" diff --cached --name-only 2>/dev/null |
	grep -E '^\.github/workflows/.*\.ya?ml$' || true)"
if [[ -n "$workflows_staged" ]] && command -v actionlint >/dev/null 2>&1; then
	lint_out="$(cd "$repo_root" && actionlint $workflows_staged 2>&1)" || {
		printf 'Commit blocked: actionlint found problems in staged workflow files.\n\n%s\n' "$lint_out" >&2
		exit 2
	}
fi

# Staged code files (anything outside docs/markdown/agent-config counts as code).
code_staged="$(git -C "$repo_root" diff --cached --name-only 2>/dev/null |
	grep -Ev '^docs/|\.md$|^\.claude/|^\.github/' || true)"
[[ -z "$code_staged" ]] && exit 0

stamp=0
[[ -f "$stamp_file" ]] && stamp="$(cat "$stamp_file" 2>/dev/null || echo 0)"

stale=""
if [[ "$stamp" == "0" ]]; then
	stale="(no ./jt check has completed yet)"
else
	while IFS= read -r f; do
		[[ -f "$repo_root/$f" ]] || continue
		mtime="$(stat -f %m "$repo_root/$f" 2>/dev/null || stat -c %Y "$repo_root/$f" 2>/dev/null || echo 0)"
		if (( mtime > stamp )); then
			stale="($f changed after the last ./jt check)"
			break
		fi
	done <<< "$code_staged"
fi

[[ -z "$stale" ]] && exit 0

cat >&2 <<EOF
Commit blocked: staged code changes have not passed ./jt check $stale.

Run the pre-commit gate first:
  ./jt check        # tests + spotless + detekt (~1 min warm)

Then retry the commit. If spotless flags formatting, ./jt spotless-fix applies it.
Docs-only commits (docs/, *.md, .claude/, .github/) are not gated.
EOF
exit 2
