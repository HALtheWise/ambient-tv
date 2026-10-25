#!/usr/bin/env bash
# Build the launcher, install it on the TV, grant the Wi-Fi-name permission and (with the
# device-owner app present) make it the persistent default Home.
set -euo pipefail
cd "$(dirname "$0")/.."
TV=".venv/bin/python tools/tv.py"
tools/build-apk.sh launcher tvhome
$TV --push build/tvhome.apk /data/local/tmp/tvhome.apk
# While Home restarts, TCL's TV player (next in the back stack) would resume and re-tune its
# last input, which can drag it to the front: stop it first.
$TV 'am force-stop com.tcl.tv; pm install -r /data/local/tmp/tvhome.apk'
$TV 'pm grant bot.tvhome android.permission.ACCESS_FINE_LOCATION'
if $TV 'dpm list-owners' | grep -q bot.tvowner; then
    $TV 'am broadcast -a bot.tvowner.CMD -n bot.tvowner/.Cmd --es op set-home --es home bot.tvhome/.Home' | tail -1
fi
$TV 'am start -n bot.tvhome/.Home' >/dev/null
