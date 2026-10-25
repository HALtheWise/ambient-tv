# ambient-tv

Debloat and custom home screen for a TCL Google TV (board G08, "Smart TV Pro",
Android 14 / SDK 34, build `UTT2.250416.001`), set up in Google TV **basic mode** with no
Google account. Everything here is reversible.

What is on the TV now:

- **25 packages disabled**: TCL telemetry, updater and Alexa (`com.tcl.ttvs`), TCL content and
  shop-demo apps, TCL/Google background installers, Android 14 ad modules, Prime Video, Netflix,
  and the stock Google TV launcher. The full list with reasons is
  [`restore/disabled.txt`](restore/disabled.txt).
- **Chromecast built-in restored.** Basic mode hides it; a device-owner app (`owner/`,
  package `bot.tvowner`) unhides it. YouTube stays enabled in guest mode so casts open in the
  real app (with playback speed under the player's gear icon).
- **AirPlay** switched on in Settings.
- **An ambient home screen** (`launcher/`, package `bot.tvhome`): Game of Life (or Doom fire,
  fractals, reaction-diffusion, Langton's ants) behind a clock, Cast, AirPlay and Wi-Fi status and
  a screen-off timer, with a shelf to switch scenes and reach the few apps that matter.

## Requirements

- `.venv` with `adb-shell` (`python3 -m venv .venv && .venv/bin/pip install adb-shell`).
  `~/.android/adbkey` is your ADB key; `tools/tv.py` makes one if it is missing.
- Android SDK at `~/Android/Sdk` with `platforms;android-34` and `build-tools;35.0.0`, and a JDK.
  (build-tools 34's d8 crashes on JDK 21 class files.)
- The TV's address in `tools/tv.env` (copy `tools/tv.env.example`), or `TV_HOST` / `TV_PORT` /
  `TV_MAC` in the environment.
- The signing key, `build/debug.keystore`, to install anything (see below).

## Tools

| | |
|---|---|
| `tools/tv.py 'cmd'` | run a shell command on the TV |
| `tools/tv.py --screenshot shots/x.png [KEY ...]` | press keys, then capture the screen |
| `tools/tv.py --sleep` / `--wake` | standby via the device owner / wake with a Wake-on-LAN packet (MAC in `TV_MAC`) |
| `tools/disable.sh "note" pkg ...` | disable packages one by one and log them for restore |
| `tools/build-apk.sh <dir> <name>` | build an app dir into `build/<name>.apk` (no Gradle) |
| `tools/install-launcher.sh` | build, install and set the launcher as Home |
| `restore/restore.sh` | re-enable everything that was disabled |

Screenshots of Live TV, HDMI and Netflix come back empty (protected content); check
`dumpsys activity activities | grep topResumedActivity` instead.

## Re-enabling apps

```sh
restore/restore.sh com.netflix.ninja          # one app
restore/restore.sh                            # every package in restore/disabled.txt
restore/restore.sh --launcher                 # everything, plus stock Home back, device owner
                                              # removed, both of our apps uninstalled
```

`com.tcl.ttvs` holds the firmware updater: re-enable it to get TCL updates again (and its
telemetry with it). A firmware update may re-enable packages or reset Home; re-run the disable
steps afterwards.

## The home screen

An ambient display, not an app launcher (`launcher/`, package `bot.tvhome`):

- **Game of Life**, full screen, rotating through 25 famous patterns (`launcher/assets/life/`,
  from Golly's collection and the LifeWiki). The caption names the pattern and its discoverer
  (press Up for a line on why it matters), with the live generation and population (drawn as glowing digits through a
  1/30 s exposure: fast digits blur evenly, and changed digits fade out over ~0.3 s). The engine is Bill Gosper's
  HashLife (`Life.java`, nodes in primitive arrays so the garbage collector never has to trace
  them), on its own thread (`LifeSim.java`): breeders, the Turing machine and the prime sieves
  run at thousands of generations a second, and a slow step delays the pattern, never a frame.
  A line-fitting camera follows the pattern's mass and zooms out as it grows (methuselahs frame
  their main cluster, so escaping gliders don't pull the camera out).
  Drawing (`LifeRenderer.java`) keeps the visible universe on the GPU at one texel per block of
  2^k cells, half a pixel to a pixel and a quarter across, and per frame touches only what
  changed: the two snapshots' quadtrees are diffed (hash-consed nodes, so an unchanged node
  means an unchanged region), changed 16 x 16-texel tiles are rasterized and merged in by a
  compute shader, which keeps each texel's birth frame so new life glows and settled life dims
  at any zoom. A zoom past a power of two rebuilds the next level from the current one on the
  GPU over a few frames, ahead of time. `logcat -s LifeFrames` prints frame timings.
  Patterns and their speeds and durations are listed in `Catalog.java`. Right skips to the
  next, Left goes back.
- Bottom left: clock and date (Outfit, SIL OFL, in `launcher/assets/fonts`).
- Top right, **status chips** (Up to reach them):
  - Google Cast and AirPlay, with the name phones see. Select it for the join card: the Wi-Fi
    network's QR code (see below) and the receiver names.
  - **Screen-off timer**: counts down 5 minutes from the last key press, then puts the TV to
    sleep through the device owner (`lockNow`). Select it to switch to *Always on*
    (remembered).
  - **Settings** (gear), since the remote's Settings button no longer works.
- **Down opens the shelf**: an *Ambient* row (the backgrounds below, and Shuffle) and
  an *Apps* row (YouTube, Inputs, TV, Settings, Play Store, then the rest by name). Inputs is a
  menu of HDMI ports labelled with their HDMI-CEC device names, plus Live TV. Back closes it.

### Wi-Fi QR code

The TV cannot read back its own Wi-Fi password, so give it once over adb (the receiver requires
`DUMP`, which only the shell has):

```sh
tools/tv.py "am broadcast -a bot.tvhome.CONFIG -n bot.tvhome/.Config --es wifiPassword 'the password'"
# optional: --es wifiSecurity WPA|WEP|nopass   (default WPA)
```

The same receiver takes two settings for testing, applied the next time Home starts:
`--es background life|fire|fractal|rd|ant|shuffle|none` (`none`: a black stage, for measuring
the interface alone) and `--es lifeFirst 52513m.rle` (start Life on that pattern, once). `--es lifeVerify 1` checks what Life draws against the simulation, a row of tiles a frame (`logcat -s LifeVerify`; it costs frames, so set it back to `0`).

### Changing the Apps row

Edit `launcher/res/values/config.xml`:

- `dock_apps`: package names shown first, left to right. List the apps with
  `tools/tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER'`.
- `hidden_apps`: packages never shown.

Then run `tools/install-launcher.sh`.

### Backgrounds

Life is one of several backgrounds; pick another from the shelf's Ambient row and it stays
until you pick again (the current one is marked in amber). Clock, chips and caption stay on top.
Right moves to the next pattern or variant and Left back; variants also change by themselves
every 4 minutes. Captions show who and when; press Up (to the chips) for a line about what
you are looking at.
*Shuffle* moves between all backgrounds, Life included, every 8 minutes.

| Background | Variants |
|---|---|
| Life: HashLife, 25 patterns | rotates on its own |
| Fireplace: the PSX Doom fire (1995), silent | Hearth, Doom (unfiltered pixels), Embers |
| Fractals: rendered once at 2x resolution, then panned exactly half a pixel a frame along the edge (steered by a double-precision boundary search), only new strips computed | Seahorse, Elephant, Triple Spiral and Scepter Valleys, the antenna's mini Mandelbrot, Douady's rabbit, San Marco, Siegel disk, Burning Ship, and Wandering (random edges) |
| Reaction–diffusion: Gray-Scott on the GPU in half floats (a five-read Laplacian), drawn at half resolution and scaled up by the display hardware | Coral, Mitosis, Maze, Solitons, Waves |
| Langton's ant and Turk–Propp multi-colour ants, on a wrapping board whose size varies per run, with step and cell counters | RL, LLRR, RRLLLRLLLRRR, LRRRRRLLR, LLRRRLRLRLLR |

The scenes run in OpenGL ES 3 (`SceneView`, `*Scene.java`). The GPU (a Mali-G52) is shared
with the interface and with SurfaceFlinger, which composites both layers on it every frame, so
every scene is measured to leave the shelf animation at full frame rate. To add one: subclass `Scene`, add
a key to `BACKGROUNDS` and `newScene()` in `Home.java`, and a banner. The shelf banners are
rendered by `tools/banners.py` (numpy, Pillow) with the same algorithms.

## How Home was taken over

Android only consults the user's preferred Home when the top candidates have equal priority.
The stock launcher (`launcherx`) declares HOME priority 2 and the setup wizard (`setupwraith`)
priority 1, while a sideloaded app is capped at 0, so both are disabled. The setup wizard did
not own the saved Wi-Fi network (`dumpsys wifi | grep cname` shows `android.uid.system`), so
disabling it did not drop Wi-Fi.

Cost: the stock launcher also answered three remote buttons.

- **Settings button**: did nothing after the takeover. It sends
  `TOGGLE_NOTIFICATION_HANDLER_PANEL`, which SystemUI only resolves to system apps, so the
  launcher cannot answer it. Use the gear chip. (The button sends `KEYCODE_NOTIFICATION`; an
  accessibility service can catch it, but filtering keys breaks key repeat everywhere, so held
  volume and D-pad stop working. Not worth it.)
- **Guide** (app picker) and the featured-app button: also gone.
- **Input button**: unaffected (TCL's own input bar).

## Device owner (`bot.tvowner`)

Device ownership gives an app policy APIs the adb shell lacks on Android 14 (unhiding system
apps, persistent preferred activities, per-app permission grants, always-on VPN). It only works
while the TV has **no Google account**; signing in to Google TV would need it removed first.
It is built `testOnly`, so it can be removed without a factory reset:

```sh
tools/tv.py 'dpm remove-active-admin bot.tvowner/.Admin'
```

It also puts the TV to sleep for the screen-off timer: `bot.tvowner.SLEEP`, guarded by a
signature permission so only apps signed with this repo's key can send it.

Commands, adb-only (the receiver requires the `DUMP` permission, which the shell has and apps
do not):

```sh
tools/tv.py 'am broadcast -a bot.tvowner.CMD -n bot.tvowner/.Cmd --es op status --es pkg <pkg>'
# ops: status, hide, unhide, enable-system, suspend, unsuspend, set-home (--es home pkg/.Act), clear-home, sleep
```

How Cast was restored: `unhide` cleared the hidden flag, but the package stayed in the
system-disabled state (2), which neither the shell nor a device owner can clear. Removing it for
the user and reinstalling it (`pm uninstall --user 0` then `pm install-existing`) reset it to
default, and Cast came up after a reboot.

## Known issues

- **Cast and Android 14's app freezer.** Without a system app bound to it, Cast
  (`mediashell`) is cached and frozen within minutes, so phones discover it slowly or not at all
  and port 8009 fills with `CLOSE_WAIT` connections. The launcher binds `CastReceiverService`
  to keep it at visible priority. Check with
  `tools/tv.py 'dumpsys activity oom | grep mediashell'` (should read `vis ... (service)`), and
  if Home is ever switched away from `bot.tvhome`, expect Cast to go flaky again.
- **Netflix is disabled.** Casting Netflix may need the app (YouTube falls back to its web
  receiver, Netflix untested): `restore/restore.sh com.netflix.ninja`.
- The Input bar still shows tiles for disabled TCL apps (TCL tv+, User Guide); they do nothing.

## Changing the setup

For anyone in the house who wants to change something.

1. **Get the signing key.** Ask whoever set the TV up for `debug.keystore` and put it in `build/`.
   It is not in git. Both apps must be signed with it: Android refuses an update signed with
   any other key, and `tools/build-apk.sh` stops rather than make a new one.
2. **Find the TV.** Copy `tools/tv.env.example` to `tools/tv.env` and fill in the IP address
   (Settings, Network & Internet on the TV).
3. **Turn on debugging.** On the TV: Settings, Device Preferences, About, press OK on Build
   seven times. Then Developer options, USB debugging on. This TV then also listens for ADB on
   the network (port 5555).
4. **Connect.** Run `.venv/bin/python tools/tv.py 'echo ok'` and accept the *Allow USB
   debugging?* prompt on the TV. You only need to do this once per computer.
5. **Change things.** Edit the code, then run `tools/install-launcher.sh`. Check
   `logcat -s LifeFrames` for frame rate: every scene should hold 60 fps (or its own rate)
   with the shelf open.
6. **Turn debugging off afterwards.** Port 5555 is unencrypted, and anyone on the network with
   an approved key has a shell on the TV. Switch off USB debugging in Developer options, then
   check that `nc -zv <tv ip> 5555` reports *Connection refused*.

Things not to do:

- **Don't sign in to Google on the TV.** The device owner (`bot.tvowner`) needs zero accounts;
  signing in means removing it first, and with it the Cast fix and the screen-off timer.
- **Don't uninstall `bot.tvowner`**, or `bot.tvhome` to "fix" a signature error. A device owner
  can only be set again on a TV with no accounts, through adb. A signature error means the wrong
  key: get the right one.
- **Don't disable Android's own packages** (systemui, settings, the package installer and the
  like). Everything here uses `pm disable-user`, so `restore/restore.sh` undoes it.

The TV's state from before any of this (package lists, settings, `getprop`) was dumped to
`backup/`, which stays on the computer that made it: it holds device IDs and network details.

## Licenses

MIT (`LICENSE`), except: the QR code library in `launcher/src/io/nayuki/` (MIT, Project Nayuki),
the Outfit font (SIL OFL, `launcher/assets/fonts/OFL.txt`), and the Life patterns in
`launcher/assets/life/`, from Golly's collection and the LifeWiki, credited to their
discoverers in `Catalog.java`.
