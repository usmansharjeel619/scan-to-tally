#!/usr/bin/env bash
# Parse every PowerShell script with a real PowerShell.
#
# Exists because a shell-quoting bug once shipped a bootstrap script with
# literal backslash-dollar escapes in it, which only failed on the customer's
# machine. Structural greps did not catch it; a parser does.
#
# Needs pwsh. On the build host: /opt/stt-build/pwsh/pwsh
set -euo pipefail
PWSH=${PWSH:-$(command -v pwsh || echo /opt/stt-build/pwsh/pwsh)}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -x "$PWSH" ]; then
  echo "pwsh not found; set PWSH=/path/to/pwsh" >&2
  exit 2
fi

fail=0
while IFS= read -r f; do
  out=$("$PWSH" -NoProfile -Command "
    \$errs = \$null; \$toks = \$null
    [System.Management.Automation.Language.Parser]::ParseFile('$f', [ref]\$toks, [ref]\$errs) | Out-Null
    if (\$errs -and \$errs.Count) {
      \$errs | ForEach-Object { 'line {0}: {1}' -f \$_.Extent.StartLineNumber, \$_.Message }
      exit 1
    }") && echo "ok   $(basename "$f")" || { echo "FAIL $(basename "$f")"; echo "$out"; fail=1; }
# .ps1.template included deliberately. bootstrap.ps1.template is the installer
# that runs as Administrator on the Tally machine, and it was the ONE script not
# being checked -- the placeholders sit inside quoted strings, so it parses
# exactly as the generated file does and there is no reason to skip it.
done < <(find "$ROOT/scripts" "$ROOT/dist" \
    \( -name '*.ps1' -o -name '*.ps1.template' \) 2>/dev/null | sort)

exit $fail
