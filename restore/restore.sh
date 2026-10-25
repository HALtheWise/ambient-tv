#!/usr/bin/env bash
# Re-enable everything tools/disable.sh disabled (listed in restore/disabled.txt).
#   restore/restore.sh            re-enable all packages
#   restore/restore.sh pkg ...    re-enable only these
#   restore/restore.sh --launcher also hand Home back to the stock launcher, remove the
#                                 device owner and uninstall bot.tvhome / bot.tvowner
set -uo pipefail
cd "$(dirname "$0")/.."
TV=".venv/bin/python tools/tv.py"
OWNER="am broadcast -a bot.tvowner.CMD -n bot.tvowner/.Cmd"

launcher=0
if [ "${1:-}" = "--launcher" ]; then launcher=1; shift; fi

if [ $# -gt 0 ]; then
    pkgs=("$@")
else
    # The stock Home goes first, so the TV is never left without a launcher.
    list=$(sed 's/#.*//; s/ //g; /^$/d' restore/disabled.txt)
    mapfile -t pkgs < <(grep -E 'launcherx|setupwraith' <<<"$list"; grep -vE 'launcherx|setupwraith' <<<"$list")
fi
for p in "${pkgs[@]}"; do
    echo "$p: $($TV "pm enable $p" 2>&1)"
done

if [ $launcher = 1 ]; then
    $TV "$OWNER --es op clear-home --es pkg bot.tvhome" | tail -1
    $TV 'dpm remove-active-admin bot.tvowner/.Admin'
    $TV 'pm uninstall bot.tvhome'
    $TV 'pm uninstall bot.tvowner'
    # Cast stays installed; it was hidden by basic mode, not by us.
    $TV 'input keyevent KEYCODE_HOME'
fi
