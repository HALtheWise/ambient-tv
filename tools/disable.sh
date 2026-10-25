#!/usr/bin/env bash
# Disable packages for user 0, one pm call per package, and log them for restore.sh.
#   tools/disable.sh "batch note" pkg1 pkg2 ...
set -uo pipefail
cd "$(dirname "$0")/.."
TV=".venv/bin/python tools/tv.py"
note=$1; shift
mkdir -p restore
for p in "$@"; do
    out=$($TV "pm disable-user --user 0 $p" 2>&1)
    echo "$p: $out"
    if echo "$out" | grep -q "new state: disabled-user"; then
        grep -qx "$p.*" restore/disabled.txt 2>/dev/null || echo "$p  # $note" >> restore/disabled.txt
    fi
done
echo "--- now disabled on TV:"
$TV 'pm list packages -d' | sed 's/package://' | sort
