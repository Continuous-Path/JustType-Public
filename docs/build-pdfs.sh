#!/usr/bin/env bash
# Regenerate the shareable PDF versions of the JustType docs.
#
# Output (in this directory):
#   - UserGuide.pdf
#   - SettingsReference.pdf
#   - BetaTesterQuickStart.pdf
#
# Toolchain:
#   - pandoc  (markdown → standalone HTML with embedded CSS)
#   - sed     (rewrite cross-document links from .md to .pdf in the HTML)
#   - Google Chrome (headless: HTML → PDF, preserving anchor links and
#                    inter-document file:// links)
#
# Requirements:
#   - pandoc 3.x        (brew install pandoc)
#   - Google Chrome.app (already on the user's system)
#
# Internal section links (TOC → headings) work in every PDF viewer.
# Cross-document links (e.g. UserGuide.pdf → SettingsReference.pdf#section)
# work reliably in desktop viewers (Adobe Acrobat, Mac Preview). On mobile
# / web viewers they degrade gracefully to plain text.

set -euo pipefail

cd "$(dirname "$0")"

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DOCS=("UserGuide" "SettingsReference" "BetaTesterQuickStart")

if ! command -v pandoc >/dev/null 2>&1; then
    echo "error: pandoc not found. Install it with:  brew install pandoc" >&2
    exit 1
fi
if [[ ! -x "$CHROME" ]]; then
    echo "error: Google Chrome not found at: $CHROME" >&2
    echo "       (Install from https://www.google.com/chrome/)" >&2
    exit 1
fi

TMPDIR=$(mktemp -d -t justtype-docs)
trap 'rm -rf "$TMPDIR"' EXIT

# Inline CSS for the generated HTML. Aims for readable on-screen reading
# and clean print layout. The .pdf-rewrite below remaps any .md links to
# .pdf so cross-document clicks resolve to the sibling PDFs.
read -r -d '' CSS <<'CSS_EOF' || true
body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Helvetica Neue", sans-serif;
  max-width: 760px;
  margin: 2em auto;
  padding: 0 2em;
  line-height: 1.55;
  color: #1a1a1a;
}
h1 { font-size: 2em; border-bottom: 2px solid #333; padding-bottom: .3em; }
h2 { font-size: 1.5em; margin-top: 1.8em; border-bottom: 1px solid #ccc; padding-bottom: .2em; }
h3 { font-size: 1.2em; margin-top: 1.4em; }
h4 { font-size: 1.05em; margin-top: 1.2em; }
a { color: #1a5fb4; text-decoration: none; }
a:hover { text-decoration: underline; }
blockquote {
  border-left: 4px solid #c0c0c0;
  margin: 1em 0;
  padding: .5em 1em;
  background: #f7f7f7;
  color: #444;
}
code {
  font-family: "SF Mono", Menlo, Consolas, monospace;
  font-size: 0.92em;
  background: #f1f1f1;
  padding: .1em .35em;
  border-radius: 3px;
}
pre {
  background: #f7f7f7;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 1em;
  overflow-x: auto;
  font-size: 0.9em;
}
table {
  border-collapse: collapse;
  margin: 1em 0;
  width: 100%;
}
th, td {
  border: 1px solid #ccc;
  padding: .4em .7em;
  text-align: left;
  vertical-align: top;
}
th { background: #f0f0f0; }
hr { border: 0; border-top: 1px solid #ddd; margin: 2em 0; }
@media print {
  body { max-width: none; margin: 0; padding: 0; }
  a { color: #1a5fb4; }
  pre, blockquote, table { page-break-inside: avoid; }
  h2, h3, h4 { page-break-after: avoid; }
}
CSS_EOF

CSS_FILE="$TMPDIR/style.css"
printf '%s' "$CSS" > "$CSS_FILE"

for stem in "${DOCS[@]}"; do
    src="${stem}.md"
    raw_html="$TMPDIR/${stem}.raw.html"
    final_html="$TMPDIR/${stem}.html"
    out_pdf="${stem}.pdf"

    if [[ ! -f "$src" ]]; then
        echo "warning: missing source $src — skipping" >&2
        continue
    fi

    echo "  pandoc → $stem.html"
    pandoc "$src" \
        --from markdown+pipe_tables+task_lists \
        --to html5 \
        --standalone \
        --metadata title="JustType — $stem" \
        --css "style.css" \
        -o "$raw_html"

    # Rewrite cross-document links: turn .md hrefs into .pdf hrefs so a
    # click in one PDF lands on the sibling PDF. Preserves the #anchor
    # fragment so deep links to specific sections still work.
    echo "  sed   → rewrite .md → .pdf cross-doc links"
    sed -E 's/(href="[^"]*)\.md(#[^"]*)?(")/\1.pdf\2\3/g' "$raw_html" > "$final_html"

    echo "  chrome → $out_pdf"
    "$CHROME" --headless --disable-gpu \
        --no-pdf-header-footer \
        --print-to-pdf="$(pwd)/$out_pdf" \
        --print-to-pdf-no-header \
        "file://$final_html" >/dev/null 2>&1

    echo "  done   ✓ $out_pdf"
done

echo
echo "Generated PDFs in $(pwd):"
ls -lh UserGuide.pdf SettingsReference.pdf BetaTesterQuickStart.pdf 2>/dev/null
